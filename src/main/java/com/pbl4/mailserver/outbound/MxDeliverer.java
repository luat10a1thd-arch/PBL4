package com.pbl4.mailserver.outbound;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.dns.DnsException;
import com.pbl4.mailserver.dns.DnsLookup;
import com.pbl4.mailserver.dns.MxRecord;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.IDN;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Giao thư THẲNG tới MX của domain đích (OUTBOUND_MODE=DIRECT).
 * Không bao giờ ném exception; luôn trả đúng một RcptResult cho mỗi người nhận, cùng thứ tự.
 */
public final class MxDeliverer implements Deliverer {

    /** Cấu hình tách khỏi ServerConfig (các hằng static final) để test được từng chế độ. */
    public record Settings(String ehloHostname, int remotePort, String tlsMode, boolean useIpv6,
                           int connectTimeoutMs, int commandTimeoutMs, int dataTimeoutMs,
                           SSLContext requiredTlsContext) {
        public Settings {
            tlsMode = tlsMode == null ? "OPPORTUNISTIC" : tlsMode.trim().toUpperCase(Locale.ROOT);
        }

        /** requiredTlsContext = null nghĩa là dùng kho tin cậy mặc định của JVM. */
        public static Settings fromServerConfig() {
            return new Settings(ServerConfig.MAIL_HOSTNAME, ServerConfig.OUTBOUND_REMOTE_PORT, ServerConfig.OUTBOUND_TLS,
                    ServerConfig.OUTBOUND_USE_IPV6, 20_000, 5 * 60_000, 10 * 60_000, null);
        }
    }

    private final Settings settings;
    private final Supplier<DnsLookup> dns;

    public MxDeliverer() {
        this(Settings.fromServerConfig(), DnsLookup::get);
    }

    MxDeliverer(Settings settings, Supplier<DnsLookup> dns) {
        this.settings = settings;
        this.dns = dns;
    }

    @Override
    public List<RcptResult> deliver(String envelopeFrom, String domain, List<String> rcpts, byte[] raw) {
        if (rcpts == null || rcpts.isEmpty()) return List.of();
        try {
            return doDeliver(envelopeFrom, domain, rcpts, raw);
        } catch (Exception e) {
            return SmtpClient.all(safe(rcpts), RcptStatus.DEFERRED, "Lỗi nội bộ khi giao thư: " + e);
        }
    }

    private static List<String> safe(List<String> rcpts) {
        List<String> l = new ArrayList<>(rcpts.size());
        for (String r : rcpts) l.add(r == null ? "" : r);
        return l;
    }

