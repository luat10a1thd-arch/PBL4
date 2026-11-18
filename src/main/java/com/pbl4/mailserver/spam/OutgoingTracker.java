// FILE: src/main/java/com/pbl4/mailserver/spam/OutgoingTracker.java
package com.pbl4.mailserver.spam;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Cửa sổ trượt TRONG BỘ NHỚ theo dõi thư nội bộ gửi đi: khóa (băm người gửi, băm nội dung) ->
 * {thời điểm đầu, tập băm người nhận, số người nhận của lần gửi lớn nhất}.
 * Hết hạn sau 10 phút kể từ lần đầu; tối đa 20.000 mục (đầy thì bỏ mục cũ nhất); mỗi mục giữ tối đa 12 băm người nhận.
 * Không lưu tên, địa chỉ hay nội dung thư: chỉ băm.
 */
final class OutgoingTracker {
    private OutgoingTracker() {
    }

    static final long WINDOW_MS = 10L * 60 * 1000;
    static final int MAX_ENTRIES = 20_000;
    static final int MAX_RCPT = 12;
    /** Thân thư chuẩn hóa ngắn hơn mức này thì không tính tín hiệu "cùng nội dung" (tránh trùng "ok", "cảm ơn"...). */
    static final int MIN_BODY = 20;
    static final int MAX_BODY_CHARS = 20 * 1024;

    record Key(long sender, long c1, long c2) {
    }

    record Content(long h1, long h2, boolean substantial) {
    }

    record Info(int distinct, int biggest) {
    }

    private static final class Entry {
        final long first;
        final HashSet<Long> rcpts = new HashSet<>();
        int biggest;

        Entry(long first) {
            this.first = first;
        }
    }

    // Thứ tự chèn = thứ tự thời điểm đầu => mục cũ nhất luôn ở đầu.
    private static final LinkedHashMap<Key, Entry> MAP = new LinkedHashMap<>(1024, 0.75f, false);

    /** Ghi một lần gửi: rcptIds = băm người nhận (đã bỏ chính người gửi), batch = số người nhận nội bộ của lần gửi này. */
    static synchronized void note(Key k, long now, Collection<Long> rcptIds, int batch) {
        purge(now);
        Entry e = MAP.get(k);
        if (e != null && now - e.first >= WINDOW_MS) {
            MAP.remove(k);
            e = null;
        }
        if (e == null) {
            e = new Entry(now);
            MAP.put(k, e);
            while (MAP.size() > MAX_ENTRIES) {
                Iterator<Key> it = MAP.keySet().iterator();
                it.next();
                it.remove();
            }
        }
        for (Long id : rcptIds) {
            if (e.rcpts.size() >= MAX_RCPT) break;
            e.rcpts.add(id);
        }
        if (batch > e.biggest) e.biggest = batch;
    }

    /** null nếu không có mục còn hạn. */
    static synchronized Info lookup(Key k, long now) {
        Entry e = MAP.get(k);
        if (e == null) return null;
        if (now - e.first >= WINDOW_MS) {
            MAP.remove(k);
            return null;
        }
        return new Info(e.rcpts.size(), e.biggest);
    }

    private static void purge(long now) {
        Iterator<Map.Entry<Key, Entry>> it = MAP.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().first >= WINDOW_MS) it.remove();
            else break;
        }
    }

    static synchronized int size() {
        return MAP.size();
    }

    static synchronized void clear() {
        MAP.clear();
    }

    // ------------------------------------------------------------------ băm nội dung

    /**
     * SHA-256 của thân thư đã chuẩn hóa: chữ thường, gộp khoảng trắng, bỏ thẻ HTML, chỉ lấy 20 KB đầu.
     * Thân quá ngắn thì băm kèm chủ đề (và substantial=false) để các thư ngắn khác nhau không bị gộp.
     */
    static Content content(MailView v) {
        String body = v.text == null ? "" : v.text;
        if (body.isBlank() && v.html != null && !v.html.isEmpty()) {
            body = HtmlLinks.stripTags(cut(v.html, 100_000));
        } else if (looksHtml(body)) {
            body = HtmlLinks.stripTags(cut(body, 100_000));
        }
        String norm = norm(body, MAX_BODY_CHARS);
        boolean substantial = norm.length() >= MIN_BODY;
        String basis = substantial ? norm : norm + "\u0001" + norm(v.subject, 200);
        byte[] d = SpamHash.sha256(basis.getBytes(StandardCharsets.UTF_8));
        return new Content(SpamHash.toLong(d, 0), SpamHash.toLong(d, 8), substantial);
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static boolean looksHtml(String s) {
        if (s.indexOf('<') < 0) return false;
        String low = cut(s, 4000).toLowerCase(Locale.ROOT);
        return low.contains("</") || low.contains("<br") || low.contains("<p>") || low.contains("<div");
    }

    private static String norm(String s, int max) {
        if (s == null || s.isEmpty()) return "";
        int n = Math.min(s.length(), max * 4);
        StringBuilder sb = new StringBuilder(Math.min(max, n));
        boolean lastSpace = true;
        for (int i = 0; i < n && sb.length() < max; i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '\u00a0') {
                if (!lastSpace) sb.append(' ');
                lastSpace = true;
            } else {
                sb.append(Character.toLowerCase(c));
                lastSpace = false;
            }
        }
        int e = sb.length();
        if (e > 0 && sb.charAt(e - 1) == ' ') sb.setLength(e - 1);
        return sb.toString();
    }
}