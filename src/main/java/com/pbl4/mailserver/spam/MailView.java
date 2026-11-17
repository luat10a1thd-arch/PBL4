// FILE: src/main/java/com/pbl4/mailserver/spam/MailView.java
package com.pbl4.mailserver.spam;

import com.pbl4.mailserver.mime.MimeParser;
import com.pbl4.mailserver.mime.ParsedMail;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bản phân tích nhẹ của một thư để chấm điểm. Chỉ nhìn tối đa 256 KB đầu của thư.
 * Dùng MimeParser (chỉ GỌI) để lấy from/subject/date/text; tự quét cấu trúc MIME để lấy HTML thô và tên file đính kèm.
 */
final class MailView {
    static final int MAX_BYTES = 256 * 1024;
    static final int MAX_TEXT_CHARS = 64 * 1024;
    private static final int MAX_HTML_CHARS = 600_000;
    private static final int MAX_BODY_CHARS = 700_000;

    final String from;
    final String subject;
    final String text;
    final String date;
    final String replyTo;
    final boolean hasMessageId;
    final String displayName;
    final String html;
    final List<String> fileNames;
    final int imageParts;
    final List<HtmlLinks.Link> links;
    /** Có header Authentication-Results do CHÍNH server thêm (authserv-id == MAIL_HOSTNAME). */
    final boolean trustedAuth;
    final String spf;
    final String dkim;
    final String dmarc;

    static MailView of(byte[] raw) {
        byte[] head = raw.length > MAX_BYTES ? Arrays.copyOf(raw, MAX_BYTES) : raw;
        String msg = new String(head, StandardCharsets.ISO_8859_1);
        ParsedMail pm;
        try {
            pm = MimeParser.parse(head);
        } catch (Throwable t) {
            pm = new ParsedMail("", "", "", "", "", List.of());
        }
        Hdrs top = Hdrs.parse(msg, 0, msg.length());
        Scan scan = new Scan();
        try {
            scan.walk(msg, 0, msg.length(), 0);
        } catch (RuntimeException | StackOverflowError e) {
            // bỏ qua: chỉ mất phần phân tích đính kèm/HTML
        }
        return new MailView(pm, top, scan);
    }

