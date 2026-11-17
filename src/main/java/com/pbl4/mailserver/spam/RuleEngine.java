// FILE: src/main/java/com/pbl4/mailserver/spam/RuleEngine.java
package com.pbl4.mailserver.spam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Luật chấm điểm (điểm càng cao càng giống thư rác). Bảng trọng số:
 *
 *  Authentication-Results do CHÍNH server thêm (authserv-id == MAIL_HOSTNAME):
 *     spf=fail +2.5 | spf=softfail +1 | dmarc=fail +3 | dkim=none +0.5
 *  Thiếu From hợp lệ +1 | thiếu Message-ID +1 | Date thiếu/sai +1.5 | Date lệch > 2 ngày +1.5
 *  Reply-To khác domain From +1.5 | tên hiển thị chứa email domain khác +3
 *  Chủ đề toàn chữ hoa HOẶC có "!!!" +1
 *  Liên kết: > 10 link +1 | link rút gọn +1.5 | link là địa chỉ IP +2.5 | chữ hiển thị trỏ sai domain href +3
 *  Thư HTML chỉ có ảnh +1.5
 *  Đính kèm: đuôi thực thi +5 | đuôi kép (tài-liệu.pdf.exe) +4 (cộng thêm) | macro (docm/xlsm/pptm) +2
 *  Từ khóa (keywords.txt): cộng dồn, tối đa +6
 *  (Ngoài lớp này, SpamFilter cộng: liên hệ tin cậy -3, Bayes -4..+4)
 */
final class RuleEngine {
    private RuleEngine() {
    }

    static final double KEYWORD_CAP = 6.0;
    private static final long MAX_SKEW_MS = 2L * 24 * 3600 * 1000;

