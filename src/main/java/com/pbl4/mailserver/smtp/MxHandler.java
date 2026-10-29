package com.pbl4.mailserver.smtp;

import com.pbl4.mailserver.auth.InboundAuthenticator;
import com.pbl4.mailserver.auth.InboundContext;
import com.pbl4.mailserver.auth.InboundVerdict;
import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.Folder;
import com.pbl4.mailserver.core.MailService;
import com.pbl4.mailserver.core.UserStore;
import com.pbl4.mailserver.ratelimit.AbuseGuard;
import com.pbl4.mailserver.smtp.wire.DataLineTooLongException;
import com.pbl4.mailserver.smtp.wire.DataTooLargeException;
import com.pbl4.mailserver.smtp.wire.SmtpConnection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phiên SMTP INBOUND (MX, cổng 25): nhận thư từ MTA khác, KHÔNG AUTH, chỉ giao cho người dùng nội bộ.
 *
 * Nguyên tắc an toàn: KHÔNG BAO GIỜ là open relay. RCPT chỉ nhận domain MAIL_DOMAIN; mọi domain khác
 * bị 554 5.7.1. Handler này không có đường nào đưa thư ra ngoài (không gọi OutboundQueue).
 *
 * Mã trả lời:
 *  - Lỗi TẠM (451/452/450): MTA gửi sẽ giữ thư và thử lại. Dùng cho lỗi lưu trữ/IO, TEMPFAIL, vượt tốc độ.
 *  - Lỗi VĨNH VIỄN (5xx): MTA gửi sinh bounce. Dùng cho user không tồn tại, relay, quá lớn, REJECT.
 *  - User không tồn tại luôn là 550 5.1.1 (không bao giờ 451).
 *
 * Nhiều người nhận, sau DATA chỉ có MỘT mã trả lời. LỰA CHỌN: giao riêng từng người nhận.
 *  - Có ít nhất một người nhận thành công: trả 250. Người nhận thất bại (quota đầy/lỗi tạm) bị bỏ
 *    và chỉ ghi log. Lý do: trả 4xx/5xx sẽ khiến MTA gửi thử lại TOÀN BỘ danh sách và người đã nhận
 *    được thư sẽ nhận trùng. Đổi lại người thất bại không nhận được thư (nên cần cơ chế bounce riêng nếu muốn).
 *  - Tất cả thất bại: có lỗi tạm -> 451 4.3.0 (MTA retry); toàn quota -> 552 5.2.2; còn lại -> 554 5.3.0.
 *
 * Mỗi phiên dùng một instance mới (SmtpListener gọi factory). Không thread-safe.
 */
public final class MxHandler implements SmtpListener.SessionHandler {

    /** Trả địa chỉ chuẩn hóa (chữ thường) nếu là người dùng nội bộ tồn tại, ngược lại null. */
    @FunctionalInterface
    public interface UserDirectory {
        String resolve(String address);
    }

    /** Giao thư cho người dùng nội bộ (mặc định MailService::deliverLocal). */
    @FunctionalInterface
    public interface LocalDelivery {
        MailService.DeliveryResult deliver(String envelopeFrom, Set<String> localRcpts, byte[] raw, Folder folder);
    }

    private static final int MAX_RECEIVED_HOPS = 25;
    private static final int MAX_PROTOCOL_ERRORS = 30;
    private static final int MAX_PATH_LENGTH = 256;
    private static final DateTimeFormatter RECEIVED_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AbuseGuard.Engine guard;
    private final InboundAuthenticator authenticator;
    private final LocalDelivery delivery;
    private final UserDirectory users;
    private final long maxMessageBytes;
    private final int maxRecipients;

    /** Dùng cho SmtpListener: lấy mọi thứ từ cấu hình/lớp thật. */
    public MxHandler() {
        this(AbuseGuard.defaultEngine(),
                InboundAuthenticator.get(),
                MailService::deliverLocal,
                MxHandler::defaultResolve,
                ServerConfig.MAX_MESSAGE_BYTES,
                ServerConfig.SMTP_MAX_RECIPIENTS);
    }

    /** Dùng cho test: thay từng phụ thuộc bằng bản giả. */
    public MxHandler(AbuseGuard.Engine guard, InboundAuthenticator authenticator, LocalDelivery delivery,
                     UserDirectory users, long maxMessageBytes, int maxRecipients) {
        this.guard = guard;
        this.authenticator = authenticator;
        this.delivery = delivery;
        this.users = users;
        this.maxMessageBytes = maxMessageBytes;
        this.maxRecipients = Math.max(1, maxRecipients);
    }