    private MailView(ParsedMail pm, Hdrs top, Scan scan) {
        this.from = pm.from();
        this.subject = pm.subject();
        String t = pm.text();
        this.text = t.length() > MAX_TEXT_CHARS ? t.substring(0, MAX_TEXT_CHARS) : t;
        this.date = pm.date();
        String rt = top.first("reply-to");
        this.replyTo = rt == null ? "" : rt;
        String mid = top.first("message-id");
        this.hasMessageId = mid != null && !mid.isBlank();
        this.displayName = SpamAddr.displayName(this.from);
        this.html = scan.html.toString();

        List<String> names = new ArrayList<>();
        for (String n : scan.fileNames) {
            String fixed = fixUtf8(n);
            try {
                fixed = MimeParser.decodeHeader(fixed);
            } catch (RuntimeException ignored) {
                // giữ nguyên
            }
            if (fixed != null && !fixed.isBlank()) names.add(fixed);
        }
        this.fileNames = List.copyOf(names);
        this.imageParts = scan.imageParts;

        List<HtmlLinks.Link> ls = new ArrayList<>();
        if (!html.isEmpty()) {
            for (HtmlLinks.Link l : HtmlLinks.anchors(html, 300)) {
                if (HtmlLinks.isWebUrl(l.href())) ls.add(l);
            }
        }
        if (ls.isEmpty()) {
            for (String u : HtmlLinks.textUrls(text, 300)) ls.add(new HtmlLinks.Link(u, ""));
        }
        this.links = List.copyOf(ls);

        // Chỉ tin Authentication-Results có authserv-id == hostname của chính server.
        String host = SpamEnv.hostname() == null ? "" : SpamEnv.hostname().trim().toLowerCase(Locale.ROOT);
        boolean trusted = false;
        String s = "";
        String k = "";
        String d = "";
        if (!host.isEmpty()) {
            for (String ar : top.all("authentication-results")) {
                int semi = ar.indexOf(';');
                String id = (semi < 0 ? ar : ar.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
                int sp = id.indexOf(' ');
                if (sp > 0) id = id.substring(0, sp);
                if (id.equals(host)) {
                    trusted = true;
                    String low = ar.toLowerCase(Locale.ROOT);
                    s = method(low, "spf");
                    k = method(low, "dkim");
                    d = method(low, "dmarc");
                    break;
                }
            }
        }
        this.trustedAuth = trusted;
        this.spf = s;
        this.dkim = k;
        this.dmarc = d;
    }

    /** Kết quả của một phương thức xác thực trong Authentication-Results, ví dụ "fail". */
    private static String method(String low, String key) {
        String k = key + "=";
        int from = 0;
        while (true) {
            int i = low.indexOf(k, from);
            if (i < 0) return "";
            from = i + k.length();
            if (i > 0) {
                char p = low.charAt(i - 1);
                if (Character.isLetterOrDigit(p) || p == '.' || p == '-' || p == '_') continue;
            }
            int e = from;
            while (e < low.length() && Character.isLetter(low.charAt(e))) e++;
            return low.substring(from, e);
        }
    }

    static String fixUtf8(String s) {
        if (s == null) return null;
        boolean hi = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0xFF) return s;
            if (c > 0x7F) hi = true;
        }
        if (!hi) return s;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1))).toString();
        } catch (CharacterCodingException e) {
            return s;
        }
    }

    // ------------------------------------------------------------------ header thô

    static final class Hdrs {
        final Map<String, List<String>> map = new HashMap<>();
        int bodyStart;
        private int fields;

        String first(String name) {
            List<String> l = map.get(name);
            return (l == null || l.isEmpty()) ? null : l.get(0);
        }

        List<String> all(String name) {
            List<String> l = map.get(name);
            return l == null ? List.of() : l;
        }

        private void put(String name, String value) {
            if (fields >= 400) return;
            List<String> l = map.computeIfAbsent(name, k -> new ArrayList<>(1));
            if (l.size() < 20) {
                l.add(value.trim());
                fields++;
            }
        }

        static Hdrs parse(String s, int start, int end) {
            Hdrs h = new Hdrs();
            int pos = start;
            String curName = null;
            StringBuilder cur = null;
            while (pos < end) {
                int nl = s.indexOf('\n', pos);
                int lineEnd;
                int next;
                if (nl < 0 || nl >= end) {
                    lineEnd = end;
                    next = end;
                } else {
                    lineEnd = nl;
                    next = nl + 1;
                }
                int ce = lineEnd;
                if (ce > pos && s.charAt(ce - 1) == '\r') ce--;

                if (ce == pos) {
                    if (curName != null) h.put(curName, cur.toString());
                    h.bodyStart = next;
                    return h;
                }
                if (pos - start > 128 * 1024) {
                    if (curName != null) h.put(curName, cur.toString());
                    h.bodyStart = end;
                    return h;
                }
                if (pos == start && s.startsWith("From ", pos)) {
                    pos = next;
                    continue;
                }
                char c0 = s.charAt(pos);
                if (c0 == ' ' || c0 == '\t') {
                    if (cur != null && cur.length() < 8192) cur.append(' ').append(s, pos, ce);
                } else {
                    int colon = s.indexOf(':', pos);
                    String name = (colon < 0 || colon >= ce || colon == pos) ? ""
                            : s.substring(pos, colon).trim().toLowerCase(Locale.ROOT);
                    if (name.isEmpty() || name.indexOf(' ') >= 0 || name.indexOf('\t') >= 0) {
                        if (curName != null) h.put(curName, cur.toString());
                        h.bodyStart = pos;
                        return h;
                    }
                    if (curName != null) h.put(curName, cur.toString());
                    curName = name;
                    cur = new StringBuilder();
                    cur.append(s, colon + 1, Math.min(ce, colon + 1 + 8192));
                }
                pos = next;
            }
            if (curName != null) h.put(curName, cur.toString());
            h.bodyStart = end;
            return h;
        }
    }

    // ------------------------------------------------------------------ quét cấu trúc MIME

    static final class Scan {
        final StringBuilder html = new StringBuilder();
        final List<String> fileNames = new ArrayList<>();
        int imageParts;
        private int parts;

        void walk(String s, int start, int end, int depth) {
            if (++parts > 150 || depth > 6) return;
            Hdrs h = Hdrs.parse(s, start, end);
            int bs = Math.min(h.bodyStart, end);
            String ct = h.first("content-type");
            String ctype = ct == null ? "text/plain" : ct;
            int semi = ctype.indexOf(';');
            if (semi >= 0) ctype = ctype.substring(0, semi);
            ctype = ctype.trim().toLowerCase(Locale.ROOT);
            String cd = h.first("content-disposition");
            String cte = h.first("content-transfer-encoding");
            cte = cte == null ? "" : cte.trim().toLowerCase(Locale.ROOT);

            String fn = null;
            if (cd != null) fn = param(cd, "filename");
            if (fn == null && ct != null) fn = param(ct, "name");

            if (ctype.startsWith("multipart/")) {
                String b = ct == null ? null : param(ct, "boundary");
                if (b != null && !b.isEmpty() && b.length() <= 200) {
                    for (int[] r : splitParts(s, bs, end, b)) {
                        if (parts > 150) break;
                        walk(s, r[0], r[1], depth + 1);
                    }
                }
                return;
            }
            if (fn != null && !fn.isBlank() && fileNames.size() < 100) fileNames.add(fn);
            if (ctype.equals("message/rfc822")) {
                walk(s, bs, end, depth + 1);
                return;
            }
            if (ctype.startsWith("image/")) {
                imageParts++;
            } else if (ctype.equals("text/html") && html.length() < MAX_HTML_CHARS) {
                byte[] data = decodeBody(s, bs, end, cte);
                html.append(new String(data, charsetOf(ct)));
            }
        }

        private static Charset charsetOf(String ct) {
            String cs = ct == null ? null : param(ct, "charset");
            if (cs == null) return StandardCharsets.UTF_8;
            try {
                return Charset.forName(cs.trim());
            } catch (RuntimeException e) {
                return StandardCharsets.UTF_8;
            }
        }

        private static List<int[]> splitParts(String s, int start, int end, String boundary) {
            List<int[]> ranges = new ArrayList<>();
            String delim = "--" + boundary;
            int partStart = -1;
            int pos = s.indexOf(delim, start);
            while (pos >= 0 && pos < end) {
                boolean lineStart = pos == 0 || s.charAt(pos - 1) == '\n';
                if (!lineStart) {
                    pos = s.indexOf(delim, pos + delim.length());
                    continue;
                }
                int after = pos + delim.length();
                boolean closing = s.startsWith("--", after);
                int lineEnd = s.indexOf('\n', after);
                if (lineEnd < 0 || lineEnd > end) lineEnd = end;
                if (partStart >= 0) {
                    int pe = pos;
                    if (pe > partStart && s.charAt(pe - 1) == '\n') pe--;
                    if (pe > partStart && s.charAt(pe - 1) == '\r') pe--;
                    ranges.add(new int[]{partStart, pe});
                    if (ranges.size() >= 100) return ranges;
                }
                if (closing) {
                    partStart = -1;
                    break;
                }
                partStart = Math.min(lineEnd + 1, end);
                pos = s.indexOf(delim, partStart);
            }
            if (partStart >= 0 && partStart < end) ranges.add(new int[]{partStart, end});
            return ranges;
        }

        private static byte[] decodeBody(String s, int from, int to, String cte) {
            int len = Math.min(to - from, MAX_BODY_CHARS);
            if (len <= 0) return new byte[0];
            String body = s.substring(from, from + len);
            if (cte.startsWith("base64")) return b64(body);
            if (cte.startsWith("quoted-printable")) return qp(body);
            return body.getBytes(StandardCharsets.ISO_8859_1);
        }

        private static byte[] b64(String body) {
            String t = body;
            for (int k = 0; k < 4; k++) {
                try {
                    return Base64.getMimeDecoder().decode(t);
                } catch (IllegalArgumentException e) {
                    if (t.isEmpty()) break;
                    t = t.substring(0, t.length() - 1);
                }
            }
            return new byte[0];
        }

        private static int hex(char c) {
            if (c >= '0' && c <= '9') return c - '0';
            if (c >= 'A' && c <= 'F') return c - 'A' + 10;
            if (c >= 'a' && c <= 'f') return c - 'a' + 10;
            return -1;
        }

        private static byte[] qp(String b) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(b.length());
            int n = b.length();
            int i = 0;
            while (i < n) {
                char c = b.charAt(i);
                if (c == '=') {
                    if (i + 1 < n && b.charAt(i + 1) == '\n') {
                        i += 2;
                        continue;
                    }
                    if (i + 2 < n && b.charAt(i + 1) == '\r' && b.charAt(i + 2) == '\n') {
                        i += 3;
                        continue;
                    }
                    if (i + 2 < n && hex(b.charAt(i + 1)) >= 0 && hex(b.charAt(i + 2)) >= 0) {
                        out.write((hex(b.charAt(i + 1)) << 4) | hex(b.charAt(i + 2)));
                        i += 3;
                        continue;
                    }
                }
                out.write(c & 0xFF);
                i++;
            }
            return out.toByteArray();
        }

        /** Giá trị tham số (filename, name, boundary, charset...) của một header; hỗ trợ filename*=UTF-8''... */
        static String param(String v, String key) {
            if (v == null) return null;
            String lv = v.toLowerCase(Locale.ROOT);
            int from = 0;
            while (true) {
                int i = lv.indexOf(key, from);
                if (i < 0) return null;
                from = i + key.length();
                if (i > 0) {
                    char p = lv.charAt(i - 1);
                    if (p != ';' && p != ' ' && p != '\t') continue;
                }
                int j = from;
                boolean ext = false;
                if (j < lv.length() && lv.charAt(j) == '*') {
                    ext = true;
                    j++;
                    while (j < lv.length() && (Character.isDigit(lv.charAt(j)) || lv.charAt(j) == '*')) j++;
                }
                while (j < lv.length() && lv.charAt(j) == ' ') j++;
                if (j >= lv.length() || lv.charAt(j) != '=') continue;
                j++;
                while (j < v.length() && v.charAt(j) == ' ') j++;
                String val;
                if (j < v.length() && v.charAt(j) == '"') {
                    int e = v.indexOf('"', j + 1);
                    val = v.substring(j + 1, e < 0 ? v.length() : e);
                } else {
                    int e = j;
                    while (e < v.length() && v.charAt(e) != ';' && v.charAt(e) != ' ') e++;
                    val = v.substring(j, e);
                }
                return ext ? decodeExt(val) : val;
            }
        }

        private static String decodeExt(String val) {
            int q1 = val.indexOf('\'');
            int q2 = q1 < 0 ? -1 : val.indexOf('\'', q1 + 1);
            String cs = "UTF-8";
            String enc = val;
            if (q2 > 0) {
                cs = val.substring(0, q1);
                enc = val.substring(q2 + 1);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < enc.length(); i++) {
                char c = enc.charAt(i);
                if (c == '%' && i + 2 < enc.length() && hex(enc.charAt(i + 1)) >= 0 && hex(enc.charAt(i + 2)) >= 0) {
                    out.write((hex(enc.charAt(i + 1)) << 4) | hex(enc.charAt(i + 2)));
                    i += 2;
                } else {
                    byte[] cb = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                    out.write(cb, 0, cb.length);
                }
            }
            Charset ch;
            try {
                ch = cs.isEmpty() ? StandardCharsets.UTF_8 : Charset.forName(cs);
            } catch (RuntimeException e) {
                ch = StandardCharsets.UTF_8;
            }
            return new String(out.toByteArray(), ch);
        }
    }
}