    private static final Set<String> EXEC = Set.of("exe", "scr", "js", "vbs", "bat", "cmd", "jar", "com", "lnk", "iso");
    private static final Set<String> MACRO = Set.of("docm", "xlsm", "pptm");
    private static final Set<String> DECOY = Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "jpg", "jpeg",
            "png", "gif", "txt", "rtf", "csv", "zip", "rar", "mp3", "mp4");

    // ---- lý do (tiếng Việt, không chứa nội dung thư) ----
    static final String R_SPF_FAIL = "SPF thất bại";
    static final String R_SPF_SOFT = "SPF không chắc chắn (softfail)";
    static final String R_DMARC = "DMARC thất bại";
    static final String R_DKIM = "Không có chữ ký DKIM";
    static final String R_NOFROM = "Thiếu người gửi hợp lệ";
    static final String R_NOMID = "Thiếu Message-ID";
    static final String R_DATE_BAD = "Ngày gửi thiếu hoặc không hợp lệ";
    static final String R_DATE_SKEW = "Ngày gửi lệch quá 2 ngày";
    static final String R_REPLYTO = "Reply-To khác domain người gửi";
    static final String R_SPOOF = "Tên hiển thị giả mạo domain khác";
    static final String R_CAPS = "Chủ đề viết hoa toàn bộ";
    static final String R_BANG = "Chủ đề có nhiều dấu chấm than";
    static final String R_MANYLINKS = "Quá nhiều liên kết";
    static final String R_SHORT = "Có liên kết rút gọn";
    static final String R_IPLINK = "Liên kết trỏ tới địa chỉ IP";
    static final String R_MISMATCH = "Liên kết hiển thị sai đích";
    static final String R_IMGONLY = "Thư HTML chỉ chứa hình ảnh";
    static final String R_EXE = "Đính kèm có thể thực thi";
    static final String R_DOUBLEEXT = "Đính kèm có đuôi kép đáng ngờ";
    static final String R_MACRO = "Đính kèm có macro";
    static final String R_KEYWORDS = "Chứa từ khóa thường gặp trong thư rác";

    static final class Deadline {
        private final long endNanos;

        Deadline(long millis) {
            this.endNanos = System.nanoTime() + millis * 1_000_000L;
        }

        boolean over() {
            return System.nanoTime() - endNanos > 0;
        }
    }

    static final class Signals {
        private record Sig(double pts, String reason) {
        }

        private double total;
        private final List<Sig> items = new ArrayList<>();

        /** reason == null: tín hiệu không đưa vào danh sách lý do. */
        void add(double pts, String reason) {
            total += pts;
            if (reason != null && pts > 0) items.add(new Sig(pts, reason));
        }

        double total() {
            return total;
        }

        List<String> top(int n) {
            List<Sig> copy = new ArrayList<>(items);
            copy.sort((a, b) -> Double.compare(b.pts(), a.pts()));
            Set<String> out = new LinkedHashSet<>();
            for (Sig s : copy) {
                if (out.size() >= n) break;
                out.add(s.reason());
            }
            return new ArrayList<>(out);
        }
    }

    static void apply(MailView v, Signals sg, Deadline dl) {
        auth(v, sg);
        headers(v, sg);
        links(v, sg);
        attachments(v, sg);
        imageOnly(v, sg);
        if (!dl.over()) keywords(v, sg);
    }

    private static void auth(MailView v, Signals sg) {
        if (!v.trustedAuth) return;
        if (v.spf.equals("fail")) sg.add(2.5, R_SPF_FAIL);
        else if (v.spf.equals("softfail")) sg.add(1.0, R_SPF_SOFT);
        if (v.dmarc.equals("fail")) sg.add(3.0, R_DMARC);
        if (v.dkim.equals("none")) sg.add(0.5, R_DKIM);
    }

    private static void headers(MailView v, Signals sg) {
        String fromAddr = SpamAddr.extract(v.from);
        String fromBase = fromAddr.isEmpty() ? "" : SpamAddr.baseDomain(SpamAddr.domainOf(fromAddr));
        if (fromAddr.isEmpty()) sg.add(1.0, R_NOFROM);
        if (!v.hasMessageId) sg.add(1.0, R_NOMID);

        Instant d = parseDate(v.date);
        if (d == null) sg.add(1.5, R_DATE_BAD);
        else if (Math.abs(SpamEnv.nowMillis() - d.toEpochMilli()) > MAX_SKEW_MS) sg.add(1.5, R_DATE_SKEW);

        if (!fromBase.isEmpty()) {
            if (!v.replyTo.isEmpty()) {
                String ra = SpamAddr.extract(v.replyTo);
                if (!ra.isEmpty() && !SpamAddr.baseDomain(SpamAddr.domainOf(ra)).equals(fromBase)) {
                    sg.add(1.5, R_REPLYTO);
                }
            }
            for (String dom : SpamAddr.domainsIn(v.displayName)) {
                if (!SpamAddr.baseDomain(dom).equals(fromBase)) {
                    sg.add(3.0, R_SPOOF);
                    break;
                }
            }
        }

        String sub = v.subject;
        int letters = 0;
        int lower = 0;
        for (int i = 0; i < sub.length() && i < 500; i++) {
            char c = sub.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isLowerCase(c)) lower++;
            }
        }
        if (letters >= 8 && lower == 0) sg.add(1.0, R_CAPS);
        else if (sub.contains("!!!")) sg.add(1.0, R_BANG);
    }

    static Instant parseDate(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || t.length() > 120) return null;
        StringBuilder sb = new StringBuilder(t.length());
        int depth = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                if (depth > 0) depth--;
            } else if (depth == 0) sb.append(c);
        }
        t = sb.toString().trim().replaceAll("\\s+", " ");
        if (t.endsWith(" UTC")) t = t.substring(0, t.length() - 4) + " GMT";
        else if (t.endsWith(" UT")) t = t.substring(0, t.length() - 3) + " GMT";
        try {
            return ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static void links(MailView v, Signals sg) {
        List<HtmlLinks.Link> ls = v.links;
        if (ls.size() > 10) sg.add(1.0, R_MANYLINKS);
        boolean sh = false;
        boolean ip = false;
        boolean mm = false;
        for (HtmlLinks.Link l : ls) {
            String host = HtmlLinks.host(l.href());
            if (host.isEmpty()) continue;
            if (!sh && (HtmlLinks.SHORTENERS.contains(host) || HtmlLinks.SHORTENERS.contains(SpamAddr.baseDomain(host)))) {
                sh = true;
            }
            if (!ip && HtmlLinks.isIpHost(host)) ip = true;
            if (!mm && !l.text().isEmpty()) {
                String th = HtmlLinks.textHost(l.text());
                if (th != null && !SpamAddr.baseDomain(th).equals(SpamAddr.baseDomain(host))) mm = true;
            }
        }
        if (sh) sg.add(1.5, R_SHORT);
        if (ip) sg.add(2.5, R_IPLINK);
        if (mm) sg.add(3.0, R_MISMATCH);
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private static void attachments(MailView v, Signals sg) {
        boolean exe = false;
        boolean dbl = false;
        boolean macro = false;
        for (String name : v.fileNames) {
            String n = name.toLowerCase(Locale.ROOT).trim();
            while (n.endsWith(".") || n.endsWith(" ")) n = n.substring(0, n.length() - 1);
            String e = ext(n);
            if (EXEC.contains(e)) {
                exe = true;
                String stem = n.substring(0, n.length() - e.length() - 1);
                if (DECOY.contains(ext(stem))) dbl = true;
            } else if (MACRO.contains(e)) {
                macro = true;
            }
        }
        if (exe) sg.add(5.0, R_EXE);
        if (dbl) sg.add(4.0, R_DOUBLEEXT);
        if (macro) sg.add(2.0, R_MACRO);
    }

    private static void imageOnly(MailView v, Signals sg) {
        if (!v.html.isEmpty() && HtmlLinks.countIgnoreCase(v.html, "<img") > 0 && v.text.trim().length() < 60) {
            sg.add(1.5, R_IMGONLY);
        }
    }

    // ------------------------------------------------------------------ từ khóa

    private static final class Keywords {
        final String[] phrases;
        final double[] weights;

        Keywords(String[] p, double[] w) {
            this.phrases = p;
            this.weights = w;
        }

        static Keywords load() {
            List<String> ps = new ArrayList<>();
            List<Double> ws = new ArrayList<>();
            try (InputStream in = RuleEngine.class.getResourceAsStream("/spam/keywords.txt")) {
                if (in != null) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    String line;
                    int n = 0;
                    while ((line = r.readLine()) != null && n++ < 5000) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        int bar = line.indexOf('|');
                        if (bar <= 0) continue;
                        try {
                            double w = Double.parseDouble(line.substring(0, bar).trim());
                            String p = fold(line.substring(bar + 1).trim());
                            if (!p.isEmpty() && w > 0 && w <= 5) {
                                ps.add(p);
                                ws.add(w);
                            }
                        } catch (NumberFormatException ignored) {
                            // bỏ dòng hỏng
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // không có từ khóa: vẫn chạy các luật khác
            }
            double[] w = new double[ws.size()];
            for (int i = 0; i < w.length; i++) w[i] = ws.get(i);
            return new Keywords(ps.toArray(new String[0]), w);
        }
    }

    private static volatile Keywords kw;

    private static Keywords keywords() {
        Keywords k = kw;
        if (k == null) {
            synchronized (RuleEngine.class) {
                k = kw;
                if (k == null) {
                    k = Keywords.load();
                    kw = k;
                }
            }
        }
        return k;
    }

    static int keywordCount() {
        return keywords().phrases.length;
    }

    private static void keywords(MailView v, Signals sg) {
        Keywords k = keywords();
        if (k.phrases.length == 0) return;
        String hay = fold(v.subject + "\n" + v.text);
        double sum = 0;
        for (int i = 0; i < k.phrases.length; i++) {
            if (containsWord(hay, k.phrases[i])) sum += k.weights[i];
        }
        if (sum > 0) sg.add(Math.min(sum, KEYWORD_CAP), sum >= 1.5 ? R_KEYWORDS : null);
    }

    private static boolean containsWord(String hay, String phrase) {
        int from = 0;
        int plen = phrase.length();
        while (true) {
            int i = hay.indexOf(phrase, from);
            if (i < 0) return false;
            boolean before = i == 0 || !Character.isLetterOrDigit(hay.charAt(i - 1));
            int e = i + plen;
            boolean after = e >= hay.length() || !Character.isLetterOrDigit(hay.charAt(e));
            if (before && after) return true;
            from = i + 1;
        }
    }

    /** Chữ thường, bỏ dấu tiếng Việt (đ -> d), gộp khoảng trắng. Dùng cho từ khóa và khóa nội dung thư. */
    static String fold(String s) {
        if (s == null || s.isEmpty()) return "";
        String d = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(d.length());
        boolean lastSpace = true;
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            if (c == '\u0111') c = 'd';
            if (Character.isWhitespace(c) || c == '\u00a0') {
                if (!lastSpace) sb.append(' ');
                lastSpace = true;
            } else {
                sb.append(c);
                lastSpace = false;
            }
        }
        int e = sb.length();
        if (e > 0 && sb.charAt(e - 1) == ' ') sb.setLength(e - 1);
        return sb.toString();
    }
}