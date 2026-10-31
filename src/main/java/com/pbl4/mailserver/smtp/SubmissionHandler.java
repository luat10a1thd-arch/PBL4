package com.pbl4.mailserver.smtp;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.LoginThrottle;
import com.pbl4.mailserver.core.MailService;
import com.pbl4.mailserver.core.SecurityUtils;
import com.pbl4.mailserver.core.UserStore;
import com.pbl4.mailserver.ratelimit.AbuseGuard;
import com.pbl4.mailserver.smtp.wire.DataLineTooLongException;
import com.pbl4.mailserver.smtp.wire.DataTooLargeException;
import com.pbl4.mailserver.smtp.wire.SmtpConnection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phiên SMTP "submission" (cổng 587 STARTTLS, 465 TLS ngay, 2525 dev): người dùng đã đăng nhập gửi thư đi.
 * Mỗi phiên dùng một instance mới (SmtpListener gọi factory.get()), nên trạng thái để ở field.
 *
 * Không nhận thư từ ngoài (việc của MxHandler), không tự giao/bounce (việc của MailService/OutboundQueue).
 */
public class SubmissionHandler implements SmtpListener.SessionHandler {

    private static final int MAX_AUTH_FAILS_PER_SESSION = 5;
    private static final int MAX_BAD_COMMANDS = 20;
    private static final int MAX_USER_LEN = 320;
    private static final int MAX_PASS_LEN = 1024;
    private static final int MAX_ADDR_LEN = 254;

    /** Hash giả để vẫn tốn một lần bcrypt khi user không tồn tại (chống dò tài khoản qua thời gian phản hồi). */
    private static final String DUMMY_HASH = SecurityUtils.hashPassword("pbl4-dummy-password");

