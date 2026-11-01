package com.pbl4.mailserver.outbound;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Dựng thư báo lỗi (DSN, RFC 3464): multipart/report; report-type=delivery-status.
 * Ba phần: (1) text/plain dễ đọc, (2) message/delivery-status, (3) text/rfc822-headers (header thư gốc).
 * Toàn bộ xuống dòng là CRLF; header thư gốc được chép NGUYÊN byte, không qua String.
 */
public final class BounceBuilder {

    private BounceBuilder() {}

    /** Một người nhận không giao được. expired=true: bị bỏ vì quá thời hạn lưu hàng đợi. */
    public record Failure(String rcpt, String diagnostic, boolean expired) {
        public Failure {
            if (rcpt == null) rcpt = "";
            if (diagnostic == null) diagnostic = "";
        }
    }

    private static final Pattern ENHANCED_5 = Pattern.compile("(?<![\\d.])5\\.\\d{1,3}\\.\\d{1,3}(?![\\d.])");
    private static final Pattern SMTP_REPLY = Pattern.compile("^[2-5]\\d\\d([ -].*)?$", Pattern.DOTALL);
    private static final int MAX_ORIG_HEADER_BYTES = 32 * 1024;
    private static final int MAX_DIAG_CHARS = 900;

    /** Không bao giờ bounce cho null sender hoặc MAILER-DAEMON (tránh vòng lặp bounce). */
    public static boolean shouldBounce(String envelopeFrom) {
        String s = stripAngle(envelopeFrom);
        if (s.isEmpty()) return false;
        int at = s.lastIndexOf('@');
        String local = at >= 0 ? s.substring(0, at) : s;
        return !local.trim().equalsIgnoreCase("MAILER-DAEMON");
    }

