package com.pbl4.mailserver.auth;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phần dùng chung của DkimSigner / DkimVerifier (RFC 6376): tách header, canonicalization, tag-list.
 * Header được đọc bằng ISO-8859-1 để ánh xạ 1-1 giữa byte và ký tự (không làm hỏng byte nào).
 */
final class DkimCommon {

    static final Charset L1 = StandardCharsets.ISO_8859_1;

    private DkimCommon() {}

    /** Một trường header: raw = toàn bộ trường (kể cả các dòng gấp CRLF+WSP), không gồm CRLF cuối. */
    record Hdr(String name, String raw) {
        String lc() {
            return name.toLowerCase(Locale.ROOT);
        }
    }

    /** headerBytes: số byte của phần header (đến hết dòng trống/hết dữ liệu); bodyStart: vị trí bắt đầu thân thư. */
    record Parsed(List<Hdr> headers, int bodyStart, int headerBytes) {}

    // ------------------------------------------------------------ tách header

    static Parsed parse(byte[] raw) {
        List<Hdr> list = new ArrayList<>();
        int n = raw.length;
        int pos = 0;
        int bodyStart = n;
        int headerBytes = n;
        StringBuilder cur = null;
        while (pos < n) {
            int e = indexOfCrlf(raw, pos);
            int lineEnd = e < 0 ? n : e;
            int next = e < 0 ? n : e + 2;
            if (lineEnd == pos) { // dòng trống: hết header
                bodyStart = next;
                headerBytes = pos;
                break;
            }
            String line = new String(raw, pos, lineEnd - pos, L1);
            char c0 = line.charAt(0);
            if ((c0 == ' ' || c0 == '\t') && cur != null) {
                cur.append("\r\n").append(line);
            } else {
                flush(list, cur);
                cur = new StringBuilder(line);
            }
            pos = next;
        }
        flush(list, cur);
        return new Parsed(list, bodyStart, headerBytes);
    }

    private static void flush(List<Hdr> list, StringBuilder cur) {
        if (cur == null) {
            return;
        }
        String s = cur.toString();
        int colon = s.indexOf(':');
        if (colon <= 0) {
            return; // dòng không phải header hợp lệ: bỏ qua
        }
        list.add(new Hdr(s.substring(0, colon).trim(), s));
    }

