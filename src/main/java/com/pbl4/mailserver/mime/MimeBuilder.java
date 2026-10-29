package com.pbl4.mailserver.mime;

import com.pbl4.mailserver.config.ServerConfig;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dựng thư RFC 5322 (byte[], CRLF) từ dữ liệu form web. Hàm tĩnh thuần túy.
 *
 * <h3>Quyết định thiết kế</h3>
 * <ul>
 *   <li><b>Content-Transfer-Encoding của phần chữ: luôn {@code quoted-printable}</b> (UTF-8), cho mọi nội dung.
 *       Lý do: kết quả là ASCII 7-bit thuần nên đi qua mọi MX (kể cả MX không có 8BITMIME), dòng nào cũng
 *       &le; 76 ký tự nên không bao giờ vượt giới hạn 998 byte. Dấu '.' ở đầu dòng được mã hóa thành {@code =2E}
 *       (và "From " đầu dòng thành {@code =46rom }) nên thân thư không có dòng nào chỉ gồm một dấu chấm, dù tầng SMTP
 *       có quên dot-stuffing. Việc dot-stuffing vẫn thuộc tầng SMTP.</li>
 *   <li>Đính kèm: base64 chia dòng 76 ký tự. Base64 được kiểm tra ký tự/độ dài/padding; sai thì bỏ đính kèm đó
 *       và ghi chú vào phần chữ.</li>
 *   <li>Header From/To/Subject bị loại mọi CR/LF/ký tự điều khiển (chặn header injection).</li>
 * </ul>
 *
 * <p>Tách tag {@code [ATTACHMENT:tên]base64[/ATTACHMENT]} đọc trực tiếp từ chuỗi gốc bằng chỉ số,
 * không tạo bản sao chuỗi lớn; mỗi đính kèm chỉ có một mảng byte base64 đã làm sạch.
 */
public final class MimeBuilder {