    private static String defaultResolve(String address) {
        String c = UserStore.canonical(address);
        return (c != null && UserStore.exists(c)) ? c : null;
    }

    @Override
    public void handle(SmtpConnection c) throws Exception {
        new Session(c).run();
    }

    // ================================================================== phiên

    private enum Outcome { OK, QUOTA, TEMP, PERM }

    private final class Session {
        private final SmtpConnection c;
        private final String ip;

        private String heloName;            // null = chưa HELO/EHLO
        private boolean haveMail;
        private String mailFrom = "";       // "" = null sender
        private final Set<String> rcpts = new LinkedHashSet<>();
        private int errors;
        private Boolean tlsAvail;           // cache: tránh nạp chứng chỉ lại mỗi EHLO

        Session(SmtpConnection c) {
            this.c = c;
            this.ip = c.remoteIp();
        }

        void run() throws IOException {
            c.reply("220 " + host() + " ESMTP");
            String line;
            while ((line = c.readCommand()) != null) {
                if (!command(line)) break;
            }
        }

        // ------------------------------------------------------------ tiện ích

        private String host() {
            return ServerConfig.MAIL_HOSTNAME;
        }

        private String domain() {
            return stripDot(ServerConfig.MAIL_DOMAIN.toLowerCase(Locale.ROOT));
        }

        private boolean ok(String reply) throws IOException {
            c.reply(reply);
            return true;
        }

        /** Lỗi cú pháp/thứ tự lệnh: đếm, quá nhiều thì ngắt (chống rác/dò lệnh). */
        private boolean bad(String reply) throws IOException {
            if (++errors > MAX_PROTOCOL_ERRORS) {
                c.reply("421 4.7.0 Too many errors, closing connection");
                return false;
            }
            c.reply(reply);
            return true;
        }

        private boolean tlsAvailable() {
            if (tlsAvail == null) tlsAvail = c.tlsAvailable();
            return tlsAvail;
        }

        private void resetTransaction() {
            haveMail = false;
            mailFrom = "";
            rcpts.clear();
        }

        // ------------------------------------------------------------ lệnh

        private boolean command(String line) throws IOException {
            String t = line.trim();
            if (t.isEmpty()) return bad("500 5.5.2 Syntax error, command empty");
            int sp = t.indexOf(' ');
            String verb = (sp < 0 ? t : t.substring(0, sp)).toUpperCase(Locale.ROOT);
            String arg = sp < 0 ? "" : t.substring(sp + 1).trim();
            switch (verb) {
                case "HELO": return helo(arg, false);
                case "EHLO": return helo(arg, true);
                case "MAIL": return mail(arg);
                case "RCPT": return rcpt(arg);
                case "DATA": return data(arg);
                case "RSET":
                    resetTransaction();
                    return ok("250 2.0.0 Ok");
                case "NOOP": return ok("250 2.0.0 Ok");
                case "QUIT":
                    c.reply("221 2.0.0 Bye");
                    return false;
                case "VRFY": return ok("252 2.5.2 Cannot VRFY user, but will accept message and attempt delivery");
                case "STARTTLS": return startTls(arg);
                case "AUTH": return bad("502 5.5.1 AUTH not available on this port");
                default: return bad("500 5.5.1 Command unrecognized");
            }
        }

        private boolean helo(String arg, boolean ehlo) throws IOException {
            if (arg.isEmpty()) return bad("501 5.5.4 " + (ehlo ? "EHLO" : "HELO") + " requires domain name");
            String token = arg.split("\\s+")[0];
            heloName = token.matches("[A-Za-z0-9._:\\[\\]-]{1,255}") ? token : "unknown";
            resetTransaction();
            if (!ehlo) {
                return ok("250 " + host());
            }
            List<String> lines = new ArrayList<>();
            lines.add(host() + " Hello " + ipLiteral(ip));
            lines.add("SIZE " + maxMessageBytes);
            lines.add("8BITMIME");
            lines.add("ENHANCEDSTATUSCODES");
            lines.add("PIPELINING");
            // Chỉ quảng cáo STARTTLS khi chưa TLS VÀ máy chủ có chứng chỉ (nếu không sẽ phải trả 454).
            if (!c.isTls() && tlsAvailable()) lines.add("STARTTLS");
            c.replyMulti(250, lines); // KHÔNG quảng cáo AUTH
            return true;
        }

