package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dựng thư chuẩn RFC 5322 / MIME để gửi ra Internet từ thư đã lưu trong hộp thư.
 * - Thư tạo từ web (text + [ATTACHMENT:...]): dựng lại thành text/plain hoặc multipart/mixed.
 * - Thư có sẵn MIME (ví dụ từ Thunderbird): giữ nguyên nội dung, chỉ ép lại From và bổ sung header thiếu.
 * Luôn đảm bảo có Date, Message-ID, MIME-Version; tiêu đề tiếng Việt được mã hoá theo RFC 2047.
 */
public final class MimeBuilder {

    private static final Pattern ATTACHMENT = Pattern.compile(
            "\\[ATTACHMENT:([^\\]\\r\\n]+)\\](?:data:([^;]+);base64,)?([A-Za-z0-9+/=\\s]+)\\[/ATTACHMENT\\]");
    private static final Pattern MIME_TYPE = Pattern.compile("^[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+$");

    private MimeBuilder() {}

    public static String build(String fromAddress, String replyTo, Collection<String> rcptsFallback, String rawStored) {
        String normalized = rawStored.replace("\r\n", "\n").replace('\r', '\n');
        int split = normalized.indexOf("\n\n");
        String headerPart = split >= 0 ? normalized.substring(0, split) : normalized;
        String fullBody = split >= 0 ? normalized.substring(split + 2) : "";

        String contentType = headerValue(headerPart, "content-type").toLowerCase(Locale.ROOT);
        boolean hasCte = !headerValue(headerPart, "content-transfer-encoding").isEmpty();
        if (contentType.startsWith("multipart/") || hasCte) {
            return passThrough(fromAddress, replyTo, rcptsFallback, headerPart, fullBody);
        }
        return rebuild(fromAddress, replyTo, rcptsFallback, headerPart, fullBody);
    }

