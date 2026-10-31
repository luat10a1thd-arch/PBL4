package com.pbl4.mailserver.outbound;

import com.pbl4.mailserver.config.ServerConfig;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Giao thư qua SMTP relay có xác thực (OUTBOUND_MODE=RELAY): STARTTLS bắt buộc (hoặc TLS ngầm ở cổng 465),
 * rồi AUTH PLAIN / AUTH LOGIN bằng PBL4_OUTBOUND_SMTP_*. Không bao giờ ném exception.
 *
 * Lỗi cấu hình/xác thực relay là lỗi của quản trị viên chứ không phải của người nhận nên luôn là DEFERRED
 * (không sinh thư báo lỗi hàng loạt); chỉ phản hồi 5xx cho MAIL FROM/RCPT/DATA mới là FAILED.
 */
public final class RelayDeliverer implements Deliverer {

    public record Settings(String host, int port, String user, String pass, String ehloHostname,
                           boolean trustAll, boolean implicitTls,
                           int connectTimeoutMs, int commandTimeoutMs, int dataTimeoutMs,
                           SSLContext tlsContext) {
        /** tlsContext = null nghĩa là dùng kho tin cậy mặc định của JVM (khi trustAll=false). */
        public static Settings fromServerConfig() {
            return new Settings(ServerConfig.OUTBOUND_SMTP_HOST, ServerConfig.OUTBOUND_SMTP_PORT,
                    ServerConfig.OUTBOUND_SMTP_USER, ServerConfig.OUTBOUND_SMTP_PASS, ServerConfig.MAIL_HOSTNAME,
                    ServerConfig.OUTBOUND_TRUST_ALL, ServerConfig.OUTBOUND_SMTP_PORT == 465,
                    20_000, 5 * 60_000, 10 * 60_000, null);
        }
    }

    private final Settings settings;

    public RelayDeliverer() {
        this(Settings.fromServerConfig());
    }

    RelayDeliverer(Settings settings) {
        this.settings = settings;
    }

    @Override
    public List<RcptResult> deliver(String envelopeFrom, String domain, List<String> rcpts, byte[] raw) {
        if (rcpts == null || rcpts.isEmpty()) return List.of();
        List<String> all = new ArrayList<>(rcpts.size());
        for (String r : rcpts) all.add(r == null ? "" : r);
        try {
            return doDeliver(envelopeFrom, all, raw);
        } catch (Exception e) {
            return SmtpClient.all(all, RcptStatus.DEFERRED, "Lỗi nội bộ khi giao thư qua relay: " + e);
        }
    }