        private boolean mail(String arg) throws IOException {
            if (heloName == null) return bad("503 5.5.1 Send HELO/EHLO first");
            if (haveMail) return bad("503 5.5.1 Nested MAIL command");
            if (!arg.regionMatches(true, 0, "FROM:", 0, 5)) {
                return bad("501 5.5.4 Syntax: MAIL FROM:<address> [SIZE=n] [BODY=8BITMIME]");
            }
            Path p = parsePath(arg.substring(5));
            if (p == null || !validAddress(p.addr)) return bad("501 5.1.7 Bad sender address syntax");

            long size = -1;
            if (!p.params.isEmpty()) {
                for (String param : p.params.split("\\s+")) {
                    int eq = param.indexOf('=');
                    String key = (eq < 0 ? param : param.substring(0, eq)).toUpperCase(Locale.ROOT);
                    String val = eq < 0 ? "" : param.substring(eq + 1);
                    switch (key) {
                        case "SIZE":
                            try {
                                size = Long.parseLong(val);
                            } catch (NumberFormatException e) {
                                return bad("501 5.5.4 Invalid SIZE parameter");
                            }
                            if (size < 0) return bad("501 5.5.4 Invalid SIZE parameter");
                            break;
                        case "BODY":
                            if (!val.equalsIgnoreCase("8BITMIME") && !val.equalsIgnoreCase("7BIT")) {
                                return bad("501 5.5.4 Invalid BODY parameter");
                            }
                            break; // thư giữ nguyên byte nên 7BIT/8BITMIME như nhau
                        case "AUTH":
                            break; // AUTH=<> do MTA khác gửi kèm: bỏ qua
                        default:
                            return bad("555 5.5.4 Unsupported MAIL parameter");
                    }
                }
            }
            if (size > maxMessageBytes) {
                return ok("552 5.3.4 Message size exceeds fixed limit");
            }
            haveMail = true;
            mailFrom = p.addr;
            rcpts.clear();
            return ok("250 2.1.0 Ok");
        }

        private boolean rcpt(String arg) throws IOException {
            if (!haveMail) return bad("503 5.5.1 Need MAIL command first");
            if (!arg.regionMatches(true, 0, "TO:", 0, 3)) return bad("501 5.5.4 Syntax: RCPT TO:<address>");
            Path p = parsePath(arg.substring(3));
            if (p == null || p.addr.isEmpty() || !validAddress(p.addr)) {
                return bad("501 5.1.3 Bad recipient address syntax");
            }
            if (!p.params.isEmpty()) return bad("555 5.5.4 RCPT parameters not supported");

            String addr = p.addr.toLowerCase(Locale.ROOT);
            int at = addr.lastIndexOf('@');
            String local;
            if (at < 0) {
                if (!addr.equals("postmaster")) return bad("501 5.1.3 Recipient address must include domain");
                local = "postmaster";
            } else {
                local = addr.substring(0, at);
                String dom = stripDot(addr.substring(at + 1));
                if (local.isEmpty() || dom.isEmpty()) return bad("501 5.1.3 Bad recipient address syntax");
                if (!dom.equals(domain())) {
                    // Không phải domain của ta: TUYỆT ĐỐI không relay.
                    return ok("554 5.7.1 Relay access denied");
                }
            }

            if (local.equals("postmaster")) {
                // RFC 5321: luôn chấp nhận postmaster. Không gọi recordGoodRcpt vì địa chỉ này công khai,
                // kẻ dò tài khoản có thể xen kẽ nó để xóa bộ đếm RCPT lỗi.
                String pm = "postmaster@" + domain();
                String target = users.resolve(pm);
                if (target == null) target = users.resolve("admin@" + domain());
                if (target == null) target = pm; // chưa có hòm thư: hoãn (451) lúc giao, không bỏ thư
                return addRecipient(target, false);
            }

            String target = users.resolve(local + "@" + domain());
            if (target == null) {
                if (!guard.recordBadRcpt(ip)) {
                    c.reply("421 4.7.0 Too many invalid recipients, closing connection");
                    return false; // IP vừa bị cấm tạm: ngắt phiên
                }
                return ok("550 5.1.1 No such local user");
            }
            return addRecipient(target, true);
        }

        private boolean addRecipient(String target, boolean realUser) throws IOException {
            if (!rcpts.contains(target) && rcpts.size() >= maxRecipients) {
                return ok("452 4.5.3 Too many recipients"); // tạm: MTA gửi sẽ chuyển phần còn lại sang giao dịch khác
            }
            rcpts.add(target);
            if (realUser) guard.recordGoodRcpt(ip);
            return ok("250 2.1.5 Ok");
        }