    /**
     * @param mailDomain   domain của hệ thống (From = MAILER-DAEMON@mailDomain)
     * @param reportingHost FQDN ghi vào Reporting-MTA
     * @param envelopeFrom người gửi gốc (nơi nhận DSN)
     * @param destDomain   domain đích đã thất bại
     * @param failures     các người nhận thất bại (không rỗng)
     * @param origRaw      thư gốc (chỉ dùng phần header)
     * @param nowMs        thời điểm hiện tại (epoch ms)
     */
    public static byte[] build(String mailDomain, String reportingHost, String envelopeFrom, String destDomain,
                               List<Failure> failures, byte[] origRaw, long nowMs) {
        String sender = stripAngle(envelopeFrom);
        String boundary = "=_bounce_" + UUID.randomUUID().toString().replace("-", "");
        String date = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault())
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        boolean anyExpired = failures.stream().anyMatch(Failure::expired);

        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);

        // ---- Header thư ----
        w(out, "From: Mail Delivery System <MAILER-DAEMON@" + mailDomain + ">\r\n");
        w(out, "To: <" + sender + ">\r\n");
        w(out, "Subject: Mail Delivery Failure: Undelivered Mail Returned to Sender\r\n");
        w(out, "Date: " + date + "\r\n");
        w(out, "Message-ID: <" + UUID.randomUUID() + "@" + mailDomain + ">\r\n");
        w(out, "Auto-Submitted: auto-replied\r\n");
        w(out, "MIME-Version: 1.0\r\n");
        w(out, "Content-Type: multipart/report; report-type=delivery-status; boundary=\"" + boundary + "\"\r\n");
        w(out, "\r\n");

        // ---- Phần 1: dễ đọc ----
        w(out, "--" + boundary + "\r\n");
        w(out, "Content-Type: text/plain; charset=UTF-8\r\n");
        w(out, "Content-Transfer-Encoding: 8bit\r\n\r\n");
        StringBuilder human = new StringBuilder();
        human.append("Đây là thư tự động của hệ thống thư ").append(mailDomain).append(". Xin đừng trả lời thư này.\r\n\r\n");
        human.append("Thư của bạn KHÔNG gửi được tới các người nhận sau (máy chủ đích: ").append(destDomain).append("):\r\n\r\n");
        for (Failure f : failures) {
            human.append("  ").append(f.rcpt()).append("\r\n");
            String d = f.diagnostic().isBlank() ? "(không có chẩn đoán)" : f.diagnostic();
            for (String ln : d.split("\\r?\\n")) {
                human.append("      ").append(ln).append("\r\n");
            }
            if (f.expired()) {
                human.append("      Hệ thống đã thử lại nhiều lần nhưng quá thời hạn lưu hàng đợi nên ngừng thử.\r\n");
            }
            human.append("\r\n");
        }
        if (anyExpired) {
            human.append("Lưu ý: lỗi tạm thời kéo dài quá thời hạn nên thư bị trả lại.\r\n\r\n");
        }
        human.append("Phần đầu (header) của thư gốc được đính kèm bên dưới.\r\n");
        w(out, human.toString());

        // ---- Phần 2: message/delivery-status ----
        w(out, "--" + boundary + "\r\n");
        w(out, "Content-Type: message/delivery-status\r\n\r\n");
        w(out, "Reporting-MTA: dns; " + ascii(reportingHost) + "\r\n");
        w(out, "Arrival-Date: " + date + "\r\n");
        for (Failure f : failures) {
            w(out, "\r\n");
            w(out, "Final-Recipient: rfc822; " + ascii(f.rcpt()) + "\r\n");
            w(out, "Action: failed\r\n");
            w(out, "Status: " + statusOf(f) + "\r\n");
            w(out, "Remote-MTA: dns; " + ascii(destDomain) + "\r\n");
            w(out, "Diagnostic-Code: " + diagnosticCode(f.diagnostic()) + "\r\n");
            w(out, "Last-Attempt-Date: " + date + "\r\n");
        }
        w(out, "\r\n");

        // ---- Phần 3: header thư gốc (nguyên byte) ----
        w(out, "--" + boundary + "\r\n");
        w(out, "Content-Type: text/rfc822-headers\r\n\r\n");
        byte[] hdr = originalHeaders(origRaw);
        out.write(hdr, 0, hdr.length);
        if (hdr.length == 0 || hdr[hdr.length - 1] != '\n') w(out, "\r\n");
        w(out, "--" + boundary + "--\r\n");

        return out.toByteArray();
    }

    // ------------------------------------------------------------------ nội bộ

    static String statusOf(Failure f) {
        if (f.expired()) return "4.4.7"; // delivery time expired
        var m = ENHANCED_5.matcher(f.diagnostic());
        if (m.find()) return m.group();
        return "5.0.0";
    }

    static String diagnosticCode(String diag) {
        String one = ascii(diag.replaceAll("[\\r\\n]+", " ").trim());
        if (one.length() > MAX_DIAG_CHARS) one = one.substring(0, MAX_DIAG_CHARS);
        if (one.isEmpty()) one = "no diagnostic";
        // Phản hồi SMTP thật bắt đầu bằng mã 3 chữ số; còn lại là lỗi nội bộ (mạng, DNS...).
        return (SMTP_REPLY.matcher(one).matches() ? "smtp; " : "X-Internal; ") + one;
    }

    /** Header DSN phải là ASCII: ký tự ngoài ASCII/điều khiển thành '?'. */
    private static String ascii(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append((c >= 0x20 && c < 0x7f) ? c : '?');
        }
        return sb.toString();
    }

    private static byte[] originalHeaders(byte[] raw) {
        if (raw == null) return new byte[0];
        int end = -1;
        for (int i = 0; i + 1 < raw.length; i++) {
            if (raw[i] == '\n' && raw[i + 1] == '\n') { end = i + 1; break; }
            if (raw[i] == '\n' && i + 2 < raw.length && raw[i + 1] == '\r' && raw[i + 2] == '\n') { end = i + 1; break; }
        }
        if (end < 0) end = raw.length;
        end = Math.min(end, MAX_ORIG_HEADER_BYTES);
        return Arrays.copyOf(raw, end);
    }

    private static String stripAngle(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("<") && t.endsWith(">")) t = t.substring(1, t.length() - 1).trim();
        return t;
    }

    private static void w(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.write(b, 0, b.length);
    }
}