    static int indexOfCrlf(byte[] a, int from) {
        for (int i = from; i + 1 < a.length; i++) {
            if (a[i] == '\r' && a[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    static int countHeaders(List<Hdr> headers, String lcName) {
        int c = 0;
        for (Hdr h : headers) {
            if (h.lc().equals(lcName)) {
                c++;
            }
        }
        return c;
    }

    /**
     * Chọn header theo danh sách h=: lần xuất hiện thứ k của một tên trong h= ứng với trường thứ k tính từ DƯỚI lên.
     * Tên không còn trường tương ứng thì bỏ qua (không đóng góp gì vào hash) - đây chính là cơ chế "oversign".
     */
    static List<Hdr> selectHeaders(List<Hdr> all, List<String> names) {
        Map<String, Integer> used = new HashMap<>();
        List<Hdr> out = new ArrayList<>();
        for (String nm : names) {
            String k = nm.toLowerCase(Locale.ROOT);
            int u = used.merge(k, 1, Integer::sum);
            int seen = 0;
            Hdr pick = null;
            for (int i = all.size() - 1; i >= 0; i--) {
                if (all.get(i).lc().equals(k) && ++seen == u) {
                    pick = all.get(i);
                    break;
                }
            }
            if (pick != null) {
                out.add(pick);
            }
        }
        return out;
    }

    // ------------------------------------------------------ canonicalization

    /** Relaxed header (RFC 6376 3.4.2): tên chữ thường, bỏ gấp dòng, gộp WSP, bỏ WSP quanh dấu ':' và cuối giá trị. Không có CRLF cuối. */
    static String canonHeaderRelaxed(String raw) {
        int c = raw.indexOf(':');
        String name = raw.substring(0, c).trim().toLowerCase(Locale.ROOT);
        String v = raw.substring(c + 1).replace("\r\n", "");
        StringBuilder sb = new StringBuilder(v.length());
        boolean ws = false;
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == ' ' || ch == '\t') {
                ws = true;
            } else {
                if (ws && sb.length() > 0) {
                    sb.append(' ');
                }
                ws = false;
                sb.append(ch);
            }
        }
        return name + ":" + sb;
    }

    /** Dữ liệu header đưa vào hash: mỗi header đã canonical + CRLF. */
    static byte[] headerHashInput(List<Hdr> selected, boolean relaxed) {
        StringBuilder sb = new StringBuilder();
        for (Hdr h : selected) {
            sb.append(relaxed ? canonHeaderRelaxed(h.raw()) : h.raw()).append("\r\n");
        }
        return sb.toString().getBytes(L1);
    }

    /**
     * Canonicalization thân thư. Chỉ tách dòng theo CRLF.
     * - relaxed: bỏ WSP cuối dòng, gộp WSP trong dòng; thân rỗng => rỗng.
     * - simple: giữ nguyên; thân rỗng => "\r\n".
     * Cả hai: bỏ mọi dòng trống ở cuối; nếu còn nội dung thì bảo đảm kết thúc bằng CRLF.
     */
    static byte[] canonBody(byte[] raw, int start, boolean relaxed) {
        int n = raw.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, n - start));
        int pos = start;
        int pendingEmpty = 0;
        boolean any = false;
        while (pos < n) {
            int e = indexOfCrlf(raw, pos);
            int lineEnd = e < 0 ? n : e;
            int next = e < 0 ? n : e + 2;
            byte[] relaxedLine = null;
            int len;
            if (relaxed) {
                relaxedLine = relaxLine(raw, pos, lineEnd);
                len = relaxedLine.length;
            } else {
                len = lineEnd - pos;
            }
            if (len == 0) {
                pendingEmpty++;
            } else {
                for (; pendingEmpty > 0; pendingEmpty--) {
                    out.write('\r');
                    out.write('\n');
                }
                if (relaxed) {
                    out.write(relaxedLine, 0, len);
                } else {
                    out.write(raw, pos, len);
                }
                out.write('\r');
                out.write('\n');
                any = true;
            }
            pos = next;
        }
        if (!any && !relaxed) {
            out.write('\r');
            out.write('\n');
        }
        return out.toByteArray();
    }

    private static byte[] relaxLine(byte[] raw, int s, int e) {
        byte[] buf = new byte[e - s];
        int n = 0;
        boolean ws = false;
        for (int i = s; i < e; i++) {
            byte b = raw[i];
            if (b == ' ' || b == '\t') {
                ws = true;
            } else {
                if (ws) {
                    buf[n++] = ' ';
                    ws = false;
                }
                buf[n++] = b;
            }
        }
        return Arrays.copyOf(buf, n);
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------ tag-list

    /** Phân tích "tag=value; tag=value". Ném IllegalArgumentException nếu sai cú pháp hoặc trùng thẻ. */
    static Map<String, String> parseTags(String value) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (String seg : value.split(";", -1)) {
            String s = seg.trim();
            if (s.isEmpty()) {
                continue;
            }
            int eq = s.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("thẻ không hợp lệ: " + abbreviate(s));
            }
            String name = s.substring(0, eq).trim();
            String val = s.substring(eq + 1).trim();
            if (tags.put(name, val) != null) {
                throw new IllegalArgumentException("thẻ bị trùng: " + name);
            }
        }
        return tags;
    }

    /** Xóa mọi khoảng trắng (dùng cho giá trị b=, bh=, p=). */
    static String stripWs(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Giữ nguyên trường DKIM-Signature nhưng xóa giá trị của thẻ b= (để tính hash chữ ký). */
    static String stripBValue(String rawHeader) {
        int c = rawHeader.indexOf(':');
        String head = rawHeader.substring(0, c + 1);
        String v = rawHeader.substring(c + 1);
        StringBuilder out = new StringBuilder(rawHeader.length());
        int i = 0;
        while (true) {
            int semi = v.indexOf(';', i);
            int end = semi < 0 ? v.length() : semi;
            String seg = v.substring(i, end);
            int eq = seg.indexOf('=');
            if (eq >= 0 && seg.substring(0, eq).trim().equals("b")) {
                out.append(seg, 0, eq + 1);
            } else {
                out.append(seg);
            }
            if (semi < 0) {
                break;
            }
            out.append(';');
            i = semi + 1;
        }
        return head + out;
    }

    static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }
}