        private boolean data(String arg) throws IOException {
            if (!arg.isEmpty()) return bad("501 5.5.4 DATA takes no parameters");
            if (!haveMail) return bad("503 5.5.1 Need MAIL command first");
            if (rcpts.isEmpty()) return bad("554 5.5.1 No valid recipients");
            if (!guard.allowMessage(ip)) {
                resetTransaction();
                return ok("450 4.7.1 Too many messages from your IP, try again later");
            }
            c.reply("354 Start mail input; end with <CRLF>.<CRLF>");

                        byte[] raw;
            try {
                raw = c.readData(maxMessageBytes);
            } catch (DataLineTooLongException e) {
                resetTransaction();
                return ok("500 5.5.2 Line too long in message");
            } catch (DataTooLargeException e) {
                resetTransaction();
                return ok("552 5.3.4 Message size exceeds fixed limit");
            }
            try {
                return process(raw);
            } finally {
                resetTransaction();
            }
        }

        private boolean process(byte[] raw) throws IOException {
            if (countReceived(raw) > MAX_RECEIVED_HOPS) {
                return ok("554 5.4.6 Too many hops; mail loop detected");
            }
            String id = newId();
            String single = rcpts.size() == 1 ? rcpts.iterator().next() : null;
            byte[] full = concat(buildReceived(heloName, ip, c.isTls(), id, single), raw);

            InboundVerdict verdict;
            try {
                verdict = authenticator.evaluate(new InboundContext(ip, heloName, mailFrom, c.isTls()), full);
            } catch (RuntimeException e) {
                System.err.println("[MX] Lỗi InboundAuthenticator (client " + ip + "): " + e);
                return ok("451 4.3.0 Temporary local problem, try again later");
            }
            if (verdict == null || verdict.action() == null) {
                return ok("451 4.3.0 Temporary local problem, try again later");
            }

            byte[] payload = verdict.rawWithHeaders() != null ? verdict.rawWithHeaders() : full;
            switch (verdict.action()) {
                case ACCEPT:
                    return deliverAll(payload, Folder.INBOX, id);
                case SPAM:
                    return deliverAll(payload, Folder.SPAM, id);
                case REJECT: {
                    int code = verdict.smtpCode();
                    if (code < 400 || code > 599) code = 550;
                    String msg = verdict.smtpMessage();
                    if (msg == null || msg.trim().isEmpty()) msg = "5.7.1 Message rejected";
                    return ok(code + " " + msg);
                }
                case TEMPFAIL:
                default:
                    return ok("451 4.7.1 Temporarily unable to accept message, try again later");
            }
        }

        private boolean deliverAll(byte[] payload, Folder folder, String id) throws IOException {
            int okCount = 0, quota = 0, temp = 0;
            String pm = "postmaster@" + domain();
            for (String rcpt : rcpts) {
                Outcome o;
                if (rcpt.equals(pm) && users.resolve(pm) == null) {
                    o = Outcome.TEMP; // postmaster chưa có hòm thư để nhận
                } else {
                    o = deliverOne(rcpt, payload, folder);
                }
                switch (o) {
                    case OK: okCount++; break;
                    case QUOTA: quota++; System.err.println("[MX] Quota đầy, bỏ người nhận " + rcpt); break;
                    case TEMP: temp++; System.err.println("[MX] Lỗi tạm khi giao cho " + rcpt); break;
                    default: System.err.println("[MX] Lỗi vĩnh viễn khi giao cho " + rcpt); break;
                }
            }
            if (okCount > 0) {
                return ok("250 2.0.0 Ok: queued as " + id); // xem ghi chú đầu lớp về thất bại một phần
            }
            if (temp > 0) return ok("451 4.3.0 Temporary local problem, try again later");
            if (quota > 0) return ok("552 5.2.2 Mailbox full");
            return ok("554 5.3.0 Delivery failed permanently");
        }

        private Outcome deliverOne(String rcpt, byte[] payload, Folder folder) {
            Set<String> one = new LinkedHashSet<>();
            one.add(rcpt);
            try {
                return classify(delivery.deliver(mailFrom, one, payload, folder));
            } catch (RuntimeException e) {
                System.err.println("[MX] deliverLocal ném lỗi cho " + rcpt + ": " + e);
                return Outcome.TEMP;
            }
        }

        private boolean startTls(String arg) throws IOException {
            if (!arg.isEmpty()) return bad("501 5.5.2 STARTTLS takes no parameters");
            if (c.isTls()) return bad("503 5.5.1 TLS already active");
            if (!tlsAvailable()) return ok("454 4.7.0 TLS not available");
            c.reply("220 2.0.0 Ready to start TLS");
            if (!c.startTls()) return false; // kết nối đã đóng
            heloName = null; // RFC 3207: bỏ mọi trạng thái đã biết trước TLS
            resetTransaction();
            return true;
        }
    }