    private List<RcptResult> doDeliver(String envelopeFrom, String domainIn, List<String> rcptsIn, byte[] raw) {
        List<String> all = safe(rcptsIn);
        if (raw == null) return SmtpClient.all(all, RcptStatus.FAILED, "Nội dung thư rỗng (null)");
        String from = envelopeFrom == null ? "" : envelopeFrom;
        if (!SmtpClient.isSafeAddress(from, true)) {
            return SmtpClient.all(all, RcptStatus.FAILED, "Địa chỉ người gửi phong bì không hợp lệ");
        }

        // Người nhận không an toàn bị loại ngay (FAILED), phần còn lại mới đi gửi.
        RcptResult[] results = new RcptResult[all.size()];
        List<String> good = new ArrayList<>();
        List<Integer> goodIdx = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (SmtpClient.isSafeAddress(all.get(i), false)) {
                good.add(all.get(i));
                goodIdx.add(i);
            } else {
                results[i] = new RcptResult(all.get(i), RcptStatus.FAILED, "Địa chỉ người nhận không hợp lệ");
            }
        }
        if (!good.isEmpty()) {
            List<RcptResult> sent = deliverValid(from, domainIn, good, raw);
            for (int k = 0; k < goodIdx.size(); k++) results[goodIdx.get(k)] = sent.get(k);
        }
        List<RcptResult> out = new ArrayList<>(results.length);
        for (int i = 0; i < results.length; i++) {
            out.add(results[i] != null ? results[i] : new RcptResult(all.get(i), RcptStatus.DEFERRED, "Không có kết quả"));
        }
        return out;
    }

    /** Giao cho các người nhận hợp lệ; trả đúng good.size() kết quả. */
    private List<RcptResult> deliverValid(String from, String domainIn, List<String> good, byte[] raw) {
        String domain = normalizeHost(domainIn);
        if (domain.isEmpty()) return SmtpClient.all(good, RcptStatus.FAILED, "Thiếu domain đích");
        try {
            domain = IDN.toASCII(domain);
        } catch (IllegalArgumentException e) {
            return SmtpClient.all(good, RcptStatus.FAILED, "Tên miền đích không hợp lệ: " + domain);
        }

        DnsLookup d = dns.get();

        // 1) MX
        List<MxRecord> mx;
        try {
            mx = d.mx(domain);
        } catch (DnsException e) {
            return SmtpClient.all(good, e.temporary() ? RcptStatus.DEFERRED : RcptStatus.FAILED,
                    "Lỗi DNS " + (e.temporary() ? "tạm thời" : "vĩnh viễn") + " khi tra MX của " + domain + ": " + e.getMessage());
        }
        if (mx == null) mx = List.of();

        List<String> hosts = new ArrayList<>();
        boolean implicitMx = false;
        if (mx.isEmpty()) {
            hosts.add(domain); // RFC 5321 mục 5.1: không có MX thì dùng chính domain (bản ghi A/AAAA)
            implicitMx = true;
        } else {
            for (MxRecord m : mx) {
                String h = normalizeHost(m.host());
                if (!h.isEmpty() && !".".equals(h)) hosts.add(h);
            }
            if (hosts.isEmpty()) { // chỉ có null-MX (RFC 7505)
                return SmtpClient.all(good, RcptStatus.FAILED, "Domain " + domain + " khai báo null MX (\".\"): không nhận thư");
            }
        }

        // 2) Lần lượt từng MX, từng IP
        List<String> notes = new ArrayList<>();
        for (String host : hosts) {
            List<InetAddress> addrs;
            try {
                addrs = d.addresses(host);
            } catch (DnsException e) {
                if (implicitMx && !e.temporary()) {
                    return SmtpClient.all(good, RcptStatus.FAILED, "Domain " + domain + " không phân giải được: " + e.getMessage());
                }
                notes.add(host + ": lỗi DNS " + (e.temporary() ? "tạm thời" : "vĩnh viễn") + " (" + e.getMessage() + ")");
                continue;
            }
            if (addrs == null || addrs.isEmpty()) {
                if (implicitMx) {
                    return SmtpClient.all(good, RcptStatus.FAILED,
                            "Domain " + domain + " không có bản ghi MX lẫn A/AAAA (không tồn tại hoặc không nhận thư)");
                }
                notes.add(host + ": không có địa chỉ A/AAAA");
                continue;
            }
            List<InetAddress> ordered = order(addrs);
            if (ordered.isEmpty()) {
                notes.add(host + ": chỉ có địa chỉ IPv6 nhưng OUTBOUND_USE_IPV6=false");
                continue;
            }
            for (InetAddress ip : ordered) {
                SmtpClient.Outcome o = attempt(host, ip, from, good, raw, true);
                if (o.retryPlain()) {
                    notes.add(host + " [" + ip.getHostAddress() + "]: " + o.retryReason() + " -> thử lại không TLS");
                    o = attempt(host, ip, from, good, raw, false);
                }
                if (o.finished()) return o.results();
                notes.add(host + " [" + ip.getHostAddress() + "]: " + o.retryReason());
            }
        }
        return SmtpClient.all(good, RcptStatus.DEFERRED,
                "Không giao được tới MX nào của " + domain + ": " + SmtpClient.abbreviate(String.join("; ", notes), 1500));
    }

    /** IPv4 trước; IPv6 chỉ khi OUTBOUND_USE_IPV6. */
    private List<InetAddress> order(List<InetAddress> addrs) {
        List<InetAddress> v4 = new ArrayList<>();
        List<InetAddress> v6 = new ArrayList<>();
        for (InetAddress a : addrs) {
            if (a instanceof Inet4Address) v4.add(a);
            else if (a instanceof Inet6Address) v6.add(a);
        }
        if (settings.useIpv6()) v4.addAll(v6);
        return v4;
    }

    private SmtpClient.Outcome attempt(String host, InetAddress ip, String from, List<String> rcpts, byte[] raw, boolean allowTls) {
        SmtpClient c = null;
        try {
            c = SmtpClient.connect(ip, settings.remotePort(), settings.connectTimeoutMs(),
                    settings.commandTimeoutMs(), settings.dataTimeoutMs());

            SmtpClient.Reply g = c.readReply();
            if (g.code() != 220) return SmtpClient.Outcome.retry("lời chào bị từ chối: " + g.text());

            SmtpClient.Reply e = c.ehlo(settings.ehloHostname());
            if (!e.positive()) return SmtpClient.Outcome.retry("EHLO/HELO bị từ chối: " + e.text());

            String mode = allowTls ? settings.tlsMode() : "OFF";
            if (!"OFF".equals(mode)) {
                boolean required = "REQUIRED".equals(mode);
                if (e.hasExtension("STARTTLS")) {
                    SmtpClient.Reply t = c.command("STARTTLS");
                    if (t.code() == 220) {
                        try {
                            SSLContext ctx = required
                                    ? (settings.requiredTlsContext() != null ? settings.requiredTlsContext() : SSLContext.getDefault())
                                    : SmtpClient.trustAllContext();
                            c.upgradeTls(ctx, required, host, settings.remotePort());
                        } catch (IOException | java.security.GeneralSecurityException | RuntimeException ex) {
                            c.closeQuietly();
                            String why = "bắt tay TLS thất bại: " + ex;
                            return required ? SmtpClient.Outcome.retry(why) : SmtpClient.Outcome.retryPlain(why);
                        }
                        e = c.ehlo(settings.ehloHostname());
                        if (!e.positive()) return SmtpClient.Outcome.retry("EHLO sau STARTTLS bị từ chối: " + e.text());
                    } else if (required) {
                        return SmtpClient.Outcome.retry("STARTTLS bị từ chối: " + t.text());
                    } // cơ hội + bị từ chối: tiếp tục không TLS trên cùng kết nối
                } else if (required) {
                    return SmtpClient.Outcome.retry("máy chủ không quảng cáo STARTTLS (OUTBOUND_TLS=REQUIRED)");
                }
            }
            return SmtpClient.transact(c, from, rcpts, raw, e.sizeLimit());
        } catch (IOException ex) {
            return SmtpClient.Outcome.retry(SmtpClient.describe(ex));
        } catch (RuntimeException ex) {
            return SmtpClient.Outcome.retry("lỗi nội bộ: " + ex);
        } finally {
            if (c != null) c.closeQuietly();
        }
    }

    private static String normalizeHost(String h) {
        if (h == null) return "";
        String s = h.trim().toLowerCase(Locale.ROOT);
        while (s.length() > 1 && s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
