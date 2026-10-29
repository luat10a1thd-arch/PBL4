package com.pbl4.mailserver.mime;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Đọc thư MIME thật (Gmail/Outlook/Yahoo...) từ byte[] RFC 5322.
 *
 * <ul>
 *   <li>{@link #decodeHeader(String)}: RFC 2047 (B/Q), nhiều encoded-word liền nhau, charset bất kỳ, header gấp dòng.</li>
 *   <li>{@link #parse(byte[])}: multipart lồng nhau, quoted-printable, base64, charset khác UTF-8, message/rfc822,
 *       tên tệp RFC 2231/2047. Thư lỗi định dạng trả về bản tốt nhất có thể.</li>
 * </ul>
 * Không ném exception ra ngoài; không bao giờ trả HTML thô trong {@link ParsedMail#text()}.
 */
public final class MimeParser {

    private MimeParser() {
    }

    // ---------------------------------------------------------------------------------------------
    // GIỚI HẠN CHỐNG THƯ ĐỘC (có thể chỉnh ở đây)
    // ---------------------------------------------------------------------------------------------

    /** Độ sâu tối đa của multipart / message/rfc822 lồng nhau. Sâu hơn thì bỏ qua phần đó. */
    public static final int MAX_MIME_DEPTH = 10;
    /** Tổng số phần MIME tối đa được xử lý trong một thư (kể cả thư lồng). */
    public static final int MAX_PARTS = 200;
    /** Kích thước tối đa của khối header của một phần (byte). */
    public static final int MAX_HEADER_BYTES = 256 * 1024;
    /** Số trường header tối đa được ghi nhận cho một phần. */
    public static final int MAX_HEADER_FIELDS = 1000;
    /** Kích thước tối đa (sau giải mã) của MỘT tệp đính kèm. Vượt thì bỏ tệp đó. */
    public static final long MAX_ATTACHMENT_BYTES = 40L * 1024 * 1024;
    /** Tổng kích thước tối đa (sau giải mã) của mọi đính kèm trong một thư. */
    public static final long MAX_TOTAL_ATTACHMENT_BYTES = 64L * 1024 * 1024;
    /** Kích thước tối đa (byte sau giải mã transfer-encoding) của MỘT phần văn bản. Vượt thì cắt. */
    public static final int MAX_TEXT_PART_BYTES = 4 * 1024 * 1024;
    /** Số ký tự tối đa của thân văn bản trả về. */
    public static final int MAX_TEXT_CHARS = 2_000_000;

    private static final String TRUNCATED_NOTE =
            "[Một phần nội dung thư đã bị bỏ qua vì vượt giới hạn xử lý của máy chủ]";

    private static final Charset WIN1252 = Charset.forName("windows-1252");

    // ---------------------------------------------------------------------------------------------
    // CHARSET
    // ---------------------------------------------------------------------------------------------

    private static final Map<String, Optional<Charset>> CHARSET_CACHE = new ConcurrentHashMap<>();

    private static Optional<Charset> first(String... names) {
        for (String n : names) {
            try {
                return Optional.of(Charset.forName(n));
            } catch (RuntimeException ignored) {
                // thử tên kế tiếp
            }
        }
        return Optional.empty();
    }

    private static Optional<Charset> resolveCharset(String n) {
        switch (n) {
            case "x-unknown":
            case "unknown-8bit":
            case "unknown":
            case "default":
            case "none":
            case "x-user-defined":
            case "utf-7":
            case "unicode-1-1-utf-7":
                return Optional.empty();
            case "utf8":
                return Optional.of(StandardCharsets.UTF_8);
            case "gb2312":
            case "gb_2312-80":
            case "gb2312-80":
            case "x-gbk":
            case "gbk":
            case "cp936":
            case "euc-cn":
                return first("GBK", "GB2312", "GB18030");
            case "ks_c_5601-1987":
            case "ks_c_5601":
            case "ks_c_5601-1989":
            case "ksc5601":
            case "euc_kr":
            case "euc-kr":
                return first("x-windows-949", "EUC-KR");
            case "iso-8859-8-i":
                return first("ISO-8859-8");
            case "iso-8859-6-i":
                return first("ISO-8859-6");
            case "shift-jis":
            case "shift_jis":
            case "x-sjis":
            case "sjis":
            case "ms_kanji":
                return first("windows-31j", "Shift_JIS");
            case "iso-8859-11":
            case "tis-620":
            case "windows-874":
                return first("x-windows-874", "TIS-620", "x-iso-8859-11");
            // Thư gắn nhãn ISO-8859-1 hầu như luôn là windows-1252 (tập cha, in được ký tự 0x80-0x9F)
            case "iso-8859-1":
            case "iso_8859-1":
            case "latin1":
            case "l1":
            case "ibm819":
            case "cp819":
            case "windows-1252":
            case "cp1252":
                return Optional.of(WIN1252);
            case "us-ascii":
            case "ascii":
            case "ansi_x3.4-1968":
            case "iso646-us":
                return Optional.of(StandardCharsets.US_ASCII);
            default:
                try {
                    return Optional.of(Charset.forName(n));
                } catch (RuntimeException e) {
                    return Optional.empty();
                }
        }
    }

    /** Trả null nếu tên rỗng/lạ/không hỗ trợ. Bỏ phần ngôn ngữ "*vi" (RFC 2231) và dấu nháy. */
    private static Charset lookupCharset(String name) {
        if (name == null) return null;
        String n = name.trim();
        int star = n.indexOf('*');
        if (star >= 0) n = n.substring(0, star);
        n = stripQuotes(n).trim().toLowerCase(Locale.ROOT);
        if (n.isEmpty() || n.length() > 64) return null;
        return CHARSET_CACHE.computeIfAbsent(n, MimeParser::resolveCharset).orElse(null);
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String decodeStrictUtf8OrWin1252(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, WIN1252);
        }
    }

    private static boolean allAscii(byte[] b) {
        for (byte x : b) {
            if (x < 0) return false;
        }
        return true;
    }

    /**
     * Giải mã byte theo tên charset khai báo, có đường lùi hợp lý:
     * charset lạ/vắng -> thử UTF-8 chặt, hỏng thì windows-1252; us-ascii mà có byte cao -> như trên;
     * charset hợp lệ -> giải mã lỏng (ký tự hỏng thành U+FFFD).
     */
    static String decodeBytes(byte[] b, String charsetName) {
        if (b == null || b.length == 0) return "";
        Charset c = lookupCharset(charsetName);
        if (c == null) return decodeStrictUtf8OrWin1252(b);
        if (c == StandardCharsets.US_ASCII) {
            return allAscii(b) ? new String(b, StandardCharsets.US_ASCII) : decodeStrictUtf8OrWin1252(b);
        }
        return new String(b, c);
    }

    private static String bytesToHeaderString(byte[] b, int s, int e) {
        boolean ascii = true;
        for (int i = s; i < e; i++) {
            if (b[i] < 0) {
                ascii = false;
                break;
            }
        }
        if (ascii) return new String(b, s, e - s, StandardCharsets.ISO_8859_1);
        return decodeStrictUtf8OrWin1252(Arrays.copyOfRange(b, s, e));
    }

    // ---------------------------------------------------------------------------------------------
    // GIẢI MÃ TRANSFER-ENCODING
    // ---------------------------------------------------------------------------------------------

    private static final byte[] B64 = new byte[128];

    static {
        Arrays.fill(B64, (byte) -1);
        String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < alpha.length(); i++) B64[alpha.charAt(i)] = (byte) i;
    }

    /** Base64 lỏng: bỏ qua ký tự lạ/xuống dòng, chịu thiếu padding. Trả null nếu kết quả vượt max. */
    private static byte[] decodeBase64(byte[] src, int s, int e, long max) {
        long estimate = ((long) (e - s) * 3) / 4 + 3;
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(estimate, 1 << 20));
        int acc = 0;
        int cnt = 0;
        long written = 0;
        for (int i = s; i < e; i++) {
            int c = src[i];
            if (c == '=') break;
            if (c < 0 || c >= 128) continue;
            int v = B64[c];
            if (v < 0) continue;
            acc = (acc << 6) | v;
            if (++cnt == 4) {
                out.write((acc >> 16) & 0xFF);
                out.write((acc >> 8) & 0xFF);
                out.write(acc & 0xFF);
                written += 3;
                acc = 0;
                cnt = 0;
                if (written > max) return null;
            }
        }
        if (cnt == 2) {
            out.write((acc >> 4) & 0xFF);
            written++;
        } else if (cnt == 3) {
            out.write((acc >> 10) & 0xFF);
            out.write((acc >> 2) & 0xFF);
            written += 2;
        }
        if (written > max) return null;
        return out.toByteArray();
    }

    private static int hexVal(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return -1;
    }

    /** Quoted-printable lỏng; giữ nguyên ngắt dòng của nguồn, bỏ khoảng trắng thừa cuối dòng. */
    private static byte[] decodeQuotedPrintable(byte[] b, int s, int e, long max) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(e - s, 1 << 20));
        int i = s;
        while (i < e) {
            byte x = b[i];
            if (x == '=') {
                if (i + 1 < e && b[i + 1] == '\n') {
                    i += 2;
                    continue;
                }
                if (i + 2 < e && b[i + 1] == '\r' && b[i + 2] == '\n') {
                    i += 3;
                    continue;
                }
                if (i + 2 < e) {
                    int h1 = hexVal(b[i + 1]);
                    int h2 = hexVal(b[i + 2]);
                    if (h1 >= 0 && h2 >= 0) {
                        out.write((h1 << 4) | h2);
                        i += 3;
                        continue;
                    }
                }
                if (i + 1 >= e) { // "=" ở cuối dữ liệu: coi như ngắt dòng mềm
                    i++;
                    continue;
                }
                // "=" + khoảng trắng + xuống dòng: ngắt dòng mềm có khoảng trắng thừa
                int j = i + 1;
                while (j < e && (b[j] == ' ' || b[j] == '\t')) j++;
                if (j < e && (b[j] == '\n' || b[j] == '\r')) {
                    if (b[j] == '\r' && j + 1 < e && b[j + 1] == '\n') j += 2;
                    else j++;
                    i = j;
                    continue;
                }
                out.write('=');
                i++;
                continue;
            }
            if (x == ' ' || x == '\t') {
                int j = i;
                while (j < e && (b[j] == ' ' || b[j] == '\t')) j++;
                if (j >= e || b[j] == '\n' || b[j] == '\r') {
                    i = j; // khoảng trắng cuối dòng bị bỏ theo RFC 2045
                    continue;
                }
                out.write(b, i, j - i);
                i = j;
                continue;
            }
            out.write(x);
            i++;
            if (out.size() > max) return null;
        }
        if (out.size() > max) return null;
        return out.toByteArray();
    }

    /** Trả null nếu vượt max. */
    private static byte[] decodeTransfer(byte[] buf, int s, int e, String cte, long max) {
        if (e < s) e = s;
        if ("base64".equals(cte)) return decodeBase64(buf, s, e, max);
        if ("quoted-printable".equals(cte)) return decodeQuotedPrintable(buf, s, e, max);
        if ((long) (e - s) > max) return null;
        return Arrays.copyOfRange(buf, s, e);
    }

    // ---------------------------------------------------------------------------------------------
    // HEADER
    // ---------------------------------------------------------------------------------------------

    private static final class Headers {
        final Map<String, List<String>> map = new HashMap<>();
        int bodyStart;

        String first(String name) {
            List<String> l = map.get(name);
            return (l == null || l.isEmpty()) ? null : l.get(0);
        }

        void add(String name, String value) {
            if (map.size() >= MAX_HEADER_FIELDS && !map.containsKey(name)) return;
            map.computeIfAbsent(name, k -> new ArrayList<>(1)).add(value.strip());
        }
    }

    private static int indexOfByte(byte[] b, byte x, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] == x) return i;
        }
        return -1;
    }

    private static boolean validHeaderName(String n) {
        if (n.isEmpty()) return false;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c <= 32 || c >= 127 || c == ':') return false;
        }
        return true;
    }

    /** Đọc khối header [start,end); bodyStart trỏ tới byte đầu thân (sau dòng trống). Lỏng với thư lỗi. */
    private static Headers parseHeaders(byte[] buf, int start, int end) {
        Headers h = new Headers();
        int pos = start;
        // Bỏ dòng "From " kiểu mbox nếu có
        if (end - pos > 5 && buf[pos] == 'F' && buf[pos + 1] == 'r' && buf[pos + 2] == 'o'
                && buf[pos + 3] == 'm' && buf[pos + 4] == ' ') {
            int le = indexOfByte(buf, (byte) '\n', pos, end);
            pos = le < 0 ? end : le + 1;
        }
        int headerStart = pos;
        String curName = null;
        StringBuilder cur = null;
        while (pos < end) {
            int le = indexOfByte(buf, (byte) '\n', pos, end);
            int lineEnd = le < 0 ? end : le;
            int next = le < 0 ? end : le + 1;
            int contentEnd = lineEnd;
            if (contentEnd > pos && buf[contentEnd - 1] == '\r') contentEnd--;

            if (contentEnd == pos) { // dòng trống: hết header
                if (curName != null) h.add(curName, cur.toString());
                h.bodyStart = next;
                return h;
            }
            if (pos - headerStart > MAX_HEADER_BYTES) { // header quá lớn: coi như không có thân
                if (curName != null) h.add(curName, cur.toString());
                h.bodyStart = end;
                return h;
            }
            String line = bytesToHeaderString(buf, pos, contentEnd);
            char c0 = line.charAt(0);
            if (c0 == ' ' || c0 == '\t') {
                if (curName != null) cur.append(line); // dòng nối tiếp, giữ khoảng trắng đầu dòng
            } else {
                int colon = line.indexOf(':');
                String name = colon > 0 ? line.substring(0, colon).trim().toLowerCase(Locale.ROOT) : "";
                if (!validHeaderName(name)) {
                    // Không phải header: thư lỗi, coi từ dòng này là thân
                    if (curName != null) h.add(curName, cur.toString());
                    h.bodyStart = pos;
                    return h;
                }
                if (curName != null) h.add(curName, cur.toString());
                curName = name;
                cur = new StringBuilder(line.substring(colon + 1));
            }
            pos = next;
        }
        if (curName != null) h.add(curName, cur.toString());
        h.bodyStart = end;
        return h;
    }

    // ---------------------------------------------------------------------------------------------
    // CONTENT-TYPE / CONTENT-DISPOSITION (tham số, RFC 2231)
    // ---------------------------------------------------------------------------------------------

    private static final class Field {
        String value = "";
        final Map<String, String> params = new HashMap<>();
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    private static Field parseField(String headerValue) {
        Field f = new Field();
        if (headerValue == null) return f;
        String s = headerValue;
        int n = s.length();
        int semi = s.indexOf(';');
        f.value = (semi < 0 ? s : s.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
        int i = semi < 0 ? n : semi + 1;
        List<String[]> raw = new ArrayList<>();
        while (i < n) {
            while (i < n && (s.charAt(i) == ';' || isWs(s.charAt(i)))) i++;
            int ks = i;
            while (i < n && s.charAt(i) != '=' && s.charAt(i) != ';') i++;
            String key = s.substring(ks, i).trim().toLowerCase(Locale.ROOT);
            if (i >= n || s.charAt(i) == ';') continue; // tham số không có giá trị
            i++; // bỏ '='
            while (i < n && isWs(s.charAt(i))) i++;
            String val;
            if (i < n && s.charAt(i) == '"') {
                i++;
                StringBuilder vb = new StringBuilder();
                while (i < n) {
                    char c = s.charAt(i++);
                    if (c == '\\' && i < n && (s.charAt(i) == '"' || s.charAt(i) == '\\')) {
                        vb.append(s.charAt(i++));
                    } else if (c == '"') {
                        break;
                    } else {
                        vb.append(c); // gồm cả '\' đơn (đường dẫn kiểu Windows)
                    }
                }
                val = vb.toString();
                while (i < n && s.charAt(i) != ';') i++;
            } else {
                int vs = i;
                while (i < n && s.charAt(i) != ';') i++;
                val = s.substring(vs, i).trim();
            }
            if (!key.isEmpty()) raw.add(new String[]{key, val});
        }

        Map<String, TreeMap<Integer, String[]>> ext = new HashMap<>();
        for (String[] kv : raw) {
            String k = kv[0];
            int star = k.indexOf('*');
            if (star < 0) {
                f.params.putIfAbsent(k, kv[1]);
                continue;
            }
            String base = k.substring(0, star);
            String rest = k.substring(star + 1);
            boolean extended = rest.isEmpty() || rest.endsWith("*");
            String idxStr = rest.endsWith("*") ? rest.substring(0, rest.length() - 1) : rest;
            int idx = 0;
            if (!idxStr.isEmpty()) {
                try {
                    idx = Integer.parseInt(idxStr);
                } catch (NumberFormatException ex) {
                    continue;
                }
            }
            if (base.isEmpty() || idx < 0 || idx > 100) continue;
            ext.computeIfAbsent(base, x -> new TreeMap<>()).put(idx, new String[]{kv[1], extended ? "1" : "0"});
        }
        for (Map.Entry<String, TreeMap<Integer, String[]>> en : ext.entrySet()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            String charset = null;
            boolean firstSeg = true;
            for (String[] seg : en.getValue().values()) {
                String v = seg[0];
                boolean extended = "1".equals(seg[1]);
                if (extended) {
                    if (firstSeg) {
                        int q1 = v.indexOf('\'');
                        int q2 = q1 < 0 ? -1 : v.indexOf('\'', q1 + 1);
                        if (q1 >= 0 && q2 >= 0) {
                            charset = v.substring(0, q1);
                            v = v.substring(q2 + 1);
                        }
                    }
                    percentDecode(v, bytes);
                } else {
                    byte[] vb = v.getBytes(StandardCharsets.UTF_8);
                    bytes.write(vb, 0, vb.length);
                }
                firstSeg = false;
            }
            f.params.put(en.getKey(), decodeBytes(bytes.toByteArray(), charset));
        }
        return f;
    }

    private static void percentDecode(String v, ByteArrayOutputStream out) {
        int n = v.length();
        for (int i = 0; i < n; i++) {
            char c = v.charAt(i);
            if (c == '%' && i + 2 < n && hexVal(v.charAt(i + 1)) >= 0 && hexVal(v.charAt(i + 2)) >= 0) {
                out.write((hexVal(v.charAt(i + 1)) << 4) | hexVal(v.charAt(i + 2)));
                i += 2;
            } else {
                int len = (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(v.charAt(i + 1))) ? 2 : 1;
                byte[] cb = v.substring(i, i + len).getBytes(StandardCharsets.UTF_8);
                out.write(cb, 0, cb.length);
                i += len - 1;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // RFC 2047
    // ---------------------------------------------------------------------------------------------

    private static final class EncodedWord {
        final int end;
        final String charset;
        final byte[] bytes;

        EncodedWord(int end, String charset, byte[] bytes) {
            this.end = end;
            this.charset = charset;
            this.bytes = bytes;
        }
    }

    /** Mở gấp dòng: CRLF + khoảng trắng -> giữ khoảng trắng; CR/LF lẻ -> một dấu cách. */
    private static String unfold(String v) {
        if (v.indexOf('\r') < 0 && v.indexOf('\n') < 0) return v;
        StringBuilder sb = new StringBuilder(v.length());
        int n = v.length();
        int i = 0;
        while (i < n) {
            char c = v.charAt(i);
            if (c == '\r' || c == '\n') {
                while (i < n && (v.charAt(i) == '\r' || v.charAt(i) == '\n')) i++;
                if (i < n && v.charAt(i) != ' ' && v.charAt(i) != '\t') sb.append(' ');
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static EncodedWord parseEncodedWord(String s, int i) {
        int n = s.length();
        if (i + 7 > n || s.charAt(i) != '=' || s.charAt(i + 1) != '?') return null;
        int q1 = s.indexOf('?', i + 2);
        if (q1 < 0 || q1 - (i + 2) > 64) return null;
        if (q1 + 2 >= n || s.charAt(q1 + 2) != '?') return null;
        char enc = Character.toUpperCase(s.charAt(q1 + 1));
        if (enc != 'B' && enc != 'Q') return null;
        int q3 = s.indexOf("?=", q1 + 3);
        if (q3 < 0) return null;
        String charset = s.substring(i + 2, q1);
        String text = s.substring(q1 + 3, q3);
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == ' ' || c == '\t') return null; // encoded-word không chứa khoảng trắng
        }
        byte[] bytes;
        if (enc == 'B') {
            byte[] src = text.getBytes(StandardCharsets.ISO_8859_1);
            bytes = decodeBase64(src, 0, src.length, Integer.MAX_VALUE);
            if (bytes == null) return null;
        } else {
            ByteArrayOutputStream out = new ByteArrayOutputStream(text.length());
            for (int k = 0; k < text.length(); k++) {
                char c = text.charAt(k);
                if (c == '_') {
                    out.write(' ');
                } else if (c == '=' && k + 2 < text.length()
                        && hexVal(text.charAt(k + 1)) >= 0 && hexVal(text.charAt(k + 2)) >= 0) {
                    out.write((hexVal(text.charAt(k + 1)) << 4) | hexVal(text.charAt(k + 2)));
                    k += 2;
                } else if (c < 128) {
                    out.write(c);
                } else {
                    byte[] cb = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                    out.write(cb, 0, cb.length);
                }
            }
            bytes = out.toByteArray();
        }
        return new EncodedWord(q3 + 2, charset, bytes);
    }

    private static void flushRun(StringBuilder out, List<EncodedWord> run) {
        int i = 0;
        while (i < run.size()) {
            String cs = run.get(i).charset.trim().toLowerCase(Locale.ROOT);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int j = i;
            // Gộp các encoded-word liền nhau cùng charset trước khi giải mã: chịu được ký tự đa byte bị cắt đôi giữa hai từ
            while (j < run.size() && run.get(j).charset.trim().toLowerCase(Locale.ROOT).equals(cs)) {
                byte[] b = run.get(j).bytes;
                bytes.write(b, 0, b.length);
                j++;
            }
            out.append(decodeBytes(bytes.toByteArray(), run.get(i).charset));
            i = j;
        }
        run.clear();
    }

    /**
     * Giá trị header không bao giờ hợp lệ khi chứa ký tự điều khiển: đổi thành dấu cách (kể cả do encoded-word nhúng vào),
     * cùng U+2028/U+2029; rồi cắt khoảng trắng hai đầu. Bảo vệ cả các nơi khác dựng JSON từ giá trị này.
     */
    private static String cleanHeaderOutput(String s) {
        int n = s.length();
        StringBuilder sb = null;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            boolean bad = c < 0x20 || c == 0x7f || c == '\u2028' || c == '\u2029';
            if (bad) {
                if (sb == null) {
                    sb = new StringBuilder(n);
                    sb.append(s, 0, i);
                }
                sb.append(' ');
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return (sb == null ? s : sb.toString()).strip();
    }

    /**
     * Giải mã header theo RFC 2047. Không ném exception; đầu vào null trả "".
     * Khoảng trắng giữa hai encoded-word liền nhau bị bỏ; encoded-word lỗi được giữ nguyên văn.
     */
    public static String decodeHeader(String v) {
        if (v == null || v.isEmpty()) return "";
        try {
            String s = unfold(v);
            if (s.indexOf("=?") < 0) return cleanHeaderOutput(s);
            StringBuilder out = new StringBuilder(s.length());
            List<EncodedWord> run = new ArrayList<>();
            int n = s.length();
            int i = 0;
            while (i < n) {
                EncodedWord w = (s.charAt(i) == '=') ? parseEncodedWord(s, i) : null;
                if (w != null) {
                    run.add(w);
                    i = w.end;
                    continue;
                }
                char c = s.charAt(i);
                if (!run.isEmpty()) {
                    if (isWs(c)) {
                        int j = i;
                        while (j < n && isWs(s.charAt(j))) j++;
                        if (j < n && parseEncodedWord(s, j) != null) {
                            i = j; // khoảng trắng giữa hai encoded-word: bỏ
                            continue;
                        }
                    }
                    flushRun(out, run);
                }
                out.append(c);
                i++;
            }
            flushRun(out, run);
            return cleanHeaderOutput(out.toString());
        } catch (RuntimeException e) {
            try {
                return cleanHeaderOutput(unfold(v));
            } catch (RuntimeException e2) {
                return "";
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // LÀM SẠCH VĂN BẢN
    // ---------------------------------------------------------------------------------------------

    /**
     * Chuẩn hóa xuống dòng về "\n", bỏ ký tự điều khiển (trừ \n và \t), sửa surrogate lẻ.
     * Vô hiệu hóa chuỗi "[ATTACHMENT:" xuất hiện trong chính nội dung thư để thư lạ không giả được đính kèm ở frontend.
     */
    static String cleanText(String s) {
        int n = s.length();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                if (i + 1 < n && s.charAt(i + 1) == '\n') i++;
                sb.append('\n');
            } else if (c == '\n' || c == '\t') {
                sb.append(c);
            } else if (c < 0x20 || c == 0x7f) {
                // bỏ
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                    sb.append(c).append(s.charAt(++i));
                } else {
                    sb.append('\uFFFD');
                }
            } else if (Character.isLowSurrogate(c)) {
                sb.append('\uFFFD');
            } else if (c == '\uFFFE' || c == '\uFFFF') {
                // bỏ
            } else {
                sb.append(c);
            }
        }
        String r = sb.toString();
        return r.contains("[ATTACHMENT:") ? r.replace("[ATTACHMENT:", "[ATTACHMENT :") : r;
    }

    private static String stripTrailingBlank(String s) {
        int e = s.length();
        while (e > 0) {
            char c = s.charAt(e - 1);
            if (c == '\n' || c == ' ' || c == '\t') e--;
            else break;
        }
        return s.substring(0, e);
    }

    // ---------------------------------------------------------------------------------------------
    // HTML -> VĂN BẢN AN TOÀN (bộ quét tuyến tính, không regex)
    // ---------------------------------------------------------------------------------------------

    private static final Map<String, String> ENTITIES = new HashMap<>();

    static {
        String[][] pairs = {
                {"amp", "&"}, {"lt", "<"}, {"gt", ">"}, {"quot", "\""}, {"apos", "'"}, {"nbsp", " "},
                {"copy", "\u00A9"}, {"reg", "\u00AE"}, {"trade", "\u2122"}, {"hellip", "\u2026"},
                {"mdash", "\u2014"}, {"ndash", "\u2013"}, {"lsquo", "\u2018"}, {"rsquo", "\u2019"},
                {"ldquo", "\u201C"}, {"rdquo", "\u201D"}, {"sbquo", "\u201A"}, {"bdquo", "\u201E"},
                {"bull", "\u2022"}, {"middot", "\u00B7"}, {"euro", "\u20AC"}, {"pound", "\u00A3"},
                {"yen", "\u00A5"}, {"cent", "\u00A2"}, {"sect", "\u00A7"}, {"deg", "\u00B0"},
                {"plusmn", "\u00B1"}, {"times", "\u00D7"}, {"divide", "\u00F7"}, {"laquo", "\u00AB"},
                {"raquo", "\u00BB"}, {"iexcl", "\u00A1"}, {"iquest", "\u00BF"}, {"shy", ""},
                {"zwnj", "\u200C"}, {"zwj", "\u200D"}, {"ensp", " "}, {"emsp", " "}, {"thinsp", " "},
                {"rarr", "\u2192"}, {"larr", "\u2190"}, {"para", "\u00B6"}, {"frac12", "\u00BD"},
                {"frac14", "\u00BC"}, {"frac34", "\u00BE"}, {"sup2", "\u00B2"}, {"sup3", "\u00B3"},
                {"micro", "\u00B5"}, {"curren", "\u00A4"}, {"brvbar", "\u00A6"}, {"uml", "\u00A8"},
                {"ordf", "\u00AA"}, {"ordm", "\u00BA"}, {"not", "\u00AC"}, {"macr", "\u00AF"},
                {"acute", "\u00B4"}, {"cedil", "\u00B8"}, {"sup1", "\u00B9"}
        };
        for (String[] p : pairs) ENTITIES.put(p[0], p[1]);
        // Chữ Latin-1 U+00C0..U+00FF (bỏ × và ÷)
        String latin = "Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml "
                + "ETH Ntilde Ograve Oacute Ocirc Otilde Ouml * Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig "
                + "agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml "
                + "eth ntilde ograve oacute ocirc otilde ouml * oslash ugrave uacute ucirc uuml yacute thorn yuml";
        String[] names = latin.split(" ");
        for (int k = 0; k < names.length; k++) {
            if (!"*".equals(names[k])) ENTITIES.put(names[k], String.valueOf((char) (0xC0 + k)));
        }
    }

    private static final class HtmlOut {
        final StringBuilder sb = new StringBuilder();
        int pre = 0;
        boolean space = false;

        char last() {
            return sb.length() == 0 ? '\n' : sb.charAt(sb.length() - 1);
        }

        void text(char c) {
            if (space && sb.length() > 0 && last() != '\n' && last() != ' ') sb.append(' ');
            space = false;
            sb.append(c);
        }

        void literal(String s) { // thực thể đã giải mã: giữ nguyên, không gộp khoảng trắng
            if (s.isEmpty()) return;
            if (space && sb.length() > 0 && last() != '\n' && last() != ' ' && s.charAt(0) != ' ') sb.append(' ');
            space = false;
            sb.append(s);
        }

        void whitespace(char c) {
            if (pre > 0) {
                if (c != '\r') sb.append(c);
            } else {
                space = true;
            }
        }

        void newline() { // đảm bảo đang ở đầu dòng
            space = false;
            if (sb.length() > 0 && last() != '\n') sb.append('\n');
        }

        void blankLine() { // đảm bảo có một dòng trống phía trước
            newline();
            int len = sb.length();
            if (len > 0 && !(len >= 2 && sb.charAt(len - 2) == '\n')) sb.append('\n');
        }

        void forceBreak() {
            space = false;
            sb.append('\n');
        }

        void cell() {
            space = false;
            if (sb.length() > 0 && last() != '\n' && last() != ' ') sb.append(' ');
        }
    }

    private static boolean regionEqIgnoreCase(String s, int at, String lowerNeedle) {
        return s.regionMatches(true, at, lowerNeedle, 0, lowerNeedle.length());
    }

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        int max = s.length() - needle.length();
        for (int i = Math.max(0, from); i <= max; i++) {
            if (s.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * Chuyển HTML sang văn bản thuần: bỏ mọi thẻ, bỏ nội dung script/style/head/title, đổi br và thẻ khối thành xuống dòng,
     * giải mã thực thể. Kết quả không chứa thẻ HTML.
     */
    static String htmlToText(String html) {
        HtmlOut o = new HtmlOut();
        int n = html.length();
        int i = 0;
        while (i < n && o.sb.length() < MAX_TEXT_CHARS) {
            char c = html.charAt(i);
            if (c == '<') {
                if (html.startsWith("<!--", i)) {
                    int end = html.indexOf("-->", i + 4);
                    i = end < 0 ? n : end + 3;
                    continue;
                }
                char c1 = i + 1 < n ? html.charAt(i + 1) : 0;
                if (c1 == '!' || c1 == '?') {
                    int end = html.indexOf('>', i + 1);
                    i = end < 0 ? n : end + 1;
                    continue;
                }
                if (c1 == '/' || isAsciiLetter(c1)) {
                    int j = i + 1;
                    boolean closing = false;
                    if (html.charAt(j) == '/') {
                        closing = true;
                        j++;
                    }
                    int nameStart = j;
                    while (j < n && (isAsciiLetter(html.charAt(j)) || (html.charAt(j) >= '0' && html.charAt(j) <= '9'))) j++;
                    String name = html.substring(nameStart, j).toLowerCase(Locale.ROOT);
                    // Tìm '>' có nhận biết dấu nháy, tối đa 8192 ký tự; quá thì lùi về '>' đầu tiên
                    int k = j;
                    char q = 0;
                    int lim = Math.min(n, j + 8192);
                    int tagEnd = -1;
                    while (k < lim) {
                        char ch = html.charAt(k);
                        if (q != 0) {
                            if (ch == q) q = 0;
                        } else if (ch == '"' || ch == '\'') {
                            q = ch;
                        } else if (ch == '>') {
                            tagEnd = k;
                            break;
                        }
                        k++;
                    }
                    if (tagEnd < 0) tagEnd = html.indexOf('>', j);
                    if (tagEnd < 0) { // thẻ không đóng: bỏ phần còn lại
                        i = n;
                        continue;
                    }
                    i = tagEnd + 1;
                    if (name.isEmpty()) continue;
                    if (!closing) {
                        if (name.equals("script") || name.equals("style")) {
                            int ce = indexOfIgnoreCase(html, "</" + name, i);
                            if (ce < 0) {
                                i = n; // script/style không đóng: bỏ hết cho an toàn
                            } else {
                                int gt = html.indexOf('>', ce);
                                i = gt < 0 ? n : gt + 1;
                            }
                            continue;
                        }
                        if (name.equals("head") || name.equals("title")) {
                            int ce = indexOfIgnoreCase(html, "</" + name, i);
                            if (ce >= 0) {
                                int gt = html.indexOf('>', ce);
                                i = gt < 0 ? n : gt + 1;
                            }
                            continue;
                        }
                    }
                    applyTag(o, name, closing);
                    continue;
                }
                o.text('<'); // dấu '<' trơ trọi trong văn bản
                i++;
                continue;
            }
            if (c == '&') {
                int semi = -1;
                int lim = Math.min(n, i + 12);
                for (int k = i + 1; k < lim; k++) {
                    if (html.charAt(k) == ';') {
                        semi = k;
                        break;
                    }
                }
                if (semi > i + 1) {
                    String ent = html.substring(i + 1, semi);
                    String rep = null;
                    boolean known = false;
                    if (ent.charAt(0) == '#') {
                        try {
                            int cp = (ent.length() > 1 && (ent.charAt(1) == 'x' || ent.charAt(1) == 'X'))
                                    ? Integer.parseInt(ent.substring(2), 16)
                                    : Integer.parseInt(ent.substring(1));
                            known = true;
                            if (cp == 9 || cp == 10 || cp == 13 || (cp >= 32 && cp != 127 && Character.isValidCodePoint(cp)
                                    && !(cp >= 0xD800 && cp <= 0xDFFF) && cp != 0xFFFE && cp != 0xFFFF)) {
                                rep = cp == 160 ? " " : new String(Character.toChars(cp));
                                if (cp == 13) rep = "\n";
                            } else {
                                rep = "";
                            }
                        } catch (NumberFormatException ex) {
                            known = false;
                        }
                    } else {
                        rep = ENTITIES.get(ent);
                        known = rep != null;
                    }
                    if (known) {
                        o.literal(rep);
                        i = semi + 1;
                        continue;
                    }
                }
                o.text('&');
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                o.whitespace(c);
            } else {
                o.text(c);
            }
            i++;
        }
        return tidyLines(o.sb.toString());
    }

    private static void applyTag(HtmlOut o, String name, boolean closing) {
        switch (name) {
            case "br":
                o.forceBreak();
                break;
            case "hr":
                o.newline();
                break;
            case "p":
            case "h1":
            case "h2":
            case "h3":
            case "h4":
            case "h5":
            case "h6":
            case "blockquote":
            case "table":
                if (closing) o.blankLine();
                else o.newline();
                break;
            case "div":
            case "tr":
            case "ul":
            case "ol":
            case "section":
            case "article":
            case "header":
            case "footer":
            case "form":
            case "dl":
            case "dt":
            case "dd":
            case "address":
            case "center":
            case "figure":
            case "fieldset":
                o.newline();
                break;
            case "li":
                o.newline();
                if (!closing) o.sb.append("- ");
                break;
            case "td":
            case "th":
                o.cell();
                break;
            case "pre":
                if (closing) {
                    o.pre = Math.max(0, o.pre - 1);
                    o.newline();
                } else {
                    o.newline();
                    o.pre++;
                }
                break;
            default:
                break; // thẻ khác: bỏ thẻ, giữ nội dung
        }
    }

    /** Bỏ khoảng trắng cuối dòng, tối đa hai dòng trống liên tiếp, bỏ dòng trống đầu/cuối, bỏ ký tự điều khiển. */
    private static String tidyLines(String s) {
        String[] lines = s.split("\n", -1);
        StringBuilder sb = new StringBuilder(s.length());
        int blanks = 0;
        for (String line : lines) {
            int e = line.length();
            while (e > 0 && (line.charAt(e - 1) == ' ' || line.charAt(e - 1) == '\t')) e--;
            if (e == 0) {
                if (sb.length() > 0) blanks++;
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
                for (int k = 0; k < Math.min(blanks, 2); k++) sb.append('\n');
            }
            blanks = 0;
            sb.append(line, 0, e);
        }
        return cleanText(sb.toString());
    }

    // ---------------------------------------------------------------------------------------------
    // DUYỆT CÂY MIME
    // ---------------------------------------------------------------------------------------------

    private static final class Ctx {
        int parts = 0;
        long attachmentBytes = 0;
        int attachmentSeq = 0;
        boolean truncated = false;
        final List<AttachmentInfo> attachments = new ArrayList<>();
    }

    private static final class Result {
        String text = "";
        boolean htmlOnly = false;
    }

    private static String cteOf(Headers h) {
        String v = h.first("content-transfer-encoding");
        if (v == null) return "";
        int semi = v.indexOf(';');
        if (semi >= 0) v = v.substring(0, semi);
        return v.trim().toLowerCase(Locale.ROOT);
    }

    private static String sanitizeFileName(String name) {
        if (name == null) return null;
        String n = name;
        int cut = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (cut >= 0) n = n.substring(cut + 1);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c >= 0x20 && c != 0x7f) sb.append(c);
        }
        n = sb.toString().trim();
        if (n.length() > 255) n = n.substring(0, 255);
        return n.isEmpty() ? null : n;
    }

    private static String fileNameOf(Field disposition, Field contentType) {
        String f = disposition.params.get("filename");
        if (f == null || f.isBlank()) f = contentType.params.get("name");
        if (f == null) return null;
        if (f.contains("=?")) f = decodeHeader(f);
        return sanitizeFileName(f);
    }

    private static String extensionFor(String type) {
        switch (type) {
            case "image/jpeg": return ".jpg";
            case "image/png": return ".png";
            case "image/gif": return ".gif";
            case "image/webp": return ".webp";
            case "image/svg+xml": return ".svg";
            case "application/pdf": return ".pdf";
            case "application/zip": return ".zip";
            case "text/calendar": return ".ics";
            case "text/plain":
            case "message/delivery-status":
            case "text/rfc822-headers": return ".txt";
            case "text/html": return ".html";
            case "message/rfc822": return ".eml";
            case "application/pkcs7-signature":
            case "application/x-pkcs7-signature": return ".p7s";
            default: return ".bin";
        }
    }

    private static String contentTypeOf(Field ct) {
        String t = ct.value;
        if (t.isEmpty() || t.indexOf('/') <= 0 || t.length() > 100) return "application/octet-stream";
        return t;
    }

    private static void addAttachment(Ctx ctx, byte[] data, String type, String name) {
        String n = name;
        if (n == null) {
            ctx.attachmentSeq++;
            n = "attachment-" + ctx.attachmentSeq + extensionFor(type);
        }
        ctx.attachmentBytes += data.length;
        ctx.attachments.add(new AttachmentInfo(n, type, data));
    }

    private static List<int[]> splitMultipart(byte[] buf, int start, int end, String boundary) {
        List<int[]> parts = new ArrayList<>();
        byte[] dash = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int partStart = -1;
        int pos = start;
        boolean closed = false;
        while (pos < end) {
            int le = indexOfByte(buf, (byte) '\n', pos, end);
            int lineEnd = le < 0 ? end : le;
            int next = le < 0 ? end : le + 1;
            int contentEnd = lineEnd;
            if (contentEnd > pos && buf[contentEnd - 1] == '\r') contentEnd--;

            if (contentEnd - pos >= dash.length && startsWithAt(buf, pos, dash)) {
                int after = pos + dash.length;
                boolean closing = after + 2 <= contentEnd && buf[after] == '-' && buf[after + 1] == '-';
                int restStart = closing ? after + 2 : after;
                boolean ok = true;
                for (int k = restStart; k < contentEnd; k++) {
                    if (buf[k] != ' ' && buf[k] != '\t') {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    if (partStart >= 0) {
                        int partEnd = pos;
                        if (partEnd > partStart && buf[partEnd - 1] == '\n') partEnd--;
                        if (partEnd > partStart && buf[partEnd - 1] == '\r') partEnd--;
                        if (partEnd > partStart) parts.add(new int[]{partStart, partEnd});
                        if (parts.size() > MAX_PARTS) return parts;
                    }
                    if (closing) {
                        partStart = -1;
                        closed = true;
                        break;
                    }
                    partStart = next;
                }
            }
            pos = next;
        }
        if (!closed && partStart >= 0 && partStart < end) {
            parts.add(new int[]{partStart, end}); // thư cắt cụt: lấy phần cuối
        }
        return parts;
    }

    private static boolean startsWithAt(byte[] buf, int at, byte[] prefix) {
        if (at + prefix.length > buf.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (buf[at + i] != prefix[i]) return false;
        }
        return true;
    }

    private static Result walk(byte[] buf, int start, int end, Ctx ctx, int depth) {
        Result r = new Result();
        if (ctx.parts >= MAX_PARTS || depth > MAX_MIME_DEPTH) {
            ctx.truncated = true;
            return r;
        }
        ctx.parts++;

        Headers h = parseHeaders(buf, start, end);
        int bs = Math.min(h.bodyStart, end);
        Field ct = parseField(h.first("content-type"));
        Field cd = parseField(h.first("content-disposition"));
        String type = ct.value;
        if (type.isEmpty() || type.indexOf('/') <= 0) type = "text/plain";
        String cte = cteOf(h);
        String fileName = fileNameOf(cd, ct);
        boolean attachmentDisp = "attachment".equals(cd.value);

        if (type.startsWith("multipart/")) {
            String boundary = ct.params.get("boundary");
            if (boundary != null && !boundary.isEmpty()) {
                List<int[]> parts = splitMultipart(buf, bs, end, boundary);
                if (!parts.isEmpty()) {
                    List<Result> rs = new ArrayList<>(parts.size());
                    for (int[] p : parts) {
                        if (ctx.parts >= MAX_PARTS) {
                            ctx.truncated = true;
                            break;
                        }
                        rs.add(walk(buf, p[0], p[1], ctx, depth + 1));
                    }
                    return combine(rs, type.equals("multipart/alternative"));
                }
            }
            // multipart hỏng (không có boundary / không thấy phần nào): xử lý như văn bản thường
            type = "text/plain";
        }

        if (type.equals("message/rfc822") || type.equals("message/global")) {
            byte[] inner = decodeTransfer(buf, bs, end, cte, MAX_ATTACHMENT_BYTES);
            if (inner == null) {
                ctx.truncated = true;
                return r;
            }
            Headers ih = parseHeaders(inner, 0, inner.length);
            if (attachmentDisp || fileName != null) {
                String name = fileName;
                if (name == null) {
                    String subj = sanitizeFileName(decodeHeader(ih.first("subject")));
                    name = (subj == null ? "message" : subj) + ".eml";
                }
                if (ctx.attachmentBytes + inner.length > MAX_TOTAL_ATTACHMENT_BYTES) {
                    ctx.truncated = true;
                    return r;
                }
                addAttachment(ctx, inner, "message/rfc822", name);
                return r;
            }
            Result nested = walk(inner, 0, inner.length, ctx, depth + 1);
            StringBuilder sb = new StringBuilder("---------- Thư được chuyển tiếp ----------\n");
            appendIfNotEmpty(sb, "Từ: ", decodeHeader(ih.first("from")));
            appendIfNotEmpty(sb, "Đến: ", decodeHeader(ih.first("to")));
            appendIfNotEmpty(sb, "Chủ đề: ", decodeHeader(ih.first("subject")));
            appendIfNotEmpty(sb, "Ngày: ", decodeHeader(ih.first("date")));
            sb.append('\n').append(nested.text);
            r.text = cleanText(sb.toString());
            r.htmlOnly = false;
            return r;
        }

        boolean isTextBody = type.equals("text/plain") || type.equals("text/html");
        boolean asBody = isTextBody && (depth == 0 || (!attachmentDisp && fileName == null));
        if (asBody) {
            byte[] data = decodeTransfer(buf, bs, end, cte, Long.MAX_VALUE);
            if (data == null) data = new byte[0];
            if (data.length > MAX_TEXT_PART_BYTES) {
                data = Arrays.copyOf(data, MAX_TEXT_PART_BYTES);
                ctx.truncated = true;
            }
            String decoded = decodeBytes(data, ct.params.get("charset"));
            if (type.equals("text/html")) {
                r.text = htmlToText(decoded);
                r.htmlOnly = true;
            } else {
                r.text = cleanText(decoded);
            }
            return r;
        }

        // Còn lại: đính kèm
        long allowed = Math.min(MAX_ATTACHMENT_BYTES, MAX_TOTAL_ATTACHMENT_BYTES - ctx.attachmentBytes);
        if (allowed <= 0) {
            ctx.truncated = true;
            return r;
        }
        byte[] data = decodeTransfer(buf, bs, end, cte, allowed);
        if (data == null) {
            ctx.truncated = true;
            return r;
        }
        addAttachment(ctx, data, contentTypeOf(ct), fileName);
        return r;
    }

    private static void appendIfNotEmpty(StringBuilder sb, String label, String value) {
        if (value != null && !value.isEmpty()) sb.append(label).append(value).append('\n');
    }

    private static Result combine(List<Result> rs, boolean alternative) {
        Result out = new Result();
        if (alternative) {
            Result best = null;
            for (Result x : rs) {
                if (!x.text.isBlank() && !x.htmlOnly) best = x; // bản văn bản thuần cuối cùng
            }
            if (best == null) {
                for (Result x : rs) {
                    if (!x.text.isBlank()) best = x; // chỉ có HTML
                }
            }
            return best == null ? out : best;
        }
        StringBuilder sb = new StringBuilder();
        boolean allHtml = true;
        for (Result x : rs) {
            if (x.text.isBlank()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(x.text);
            if (!x.htmlOnly) allHtml = false;
        }
        out.text = sb.toString();
        out.htmlOnly = allHtml && sb.length() > 0;
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // API CHÍNH
    // ---------------------------------------------------------------------------------------------

    /**
     * Đọc thư. Không ném exception: thư lỗi định dạng trả bản tốt nhất có thể, thư rỗng/null trả ParsedMail rỗng.
     */
    public static ParsedMail parse(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return new ParsedMail("", "", "", "", "", List.of());
        }
        String from = "";
        String to = "";
        String subject = "";
        String date = "";
        try {
            Headers h = parseHeaders(raw, 0, raw.length);
            from = decodeHeader(h.first("from"));
            to = decodeHeader(h.first("to"));
            subject = decodeHeader(h.first("subject"));
            date = decodeHeader(h.first("date"));
        } catch (RuntimeException | StackOverflowError e) {
            // giữ các giá trị rỗng
        }
        try {
            Ctx ctx = new Ctx();
            Result r = walk(raw, 0, raw.length, ctx, 0);
            String text = stripTrailingBlank(r.text);
            if (text.length() > MAX_TEXT_CHARS) {
                text = text.substring(0, MAX_TEXT_CHARS);
                ctx.truncated = true;
            }
            if (ctx.truncated) {
                text = text.isEmpty() ? TRUNCATED_NOTE : text + "\n\n" + TRUNCATED_NOTE;
            }
            return new ParsedMail(from, to, subject, date, text, ctx.attachments);
        } catch (RuntimeException | StackOverflowError e) {
            return new ParsedMail(from, to, subject, date, "", List.of());
        }
    }
}