    // ================================================================== hàm thuần

    /**
     * Phân loại kết quả MailService.deliverLocal. DeliveryResult hiện chỉ có success/message nên phải
     * dựa vào nội dung message. Lỗi không rõ nguyên nhân coi là TẠM (451) để không làm mất thư.
     * (Đã ghi REQUESTS.md đề nghị A9 thêm trường phân loại rõ ràng.)
     */
    static Outcome classify(MailService.DeliveryResult r) {
        if (r == null) return Outcome.TEMP;
        if (r.success) return Outcome.OK;
        String m = r.message == null ? "" : r.message.toLowerCase(Locale.ROOT);
        if (m.contains("quota") || m.contains("mailbox full") || m.contains("over limit")) return Outcome.QUOTA;
        if (m.contains("no such user") || m.contains("unknown user") || m.contains("does not exist")) {
            return Outcome.PERM;
        }
        return Outcome.TEMP;
    }

    private static final class Path {
        final String addr;
        final String params;

        Path(String addr, String params) {
            this.addr = addr;
            this.params = params;
        }
    }

    /** Phân tích "<addr> PARAMS". Trả null nếu sai cú pháp. Chấp nhận "<>" và bỏ source route "@a,@b:". */
    static Path parsePath(String s) {
        s = s.trim();
        if (s.isEmpty() || s.charAt(0) != '<') return null;
        boolean quoted = false;
        int end = -1;
        for (int i = 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quoted) {
                if (ch == '\\') i++;
                else if (ch == '"') quoted = false;
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == '>') {
                end = i;
                break;
            }
        }
        if (end < 0) return null;
        if (end + 1 < s.length() && s.charAt(end + 1) != ' ') return null;
        String addr = s.substring(1, end).trim();
        if (addr.startsWith("@")) {
            int colon = addr.indexOf(':');
            if (colon < 0) return null;
            addr = addr.substring(colon + 1);
        }
        return new Path(addr, s.substring(end + 1).trim());
    }

    private static boolean validAddress(String a) {
        if (a.length() > MAX_PATH_LENGTH) return false;
        for (int i = 0; i < a.length(); i++) {
            char ch = a.charAt(i);
            if (ch < 0x20 || ch == 0x7f) return false;
        }
        return true;
    }

    private static String stripDot(String d) {
        return d.endsWith(".") ? d.substring(0, d.length() - 1) : d;
    }

    private static String ipLiteral(String ip) {
        return ip.indexOf(':') >= 0 ? "[IPv6:" + ip + "]" : "[" + ip + "]";
    }

    private static String newId() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(16);
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    /** Đếm header Received trong phần đầu thư (tới dòng trống đầu tiên). */
    static int countReceived(byte[] raw) {
        final byte[] key = "received:".getBytes(StandardCharsets.US_ASCII);
        int count = 0;
        int pos = 0;
        final int n = raw.length;
        while (pos < n) {
            int eol = pos;
            while (eol < n && raw[eol] != '\n') eol++;
            int lineEnd = eol;
            if (lineEnd > pos && raw[lineEnd - 1] == '\r') lineEnd--;
            if (lineEnd == pos) break; // dòng trống: hết header
            if (lineEnd - pos >= key.length) {
                boolean match = true;
                for (int i = 0; i < key.length; i++) {
                    if (Character.toLowerCase((char) (raw[pos + i] & 0xFF)) != key[i]) {
                        match = false;
                        break;
                    }
                }
                if (match) count++;
            }
            pos = eol + 1;
        }
        return count;
    }

    /** Header Received đặt ở đầu thư. "for" chỉ ghi khi có đúng một người nhận (RFC 5321 mục 4.4). */
    static byte[] buildReceived(String helo, String ip, boolean tls, String id, String singleRcpt) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("Received: from ").append(helo).append(" (").append(ipLiteral(ip)).append(")\r\n");
        sb.append("\tby ").append(ServerConfig.MAIL_HOSTNAME).append(" with ")
                .append(tls ? "ESMTPS" : "ESMTP").append(" id ").append(id);
        if (singleRcpt != null) {
            sb.append("\r\n\tfor <").append(singleRcpt).append(">");
        }
        sb.append(";\r\n\t").append(RECEIVED_DATE.format(ZonedDateTime.now(ZoneOffset.UTC))).append("\r\n");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}