    private static final String OPEN = "[ATTACHMENT:";
    private static final String CLOSE = "[/ATTACHMENT]";
    private static final int MAX_FILENAME_BYTES = 150;
    private static final int HEADER_FOLD = 78;

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("zip", "application/zip"),
            Map.entry("gz", "application/gzip"),
            Map.entry("7z", "application/x-7z-compressed"),
            Map.entry("rar", "application/vnd.rar"),
            Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"),
            Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("mp4", "video/mp4"));

    private MimeBuilder() {
    }

    /**
     * @param body nội dung form; có thể chứa các tag {@code [ATTACHMENT:tên]base64[/ATTACHMENT]}
     * @return thư hoàn chỉnh (header + thân) dạng byte, CRLF, luôn kết thúc bằng CRLF
     */
    public static byte[] fromWebForm(String from, String to, String subject, String body) {
        return build(from, to, subject, body, ServerConfig.MAIL_HOSTNAME);
    }

    /** Bản có hostname để test không phụ thuộc biến môi trường. */
    static byte[] build(String from, String to, String subject, String body, String hostname) {
        String f = cleanHeader(from);
        String t = cleanHeader(to);
        String s = cleanHeader(subject);

        ParsedBody pb = parseBody(body == null ? "" : body);
        byte[] qp = encodeQuotedPrintable(pb.text());

        ByteArrayOutputStream out = new ByteArrayOutputStream(qp.length + 1024 + totalAttachmentSize(pb));

        writeHeader(out, "From", encodeAddresses(f));
        writeHeader(out, "To", encodeAddresses(t));
        writeSubject(out, s);
        writeHeader(out, "Date", MessageNormalizer.formatDate(ZonedDateTime.now()));
        writeHeader(out, "Message-ID", MessageNormalizer.newMessageId(hostname));
        writeHeader(out, "MIME-Version", "1.0");

        if (pb.attachments().isEmpty()) {
            writeHeader(out, "Content-Type", "text/plain; charset=UTF-8");
            writeHeader(out, "Content-Transfer-Encoding", "quoted-printable");
            writeCrlf(out);
            out.write(qp, 0, qp.length);
            if (qp.length > 0 && !endsWithCrlf(qp)) {
                writeCrlf(out);
            }
            return out.toByteArray();
        }

        // boundary ngẫu nhiên, bảo đảm không xuất hiện trong phần chữ (base64 không chứa '-' nên an toàn)
        String boundary;
        do {
            boundary = "----=_Part_" + UUID.randomUUID().toString().replace("-", "");
        } while (indexOf(qp, ("--" + boundary).getBytes(StandardCharsets.US_ASCII)) >= 0);

        writeHeader(out, "Content-Type", "multipart/mixed; boundary=\"" + boundary + "\"");
        writeCrlf(out);

        writeAscii(out, "--" + boundary + "\r\n");
        writeAscii(out, "Content-Type: text/plain; charset=UTF-8\r\n");
        writeAscii(out, "Content-Transfer-Encoding: quoted-printable\r\n\r\n");
        out.write(qp, 0, qp.length);

        for (Attachment a : pb.attachments()) {
            writeAscii(out, "\r\n--" + boundary + "\r\n");
            writeAttachmentHeaders(out, a.name());
            writeCrlf(out);
            writeWrappedBase64(out, a.base64());
        }
        writeAscii(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    // ------------------------------------------------------------------------------------------
    // Header
    // ------------------------------------------------------------------------------------------

    /** Loại CR/LF (thành khoảng trắng) và ký tự điều khiển khác; chặn header injection. */
    static String cleanHeader(String v) {
        if (v == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\r' && i + 1 < v.length() && v.charAt(i + 1) == '\n') {
                continue; // cặp CRLF chỉ thành một khoảng trắng (do '\n' kế tiếp)
            }
            if (c == '\r' || c == '\n' || c == '\t' || c == '\u0085' || c == '\u2028' || c == '\u2029') {
                sb.append(' ');
            } else if (c < 0x20 || c == 0x7F) {
                // bỏ hẳn ký tự điều khiển
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static void writeHeader(ByteArrayOutputStream out, String name, String value) {
        byte[] line = (value.isEmpty() ? name + ":" : name + ": " + value).getBytes(StandardCharsets.UTF_8);
        MessageNormalizer.writeFolded(out, line, 0, line.length, HEADER_FOLD);
        writeCrlf(out);
    }

    /** Subject: ASCII thì ghi thẳng (gấp tại khoảng trắng); có non-ASCII thì RFC 2047 B-encoding. */
    private static void writeSubject(ByteArrayOutputStream out, String s) {
        if (isAscii(s) && longestToken(s) <= 900) {
            writeHeader(out, "Subject", s);
            return;
        }
        // dòng đầu "Subject: " (9 ký tự) + word <= 76 -> tối đa 39 byte/word; các dòng sau " " + word <= 76 -> 45 byte
        List<String> words = encodeWords(s, 39, 45);
        StringBuilder sb = new StringBuilder("Subject:");
        for (int i = 0; i < words.size(); i++) {
            sb.append(i == 0 ? " " : "\r\n ").append(words.get(i));
        }
        writeAscii(out, sb.append("\r\n").toString());
    }

    /**
     * Mã hóa chuỗi thành các encoded-word {@code =?UTF-8?B?...?=}, mỗi word &le; 75 ký tự,
     * không cắt giữa một ký tự UTF-8 (cắt theo code point).
     */
    static List<String> encodeWords(String s, int firstMaxBytes, int restMaxBytes) {
        List<String> words = new ArrayList<>();
        ByteArrayOutputStream cur = new ByteArrayOutputStream();
        int limit = firstMaxBytes;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            byte[] b = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            if (cur.size() > 0 && cur.size() + b.length > limit) {
                words.add(encodedWord(cur.toByteArray()));
                cur.reset();
                limit = restMaxBytes;
            }
            cur.write(b, 0, b.length);
        }
        if (cur.size() > 0) {
            words.add(encodedWord(cur.toByteArray()));
        }
        return words;
    }

    private static String encodedWord(byte[] utf8) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(utf8) + "?=";
    }

    /**
     * From/To: tách theo dấu phẩy (bỏ qua phẩy trong ngoặc kép/ngoặc nhọn), chuẩn hóa thành "a, b".
     * Tên hiển thị có non-ASCII được mã hóa RFC 2047; địa chỉ non-ASCII (không có chuẩn thay thế
     * nếu không dùng SMTPUTF8) được giữ nguyên.
     */
    static String encodeAddresses(String value) {
        if (value.isEmpty()) {
            return value;
        }
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        int angle = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && inQuote && i + 1 < value.length()) {
                cur.append(c).append(value.charAt(++i));
                continue;
            }
            if (c == '"') {
                inQuote = !inQuote;
            } else if (!inQuote && c == '<') {
                angle++;
            } else if (!inQuote && c == '>' && angle > 0) {
                angle--;
            }
            if (c == ',' && !inQuote && angle == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());

        List<String> result = new ArrayList<>();
        for (String raw : parts) {
            String p = raw.trim();
            if (p.isEmpty()) {
                continue;
            }
            int lt = p.lastIndexOf('<');
            if (!isAscii(p) && lt > 0 && p.endsWith(">")) {
                String name = p.substring(0, lt).trim();
                String addr = p.substring(lt);
                if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
                }
                p = String.join(" ", encodeWords(name, 45, 45)) + " " + addr;
            }
            result.add(p);
        }
        return String.join(", ", result);
    }

    // ------------------------------------------------------------------------------------------
    // Tách tag đính kèm
    // ------------------------------------------------------------------------------------------

    private record Attachment(String name, byte[] base64) {
    }

    private record ParsedBody(String text, List<Attachment> attachments) {
    }

    private static int totalAttachmentSize(ParsedBody pb) {
        long total = 0;
        for (Attachment a : pb.attachments()) {
            total += a.base64().length + a.base64().length / 76 * 2 + 600L;
        }
        return (int) Math.min(total, 64L * 1024 * 1024);
    }

    /**
     * Tách phần chữ (trước tag đầu tiên) và các đính kèm. Tên file là phần nằm trước dấu ']' CUỐI CÙNG
     * của mỗi cặp tag, nên tên chứa ']' vẫn đúng (base64 không bao giờ chứa ']').
     * Thân không có tag hợp lệ nào thì giữ nguyên toàn bộ làm phần chữ.
     */
    private static ParsedBody parseBody(String body) {
        int firstOpen = body.indexOf(OPEN);
        if (firstOpen < 0 || body.indexOf(CLOSE, firstOpen) < 0) {
            return new ParsedBody(body, List.of());
        }

        String text = body.substring(0, firstOpen).stripTrailing();
        List<Attachment> atts = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        int pos = firstOpen;
        while (true) {
            int open = body.indexOf(OPEN, pos);
            if (open < 0) {
                break;
            }
            int close = body.indexOf(CLOSE, open);
            if (close < 0) {
                notes.add("Lưu ý: một thẻ đính kèm không có thẻ đóng nên đã bị bỏ qua.");
                break;
            }
            pos = close + CLOSE.length();

            int nameStart = open + OPEN.length();
            int nameEnd = body.lastIndexOf(']', close - 1);
            if (nameEnd < nameStart) {
                notes.add("Lưu ý: một thẻ đính kèm bị lỗi định dạng nên đã bị bỏ qua.");
                continue;
            }
            String name = safeFileName(body.substring(nameStart, nameEnd));
            byte[] b64 = cleanBase64(body, nameEnd + 1, close);
            if (b64 == null) {
                notes.add("Lưu ý: không thể đính kèm tệp \"" + name + "\" vì dữ liệu base64 không hợp lệ.");
            } else {
                atts.add(new Attachment(name, b64));
            }
        }

        if (!notes.isEmpty()) {
            String joined = String.join("\n", notes);
            text = text.isEmpty() ? joined : text + "\n\n" + joined;
        }
        return new ParsedBody(text, atts);
    }

    /**
     * Làm sạch base64 trong [from, to): bỏ khoảng trắng/xuống dòng, bỏ tiền tố "data:...;base64,".
     * Trả về mảng ASCII base64 đã đủ padding (độ dài chia hết cho 4), hoặc null nếu không hợp lệ
     * (ký tự lạ, dấu '=' không ở cuối, độ dài vô lý). Thiếu padding thì được bù.
     */
    private static byte[] cleanBase64(String s, int from, int to) {
        int i = from;
        while (i < to && isAsciiWhitespace(s.charAt(i))) {
            i++;
        }
        if (s.startsWith("data:", i)) {
            int comma = s.indexOf(',', i);
            if (comma < 0 || comma >= to || comma - i > 300
                    || !s.substring(i, comma).toLowerCase(java.util.Locale.ROOT).contains(";base64")) {
                return null;
            }
            i = comma + 1;
        }
        byte[] buf = new byte[to - i + 2];
        int n = 0;
        int pad = 0;
        for (; i < to; i++) {
            char c = s.charAt(i);
            if (isAsciiWhitespace(c)) {
                continue;
            }
            if (c == '=') {
                if (++pad > 2) {
                    return null;
                }
            } else if (pad > 0 || !isBase64Char(c)) {
                return null;
            }
            buf[n++] = (byte) c;
        }
        int rem = n % 4;
        if (pad == 0) {
            if (rem == 1) {
                return null;
            }
            if (rem == 2) {
                buf[n++] = '=';
                buf[n++] = '=';
            } else if (rem == 3) {
                buf[n++] = '=';
            }
        } else if (rem != 0) {
            return null;
        }
        return n == buf.length ? buf : Arrays.copyOf(buf, n);
    }

    private static boolean isBase64Char(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/';
    }

    private static boolean isAsciiWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f' || c == 0x0B;
    }

    private static void writeWrappedBase64(ByteArrayOutputStream out, byte[] b64) {
        for (int off = 0; off < b64.length; off += 76) {
            if (off > 0) {
                writeCrlf(out);
            }
            out.write(b64, off, Math.min(76, b64.length - off));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Tên file đính kèm
    // ------------------------------------------------------------------------------------------

    /** Bỏ đường dẫn, ký tự điều khiển, dấu ngoặc kép; giới hạn độ dài (giữ đuôi). Rỗng thì "attachment". */
    static String safeFileName(String raw) {
        int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        String n = slash >= 0 ? raw.substring(slash + 1) : raw;
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 0x20 || (c >= 0x7F && c <= 0x9F) || c == '"' || c == '\u2028' || c == '\u2029') {
                continue;
            }
            sb.append(c);
        }
        String r = sb.toString().trim();
        if (r.isEmpty()) {
            return "attachment";
        }
        if (r.getBytes(StandardCharsets.UTF_8).length > MAX_FILENAME_BYTES) {
            int dot = r.lastIndexOf('.');
            String ext = (dot > 0 && r.length() - dot <= 10) ? r.substring(dot) : "";
            String base = ext.isEmpty() ? r : r.substring(0, dot);
            int budget = MAX_FILENAME_BYTES - ext.getBytes(StandardCharsets.UTF_8).length;
            StringBuilder cut = new StringBuilder();
            int used = 0;
            for (int i = 0; i < base.length(); ) {
                int cp = base.codePointAt(i);
                int len = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
                if (used + len > budget) {
                    break;
                }
                cut.appendCodePoint(cp);
                used += len;
                i += Character.charCount(cp);
            }
            r = cut.toString().trim() + ext;
            if (r.isEmpty()) {
                r = "attachment";
            }
        }
        return r;
    }

    private static void writeAttachmentHeaders(ByteArrayOutputStream out, String name) {
        String ct = contentTypeFor(name);
        String fallback = asciiFallback(name);
        boolean plain = name.equals(fallback);

        StringBuilder sb = new StringBuilder();
        sb.append("Content-Type: ").append(ct).append(";\r\n");
        sb.append(" name=\"").append(fallback).append('"');
        if (!plain) {
            sb.append(";\r\n name*=UTF-8''").append(rfc5987(name));
        }
        sb.append("\r\nContent-Transfer-Encoding: base64\r\n");
        sb.append("Content-Disposition: attachment;\r\n");
        sb.append(" filename=\"").append(fallback).append('"');
        if (!plain) {
            sb.append(";\r\n filename*=UTF-8''").append(rfc5987(name));
        }
        sb.append("\r\n");
        writeAscii(out, sb.toString());
    }

    private static String contentTypeFor(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ct = CONTENT_TYPES.get(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
            if (ct != null) {
                return ct;
            }
        }
        return "application/octet-stream";
    }

    /** Tên ASCII thay thế: bỏ dấu tiếng Việt ("Báo cáo.pdf" -> "Bao cao.pdf"), ký tự còn lại ngoài ASCII thành '_'. */
    private static String asciiFallback(String name) {
        String d = Normalizer.normalize(name.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(d.length());
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }
            sb.append(c >= 0x20 && c <= 0x7E ? c : '_');
        }
        return sb.toString();
    }

    /** Percent-encode theo RFC 5987/2231 (attr-char giữ nguyên, còn lại %XX trên byte UTF-8). */
    private static String rfc5987(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || "!#$&+-.^_`|~".indexOf(c) >= 0) {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 15]);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Quoted-printable (RFC 2045)
    // ------------------------------------------------------------------------------------------

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /**
     * Mã hóa UTF-8 thành quoted-printable. Mọi kiểu xuống dòng trong text (CRLF, LF, CR) thành CRLF cứng;
     * dòng mã hóa &le; 76 ký tự (kể cả '=' nối dòng mềm). Khoảng trắng cuối dòng được mã hóa;
     * '.' ở đầu mỗi dòng vật lý và "From " ở đầu dòng logic được mã hóa.
     * Không thêm CRLF cuối nếu text không kết thúc bằng xuống dòng.
     */
    static byte[] encodeQuotedPrintable(String text) {
        byte[] d = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length + d.length / 3 + 16);
        int i = 0;
        final int n = d.length;
        while (i < n) {
            int le = i;
            while (le < n && d[le] != '\r' && d[le] != '\n') {
                le++;
            }
            encodeQpLine(out, d, i, le);
            if (le < n) {
                writeCrlf(out);
                if (d[le] == '\r' && le + 1 < n && d[le + 1] == '\n') {
                    le++;
                }
            }
            i = le + 1;
        }
        return out.toByteArray();
    }

    private static void encodeQpLine(ByteArrayOutputStream out, byte[] d, int ls, int le) {
        boolean fromLine = le - ls >= 5 && d[ls] == 'F' && d[ls + 1] == 'r' && d[ls + 2] == 'o'
                && d[ls + 3] == 'm' && d[ls + 4] == ' ';
        int col = 0;
        for (int k = ls; k < le; k++) {
            int c = d[k] & 0xFF;
            boolean last = (k == le - 1);
            boolean literal;
            if (c == ' ' || c == '\t') {
                literal = !last;
            } else {
                literal = c >= 33 && c <= 126 && c != '=';
            }
            if (c == '.' && col == 0) {
                literal = false;
            }
            if (k == ls && fromLine) {
                literal = false;
            }
            int len = literal ? 1 : 3;
            if (col + len > (last ? 76 : 75)) {
                out.write('=');
                writeCrlf(out);
                col = 0;
                if (c == '.') { // đầu dòng vật lý mới: không để dấu '.' trần
                    literal = false;
                    len = 3;
                }
            }
            if (literal) {
                out.write(c);
            } else {
                out.write('=');
                out.write(HEX[c >> 4]);
                out.write(HEX[c & 15]);
            }
            col += len;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Tiện ích nhỏ
    // ------------------------------------------------------------------------------------------

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private static int longestToken(String s) {
        int best = 0;
        int cur = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ' ') {
                cur = 0;
            } else if (++cur > best) {
                best = cur;
            }
        }
        return best;
    }

    private static boolean endsWithCrlf(byte[] b) {
        return b.length >= 2 && b[b.length - 2] == '\r' && b[b.length - 1] == '\n';
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void writeCrlf(ByteArrayOutputStream out) {
        out.write('\r');
        out.write('\n');
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(b, 0, b.length);
    }
}