    private static final Pattern MAIL_FROM = Pattern.compile("^FROM\\s*:(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RCPT_TO = Pattern.compile("^TO\\s*:(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTERNAL_ADDR =
            Pattern.compile("^[^@\\s<>\"(),;:\\[\\]\\\\]+@[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$");

    private final boolean requireTls;

    // ---- trạng thái phiên ----
    private String authUser = null;          // tài khoản canonical sau AUTH
    private String mailFrom = null;          // null = chưa có giao dịch
    private final Set<String> rcpts = new LinkedHashSet<>();
    private int authFails = 0;
    private int badCommands = 0;
    private Boolean tlsAvail = null;

    public SubmissionHandler() {
        this(ServerConfig.SMTP_REQUIRE_TLS);
    }

    /** Dùng cho test: ép chế độ bắt buộc TLS bất kể cấu hình. */
    public SubmissionHandler(boolean requireTls) {
        this.requireTls = requireTls;
    }

    // ------------------------------------------------------------------ vòng lệnh

    @Override
    public void handle(SmtpConnection c) throws Exception {
        c.reply("220 " + ServerConfig.MAIL_HOSTNAME + " ESMTP PBL4 Mail Submission");
        String line;
        while ((line = c.readCommand()) != null) {
            String t = line.trim();
            if (t.isEmpty()) {
                if (!badCommand(c)) return;
                continue;
            }
            int sp = t.indexOf(' ');
            String verb = (sp < 0 ? t : t.substring(0, sp)).toUpperCase(Locale.ROOT);
            String args = sp < 0 ? "" : t.substring(sp + 1).trim();

            boolean cont;
            switch (verb) {
                case "EHLO":
                    cont = hello(c, args, true);
                    break;
                case "HELO":
                    cont = hello(c, args, false);
                    break;
                case "STARTTLS":
                    cont = starttls(c, args);
                    break;
                case "AUTH":
                    cont = auth(c, args);
                    break;
                case "MAIL":
                    cont = mail(c, args);
                    break;
                case "RCPT":
                    cont = rcpt(c, args);
                    break;
                case "DATA":
                    cont = data(c, args);
                    break;
                case "RSET":
                    resetTransaction();
                    c.reply("250 2.0.0 OK");
                    cont = true;
                    break;
                case "NOOP":
                    c.reply("250 2.0.0 OK");
                    cont = true;
                    break;
                case "QUIT":
                    c.reply("221 2.0.0 Bye");
                    cont = false;
                    break;
                case "VRFY":
                case "EXPN":
                case "HELP":
                    c.reply("502 5.5.1 Command not implemented");
                    cont = true;
                    break;
                default:
                    cont = badCommand(c);
            }
            if (!cont) return;
        }
    }

    private boolean badCommand(SmtpConnection c) throws IOException {
        if (++badCommands >= MAX_BAD_COMMANDS) {
            c.reply("421 4.7.0 Too many errors, closing connection");
            return false;
        }
        c.reply("500 5.5.1 Command unrecognized");
        return true;
    }

    private void resetTransaction() {
        mailFrom = null;
        rcpts.clear();
    }

    private boolean tlsAvailable(SmtpConnection c) {
        if (tlsAvail == null) tlsAvail = c.tlsAvailable();
        return tlsAvail;
    }

    /** Được phép AUTH khi: không bắt buộc TLS, hoặc đã có TLS (cổng implicit TLS cũng có isTls() = true). */
    private boolean authAllowed(SmtpConnection c) {
        return !requireTls || c.isTls();
    }

    /** Trả false + đã gửi lời từ chối nếu chưa đăng nhập. */
    private boolean requireAuth(SmtpConnection c) throws IOException {
        if (authUser != null) return true;
        c.reply(authAllowed(c)
                ? "530 5.7.0 Authentication required"
                : "530 5.7.0 Must issue a STARTTLS command first");
        return false;
    }

    // ------------------------------------------------------------------ EHLO / STARTTLS

    private boolean hello(SmtpConnection c, String args, boolean extended) throws IOException {
        if (args.isEmpty()) {
            c.reply("501 5.5.2 Syntax: " + (extended ? "EHLO" : "HELO") + " hostname");
            return true;
        }
        resetTransaction();
        if (!extended) {
            c.reply("250 " + ServerConfig.MAIL_HOSTNAME);
            return true;
        }
        List<String> l = new ArrayList<>();
        l.add(ServerConfig.MAIL_HOSTNAME + " Hello [" + c.remoteIp() + "]");
        l.add("SIZE " + ServerConfig.MAX_MESSAGE_BYTES);
        l.add("8BITMIME");
        l.add("ENHANCEDSTATUSCODES");
        l.add("PIPELINING");
        if (!c.isTls() && tlsAvailable(c)) l.add("STARTTLS");
        if (authUser == null && authAllowed(c)) l.add("AUTH LOGIN PLAIN");
        c.replyMulti(250, l);
        return true;
    }

    private boolean starttls(SmtpConnection c, String args) throws IOException {
        if (!args.isEmpty()) {
            c.reply("501 5.5.2 Syntax error (no parameters allowed)");
            return true;
        }
        if (c.isTls()) {
            c.reply("503 5.5.1 TLS already active");
            return true;
        }
        if (!tlsAvailable(c)) {
            c.reply("454 4.7.0 TLS not available");
            return true;
        }
        c.reply("220 2.0.0 Ready to start TLS");
        if (!c.startTls()) {
            return false; // kết nối đã bị đóng
        }
        // RFC 3207: bỏ mọi trạng thái đã biết trước TLS
        authUser = null;
        resetTransaction();
        return true;
    }

    // ------------------------------------------------------------------ AUTH

    private record AuthOutcome(String user, boolean throttled) {}

    private boolean auth(SmtpConnection c, String args) throws IOException {
        if (authUser != null) {
            c.reply("503 5.5.1 Already authenticated");
            return true;
        }
        if (!authAllowed(c)) {
            c.reply("530 5.7.0 Must issue a STARTTLS command first");
            return true;
        }
        if (mailFrom != null) {
            c.reply("503 5.5.1 AUTH not permitted during a mail transaction");
            return true;
        }
        String[] p = args.split("\\s+", 2);
        String mech = p[0].toUpperCase(Locale.ROOT);
        String initial = p.length > 1 ? p[1].trim() : null;
        if (initial != null && initial.isEmpty()) initial = null;

        String user;
        String pass;

        if (mech.equals("LOGIN")) {
            String uLine = initial != null ? initial : prompt(c, "VXNlcm5hbWU6"); // "Username:"
            if (uLine == null) return false;
            if (uLine.trim().equals("*")) return cancelled(c);
            byte[] ub = decodeB64(uLine.trim());
            if (ub == null) {
                c.reply("501 5.5.4 Cannot decode Base64 username");
                return true;
            }
            String pLine = prompt(c, "UGFzc3dvcmQ6"); // "Password:"
            if (pLine == null) return false;
            if (pLine.trim().equals("*")) return cancelled(c);
            byte[] pb = decodeB64(pLine.trim());
            if (pb == null) {
                c.reply("501 5.5.4 Cannot decode Base64 password");
                return true;
            }
            user = new String(ub, StandardCharsets.UTF_8);
            pass = new String(pb, StandardCharsets.UTF_8);
        } else if (mech.equals("PLAIN")) {
            String line = initial != null ? initial : prompt(c, "");
            if (line == null) return false;
            if (line.trim().equals("*")) return cancelled(c);
            byte[] b = decodeB64(line.trim());
            if (b == null) {
                c.reply("501 5.5.4 Invalid Base64 encoding for AUTH PLAIN");
                return true;
            }
            String[] parts = new String(b, StandardCharsets.UTF_8).split("\0", -1);
            if (parts.length == 3) {
                if (!parts[0].isEmpty() && !parts[0].equalsIgnoreCase(parts[1])) {
                    return authFailed(c); // không hỗ trợ authzid khác authcid
                }
                user = parts[1];
                pass = parts[2];
            } else if (parts.length == 2) {
                user = parts[0];
                pass = parts[1];
            } else {
                c.reply("501 5.5.2 Malformed AUTH PLAIN data");
                return true;
            }
        } else {
            c.reply("504 5.5.4 Unrecognized authentication type");
            return true;
        }

        if (user.length() > MAX_USER_LEN || pass.length() > MAX_PASS_LEN || user.isEmpty()) {
            LoginThrottle.recordFailedAttempt(c.remoteIp(), null); // không dùng user rác làm key
            return authFailed(c);
        }

        AuthOutcome r = authenticate(c.remoteIp(), user, pass);
        if (r.throttled()) {
            c.reply("454 4.7.0 Too many failed attempts, try again later");
            return true;
        }
        if (r.user() == null) return authFailed(c);

        authUser = r.user();
        c.setAuthenticated(true);
        c.reply("235 2.7.0 Authentication successful");
        return true;
    }

    private String prompt(SmtpConnection c, String b64Prompt) throws IOException {
        c.reply("334 " + b64Prompt);
        return c.readRawLine();
    }

    private boolean cancelled(SmtpConnection c) throws IOException {
        c.reply("501 5.0.0 Authentication cancelled");
        return true;
    }

    private boolean authFailed(SmtpConnection c) throws IOException {
        c.reply("535 5.7.8 Authentication credentials invalid");
        if (++authFails >= MAX_AUTH_FAILS_PER_SESSION) {
            c.reply("421 4.7.0 Too many authentication failures");
            return false;
        }
        return true;
    }

    /** Base64 chặt; "=" nghĩa là chuỗi rỗng (RFC 4954). null nếu sai định dạng. */
    private static byte[] decodeB64(String s) {
        if (s.equals("=")) return new byte[0];
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private AuthOutcome authenticate(String ip, String username, String password) {
        if (LoginThrottle.isThrottled(ip, username)) {
            return new AuthOutcome(null, true);
        }
        String hash = UserStore.findHash(username);
        boolean locked = "LOCKED".equalsIgnoreCase(UserStore.getStatus(username));
        // Luôn chạy đúng một lần bcrypt để thời gian không lộ user có tồn tại hay không
        boolean passOk = SecurityUtils.checkPassword(password, hash != null ? hash : DUMMY_HASH);
        String canon = (hash != null && !locked && passOk) ? UserStore.canonical(username) : null;
        if (canon != null) {
            LoginThrottle.resetAttempts(ip, username);
            return new AuthOutcome(canon, false);
        }
        LoginThrottle.recordFailedAttempt(ip, username);
        return new AuthOutcome(null, false);
    }

    // ------------------------------------------------------------------ MAIL / RCPT

    private boolean mail(SmtpConnection c, String args) throws IOException {
        if (!requireAuth(c)) return true;
        Matcher m = MAIL_FROM.matcher(args);
        if (!m.matches()) {
            c.reply("501 5.5.4 Syntax: MAIL FROM:<address>");
            return true;
        }
        if (mailFrom != null) {
            c.reply("503 5.5.1 Nested MAIL command");
            return true;
        }
        String[] path = parsePath(m.group(1));
        if (path == null) {
            c.reply("501 5.1.7 Bad sender address syntax");
            return true;
        }
        // Tham số mở rộng: chỉ xử lý SIZE, bỏ qua BODY/SMTPUTF8...
        for (String param : path[1].split("\\s+")) {
            if (param.toUpperCase(Locale.ROOT).startsWith("SIZE=")) {
                try {
                    long size = Long.parseLong(param.substring(5));
                    if (size > ServerConfig.MAX_MESSAGE_BYTES) {
                        c.reply("552 5.3.4 Message size exceeds fixed limit");
                        return true;
                    }
                } catch (NumberFormatException e) {
                    c.reply("501 5.5.4 Invalid SIZE parameter");
                    return true;
                }
            }
        }
        String addr = path[0];
        String canon = (addr.isEmpty() || addr.length() > MAX_ADDR_LEN) ? null : UserStore.canonical(addr);
        if (canon == null || !canon.equalsIgnoreCase(authUser)) {
            c.reply("553 5.7.1 Sender address must match authenticated account");
            return true;
        }
        mailFrom = canon;
        rcpts.clear();
        c.reply("250 2.1.0 OK");
        return true;
    }

    private boolean rcpt(SmtpConnection c, String args) throws IOException {
        if (!requireAuth(c)) return true;
        Matcher m = RCPT_TO.matcher(args);
        if (!m.matches()) {
            c.reply("501 5.5.4 Syntax: RCPT TO:<address>");
            return true;
        }
        if (mailFrom == null) {
            c.reply("503 5.5.1 Need MAIL FROM first");
            return true;
        }
        if (rcpts.size() >= ServerConfig.SMTP_MAX_RECIPIENTS) {
            c.reply("452 4.5.3 Too many recipients");
            return true;
        }
        String[] path = parsePath(m.group(1));
        String cand = path == null ? "" : path[0].toLowerCase(Locale.ROOT);
        if (cand.isEmpty() || cand.length() > MAX_ADDR_LEN) {
            c.reply("501 5.1.3 Bad recipient address format");
            return true;
        }

        if (isLocalDomain(cand)) {
            String canon = UserStore.canonical(cand);
            if (canon == null || !UserStore.exists(canon)) {
                c.reply("550 5.1.1 No such local user");
                if (!AbuseGuard.recordBadRcpt(c.remoteIp())) {
                    c.reply("421 4.7.0 Too many invalid recipients, closing connection");
                    return false;
                }
                return true;
            }
            rcpts.add(canon);
        } else {
            if (!EXTERNAL_ADDR.matcher(cand).matches() || cand.indexOf('.', cand.indexOf('@')) < 0) {
                c.reply("501 5.1.3 Bad recipient address format");
                return true;
            }
            rcpts.add(cand);
        }
        c.reply("250 2.1.5 OK");
        return true;
    }

    private static boolean isLocalDomain(String email) {
        int at = email.indexOf('@');
        if (at < 0) return true;
        String domain = email.substring(at + 1).trim();
        return domain.equalsIgnoreCase(ServerConfig.MAIL_DOMAIN) || domain.equalsIgnoreCase("localhost");
    }

    /**
     * Tách "<addr> params" hoặc "addr params". Trả {addr, params} hoặc null nếu thiếu '>'.
     * "<>" cho addr rỗng. Bỏ source route kiểu "@a,@b:user@x".
     */
    static String[] parsePath(String raw) {
        String s = raw.trim();
        if (s.startsWith("<")) {
            int e = s.indexOf('>');
            if (e < 0) return null;
            String a = s.substring(1, e).trim();
            if (a.startsWith("@")) {
                int colon = a.lastIndexOf(':');
                if (colon > 0) a = a.substring(colon + 1);
            }
            return new String[]{a, s.substring(e + 1).trim()};
        }
        int sp = s.indexOf(' ');
        return sp < 0 ? new String[]{s, ""} : new String[]{s.substring(0, sp), s.substring(sp + 1).trim()};
    }

    // ------------------------------------------------------------------ DATA

    private boolean data(SmtpConnection c, String args) throws IOException {
        if (!requireAuth(c)) return true;
        if (!args.isEmpty()) {
            c.reply("501 5.5.4 Syntax error (no parameters allowed)");
            return true;
        }
        if (mailFrom == null) {
            c.reply("503 5.5.1 Need MAIL FROM first");
            return true;
        }
        if (rcpts.isEmpty()) {
            c.reply("503 5.5.1 Need RCPT first");
            return true;
        }
        if (!AbuseGuard.allowMessage(c.remoteIp())) {
            resetTransaction();
            c.reply("450 4.7.1 Sending rate exceeded, try again later");
            return true;
        }

        c.reply("354 Start mail input; end with <CRLF>.<CRLF>");
        byte[] raw;
        try {
            raw = c.readData(ServerConfig.MAX_MESSAGE_BYTES);
        } catch (DataLineTooLongException e) {
            resetTransaction();
            c.reply("500 5.5.2 Line too long in message");
            return true;
        } catch (DataTooLargeException e) {
            resetTransaction();
            c.reply("552 5.3.4 Message size exceeds fixed limit");
            return true;
        }
        // IOException khác (EOF, nuốt quá nhiều) lan ra ngoài: SmtpListener đóng kết nối.

        String from = mailFrom;
        Set<String> to = new LinkedHashSet<>(rcpts);
        resetTransaction();

        MailService.DeliveryResult r;
        try {
            r = MailService.submit(authUser, from, to, raw);
        } catch (Exception e) {
            System.err.println("[SUBMISSION] MailService.submit lỗi: " + e);
            c.reply("451 4.3.0 Temporary local problem, try again later");
            return true;
        }
        if (r.success) {
            c.reply("250 2.0.0 OK: message accepted for delivery");
        } else if (isPermanent(r.message)) {
            c.reply("554 5.7.0 Message rejected: " + r.message);
        } else {
            c.reply("451 4.3.0 " + (r.message == null ? "Temporary failure, try again later" : r.message));
        }
        return true;
    }

    /**
     * Lỗi vĩnh viễn nếu message do MailService bắt đầu bằng mã 5xx ("554 ...", "5.1.1 ...").
     * Mọi trường hợp khác (hàng đợi đầy, lỗi đĩa...) coi là tạm thời -> 451 để client gửi lại.
     * Xem REQUESTS.md: nên có cờ rõ ràng thay vì đoán theo chuỗi.
     */
    static boolean isPermanent(String message) {
        if (message == null) return false;
        return message.trim().matches("^5(\\.\\d+\\.\\d+|\\d\\d)\\b.*");
    }
}