    private static String rebuild(String fromAddress, String replyTo, Collection<String> rcpts,
                                  String headerPart, String fullBody) {
        String subject = headerValue(headerPart, "subject");
        String to = headerValue(headerPart, "to");
        String date = headerValue(headerPart, "date");
        if (subject.isEmpty()) subject = "(Không có tiêu đề)";
        if (to.isEmpty()) to = String.join(", ", rcpts);
        if (date.isEmpty()) date = nowRfc822();

        String textPart = fullBody;
        List<String[]> attachments = new ArrayList<>();
        int idx = fullBody.indexOf("[ATTACHMENT:");
        if (idx >= 0) {
            textPart = fullBody.substring(0, idx).trim();
            Matcher m = ATTACHMENT.matcher(fullBody);
            while (m.find()) {
                String name = sanitizeFileName(m.group(1));
                String mime = m.group(2) == null ? "" : m.group(2).trim();
                if (!MIME_TYPE.matcher(mime).matches()) mime = "application/octet-stream";
                String b64 = m.group(3).replaceAll("\\s+", "");
                attachments.add(new String[]{name, mime, b64});
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("From: ").append(fromAddress).append("\r\n");
        if (replyTo != null && !replyTo.isEmpty()) sb.append("Reply-To: ").append(replyTo).append("\r\n");
        sb.append("To: ").append(to).append("\r\n");
        sb.append("Subject: ").append(encodeHeaderText(subject)).append("\r\n");
        sb.append("Date: ").append(date).append("\r\n");
        sb.append("Message-ID: ").append(newMessageId()).append("\r\n");
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("X-Mailer: DUT Secure Mail Server\r\n");

        if (attachments.isEmpty()) {
            sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
            sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
            sb.append(encodeText(textPart)).append("\r\n");
        } else {
            String boundary = "=_PBL4_" + UUID.randomUUID().toString().replace("-", "");
            sb.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n\r\n");

            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
            sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
            sb.append(encodeText(textPart)).append("\r\n");

            for (String[] att : attachments) {
                String name = att[0];
                String asciiName = name.replaceAll("[^\\x20-\\x7E]", "_");
                String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
                sb.append("--").append(boundary).append("\r\n");
                sb.append("Content-Type: ").append(att[1]).append("; name=\"").append(asciiName).append("\"\r\n");
                sb.append("Content-Transfer-Encoding: base64\r\n");
                sb.append("Content-Disposition: attachment; filename=\"").append(asciiName)
                  .append("\"; filename*=UTF-8''").append(encodedName).append("\r\n\r\n");
                for (int i = 0; i < att[2].length(); i += 76) {
                    sb.append(att[2], i, Math.min(i + 76, att[2].length())).append("\r\n");
                }
            }
            sb.append("--").append(boundary).append("--\r\n");
        }
        return sb.toString();
    }

    private static String passThrough(String fromAddress, String replyTo, Collection<String> rcpts,
                                      String headerPart, String fullBody) {
        Set<String> drop = (replyTo != null && !replyTo.isEmpty())
                ? Set.of("from", "sender", "return-path", "bcc", "reply-to")
                : Set.of("from", "sender", "return-path", "bcc");
        List<String> kept = filterHeaders(headerPart, drop);

        StringBuilder sb = new StringBuilder();
        sb.append("From: ").append(fromAddress).append("\r\n");
        if (replyTo != null && !replyTo.isEmpty()) sb.append("Reply-To: ").append(replyTo).append("\r\n");
        for (String line : kept) sb.append(line).append("\r\n");
        if (!hasHeader(kept, "to")) sb.append("To: ").append(String.join(", ", rcpts)).append("\r\n");
        if (!hasHeader(kept, "date")) sb.append("Date: ").append(nowRfc822()).append("\r\n");
        if (!hasHeader(kept, "message-id")) sb.append("Message-ID: ").append(newMessageId()).append("\r\n");
        if (!hasHeader(kept, "mime-version")) sb.append("MIME-Version: 1.0\r\n");
        sb.append("\r\n");
        sb.append(fullBody.replace("\n", "\r\n"));
        return sb.toString();
    }

    // ---------- tiện ích ----------

    static String encodeHeaderText(String s) {
        boolean ascii = true;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 126) {
                ascii = false;
                break;
            }
        }
        if (ascii) return s;

        // Chia thành nhiều encoded-word, mỗi từ tối đa 45 byte UTF-8 (base64 ~ 60 ký tự, dưới giới hạn 75 của RFC 2047)
        List<String> words = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curBytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int len = ch.getBytes(StandardCharsets.UTF_8).length;
            if (curBytes + len > 45) {
                words.add(cur.toString());
                cur.setLength(0);
                curBytes = 0;
            }
            cur.append(ch);
            curBytes += len;
            i += Character.charCount(cp);
        }
        if (cur.length() > 0) words.add(cur.toString());

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) out.append("\r\n ");
            out.append("=?UTF-8?B?")
               .append(Base64.getEncoder().encodeToString(words.get(i).getBytes(StandardCharsets.UTF_8)))
               .append("?=");
        }
        return out.toString();
    }

    private static String encodeText(String text) {
        String canonical = text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
        return Base64.getMimeEncoder(76, new byte[]{'\r', '\n'})
                .encodeToString(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String sanitizeFileName(String name) {
        String clean = name.replace("\"", "").replace("\\", "").replace("/", "").replace("\r", "").replace("\n", "").trim();
        return clean.isEmpty() ? "attachment" : clean;
    }

    private static String nowRfc822() {
        return new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH).format(new Date());
    }

    private static String newMessageId() {
    	return "<" + UUID.randomUUID() + "@" + ServerConfig.MAIL_DOMAIN + ">";
    }

    private static String headerValue(String headerPart, String name) {
        String prefix = name.toLowerCase(Locale.ROOT) + ":";
        String[] lines = headerPart.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].toLowerCase(Locale.ROOT).startsWith(prefix)) {
                StringBuilder v = new StringBuilder(lines[i].substring(prefix.length()).trim());
                while (i + 1 < lines.length && (lines[i + 1].startsWith(" ") || lines[i + 1].startsWith("\t"))) {
                    i++;
                    v.append(' ').append(lines[i].trim());
                }
                return v.toString();
            }
        }
        return "";
    }

    private static List<String> filterHeaders(String headerPart, Set<String> drop) {
        List<String> kept = new ArrayList<>();
        boolean skipping = false;
        for (String line : headerPart.split("\n")) {
            if (line.isEmpty()) continue;
            if (line.startsWith(" ") || line.startsWith("\t")) {
                if (!skipping) kept.add(line);
                continue;
            }
            int colon = line.indexOf(':');
            String name = colon > 0 ? line.substring(0, colon).trim().toLowerCase(Locale.ROOT) : "";
            skipping = drop.contains(name);
            if (!skipping) kept.add(line);
        }
        return kept;
    }

    private static boolean hasHeader(List<String> lines, String name) {
        String prefix = name + ":";
        for (String l : lines) {
            if (l.toLowerCase(Locale.ROOT).startsWith(prefix)) return true;
        }
        return false;
    }
}