    private List<RcptResult> doDeliver(String envelopeFrom, List<String> all, byte[] raw) {
        if (raw == null) return SmtpClient.all(all, RcptStatus.FAILED, "Nội dung thư rỗng (null)");
        if (settings.host() == null || settings.host().isEmpty() || settings.user() == null || settings.user().isEmpty()) {
            return SmtpClient.all(all, RcptStatus.DEFERRED,
                    "Cấu hình SMTP Relay chưa hoàn chỉnh (cần PBL4_OUTBOUND_SMTP_HOST và PBL4_OUTBOUND_SMTP_USER)");
        }
        // Relay (Gmail, SendGrid...) thường chỉ chấp nhận MAIL FROM là tài khoản đã xác thực, như logic cũ.
        String from = settings.user().indexOf('@') > 0 ? settings.user() : (envelopeFrom == null ? "" : envelopeFrom);
        if (!SmtpClient.isSafeAddress(from, true)) {
            return SmtpClient.all(all, RcptStatus.FAILED, "Địa chỉ người gửi phong bì không hợp lệ");
        }

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
            List<RcptResult> sent = relay(from, good, raw);
            for (int k = 0; k < goodIdx.size(); k++) results[goodIdx.get(k)] = sent.get(k);
        }
        List<RcptResult> out = new ArrayList<>(results.length);
        for (int i = 0; i < results.length; i++) {
            out.add(results[i] != null ? results[i] : new RcptResult(all.get(i), RcptStatus.DEFERRED, "Không có kết quả"));
        }
        return out;
    }

    private List<RcptResult> relay(String from, List<String> rcpts, byte[] raw) {
        SmtpClient c = null;
        try {
            InetAddress ip = InetAddress.getByName(settings.host());
            c = SmtpClient.connect(ip, settings.port(), settings.connectTimeoutMs(),
                    settings.commandTimeoutMs(), settings.dataTimeoutMs());
            if (settings.implicitTls()) {
                c.upgradeTls(tlsContext(), !settings.trustAll(), settings.host(), settings.port());
            }

            SmtpClient.Reply g = c.readReply();
            if (g.code() != 220) return deferred(rcpts, "Lỗi kết nối tới SMTP Relay Gateway: " + g.text());

            SmtpClient.Reply e = c.ehlo(settings.ehloHostname());
            if (!e.positive()) return deferred(rcpts, "SMTP Relay từ chối EHLO: " + e.text());

            if (!c.isTls()) {
                if (!e.hasExtension("STARTTLS")) {
                    return deferred(rcpts, "SMTP Relay không hỗ trợ STARTTLS; không gửi mật khẩu ở dạng không mã hóa");
                }
                SmtpClient.Reply t = c.command("STARTTLS");
                if (t.code() != 220) return deferred(rcpts, "SMTP Relay từ chối STARTTLS: " + t.text());
                try {
                    c.upgradeTls(tlsContext(), !settings.trustAll(), settings.host(), settings.port());
                } catch (IOException | java.security.GeneralSecurityException ex) {
                    return deferred(rcpts, "Bắt tay TLS với SMTP Relay thất bại: " + ex);
                }
                e = c.ehlo(settings.ehloHostname());
                if (!e.positive()) return deferred(rcpts, "SMTP Relay từ chối EHLO sau STARTTLS: " + e.text());
            }

            String authFail = authenticate(c, e);
            if (authFail != null) return deferred(rcpts, authFail);

            SmtpClient.Outcome o = SmtpClient.transact(c, from, rcpts, raw, e.sizeLimit());
            if (o.finished()) return o.results();
            return deferred(rcpts, "SMTP Relay: " + o.retryReason());
        } catch (IOException ex) {
            return deferred(rcpts, "Lỗi kết nối Outbound tới SMTP Relay: " + SmtpClient.describe(ex));
        } catch (Exception ex) {
            return deferred(rcpts, "Lỗi Outbound tới SMTP Relay: " + ex);
        } finally {
            if (c != null) c.closeQuietly();
        }
    }

    /** Trả null nếu xác thực thành công, ngược lại là mô tả lỗi (không bao giờ chứa mật khẩu). */
    private String authenticate(SmtpClient c, SmtpClient.Reply ehlo) throws IOException {
        String mechs = ehlo.extensionArgs("AUTH");
        if (mechs == null) return "SMTP Relay không quảng cáo AUTH";
        String up = " " + mechs.toUpperCase(Locale.ROOT) + " ";
        SmtpClient.Reply r;
        if (up.contains(" PLAIN ")) {
            byte[] cred = ("\0" + settings.user() + "\0" + settings.pass()).getBytes(StandardCharsets.UTF_8);
            r = c.command("AUTH PLAIN " + Base64.getEncoder().encodeToString(cred));
        } else if (up.contains(" LOGIN ")) {
            r = c.command("AUTH LOGIN");
            if (r.code() != 334) return "SMTP Relay từ chối AUTH LOGIN: " + r.text();
            r = c.command(Base64.getEncoder().encodeToString(settings.user().getBytes(StandardCharsets.UTF_8)));
            if (r.code() != 334) return "SMTP Relay từ chối tên đăng nhập: " + r.text();
            r = c.command(Base64.getEncoder().encodeToString(settings.pass().getBytes(StandardCharsets.UTF_8)));
        } else {
            return "SMTP Relay không hỗ trợ AUTH PLAIN/LOGIN (chỉ có: " + mechs + ")";
        }
        return r.code() == 235 ? null : "Xác thực SMTP Relay thất bại: " + r.text();
    }

    private SSLContext tlsContext() throws java.security.GeneralSecurityException {
        if (settings.trustAll()) return SmtpClient.trustAllContext();
        return settings.tlsContext() != null ? settings.tlsContext() : SSLContext.getDefault();
    }

    private static List<RcptResult> deferred(List<String> rcpts, String why) {
        return SmtpClient.all(rcpts, RcptStatus.DEFERRED, why);
    }
}
