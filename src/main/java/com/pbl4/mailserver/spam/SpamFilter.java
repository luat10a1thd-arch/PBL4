// FILE: src/main/java/com/pbl4/mailserver/spam/SpamFilter.java
package com.pbl4.mailserver.spam;

import com.pbl4.mailserver.spam.RuleEngine.Deadline;
import com.pbl4.mailserver.spam.RuleEngine.Signals;
import com.pbl4.mailserver.spam.SpamStore.UserData;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Bộ lọc thư rác. Mọi hàm xử lý thư KHÔNG ném lỗi: lỗi hoặc hết thời gian => coi như không phải rác.
 * Chỉ phân tích 256 KB đầu của thư, không dùng mạng, ngân sách xử lý ~400 ms (thực tế vài chục ms).
 */
public final class SpamFilter {
    private SpamFilter() {
    }

    private static final SpamVerdict HAM0 = new SpamVerdict(0.0, false, List.of());
    private static final long BUDGET_MS = 400;
    private static final String R_BAYES = "Nội dung giống thư rác đã học";

    /** Cho phép Lead nối kiểm tra "user có thật" (mặc định gọi core.UserStore.exists bằng reflection). */
    public static void setUserExistsChecker(Predicate<String> checker) {
        SpamEnv.setUserExistsHook(checker);
    }

    // ------------------------------------------------------------------ phân loại

    public static SpamVerdict classify(String user, byte[] raw, String envelopeFrom) {
        SpamVerdict v;
        try {
            v = doClassify(user, raw, envelopeFrom);
        } catch (Throwable t) {
            v = HAM0;
        }
        try {
            SpamStore.count(v.spam());
        } catch (Throwable ignored) {
            // bộ đếm không được làm hỏng việc giao thư
        }
        return v;
    }

    private static SpamVerdict doClassify(String user, byte[] raw, String envelopeFrom) {
        if (raw == null || raw.length == 0) return HAM0;
        UserData ud = SpamStore.get(user);
        if (ud == null) return HAM0;
        SpamSettings st = ud.settings();
        if (!st.enabled()) return HAM0;

        Deadline dl = new Deadline(BUDGET_MS);
        MailView v = MailView.of(raw);
        String env = SpamAddr.extract(envelopeFrom);
        String fromAddr = SpamAddr.extract(v.from);

        // Thư nội bộ (envelope thuộc MAIL_DOMAIN và là user có thật) => không phải rác.
        // Nếu thư có Authentication-Results do server thêm thì nó đến từ đường MX (bên ngoài) => KHÔNG miễn trừ,
        // vì người ngoài có thể giả envelope-from nội bộ.
        if (!env.isEmpty() && !v.trustedAuth
                && SpamAddr.domainOf(env).equalsIgnoreCase(SpamEnv.domain())
                && SpamEnv.userExists(SpamAddr.localOf(env))) {
            return HAM0;
        }

        // Xác thực của chính server báo thất bại thì không tin allowlist/liên hệ (người gửi có thể bị giả mạo).
        boolean authBad = v.trustedAuth && (v.spf.equals("fail") || v.dmarc.equals("fail"));

        if (!authBad && (ud.isAllowed(fromAddr) || ud.isAllowed(env))) return HAM0;

        Signals sg = new Signals();
        RuleEngine.apply(v, sg, dl);

        if (!authBad && (ud.isContact(fromAddr) || ud.isContact(env))) sg.add(-3.0, null);

        if (st.learning() && !dl.over()) {
            Bayes b = ud.bayes();
            if (b.ready()) {
                double bs = b.score(Bayes.tokenize(v));
                sg.add(bs, bs >= 1.5 ? R_BAYES : null);
            }
        }

        double score = Math.round(sg.total() * 100.0) / 100.0;
        boolean spam = score >= st.threshold();
        return new SpamVerdict(score, spam, score > 0 ? sg.top(5) : List.of());
    }

    // ------------------------------------------------------------------ gắn thẻ

    /** Chỉ THÊM 2-3 header X-PBL4-Spam-* ở đầu thư (sau dòng "From " kiểu mbox nếu có); lỗi => trả nguyên thư. */
    public static byte[] tag(byte[] raw, SpamVerdict v) {
        if (raw == null || v == null) return raw;
        try {
            String nl = "\r\n";
            int limit = Math.min(raw.length, 998);
            for (int i = 0; i < limit; i++) {
                if (raw[i] == '\n') {
                    nl = (i > 0 && raw[i - 1] == '\r') ? "\r\n" : "\n";
                    break;
                }
            }
            StringBuilder h = new StringBuilder();
            h.append("X-PBL4-Spam-Score: ").append(String.format(Locale.ROOT, "%.2f", v.score())).append(nl);
            h.append("X-PBL4-Spam-Status: ").append(v.spam() ? "YES" : "NO").append(nl);
            if (!v.reasons().isEmpty()) h.append(reasonsHeader(v.reasons(), nl));
            byte[] add = h.toString().getBytes(StandardCharsets.ISO_8859_1);

            int at = 0;
            if (raw.length > 5 && raw[0] == 'F' && raw[1] == 'r' && raw[2] == 'o' && raw[3] == 'm' && raw[4] == ' ') {
                for (int i = 5; i < raw.length; i++) {
                    if (raw[i] == '\n') {
                        at = i + 1;
                        break;
                    }
                }
            }
            byte[] out = new byte[raw.length + add.length];
            System.arraycopy(raw, 0, out, 0, at);
            System.arraycopy(add, 0, out, at, add.length);
            System.arraycopy(raw, at, out, at + add.length, raw.length - at);
            return out;
        } catch (Throwable t) {
            return raw;
        }
    }

    private static String reasonsHeader(List<String> reasons, String nl) {
        StringBuilder j = new StringBuilder();
        for (String r : reasons) {
            if (j.length() > 0) j.append("; ");
            for (int i = 0; i < r.length(); i++) {
                char c = r.charAt(i);
                j.append(c < 0x20 || c == 0x7f ? ' ' : c);
            }
        }
        String joined = j.toString();
        boolean ascii = true;
        for (int i = 0; i < joined.length(); i++) {
            if (joined.charAt(i) > 126) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            if (joined.length() > 900) joined = joined.substring(0, 900);
            return "X-PBL4-Spam-Reasons: " + joined + nl;
        }
        byte[] b = joined.getBytes(StandardCharsets.UTF_8);
        List<String> words = new ArrayList<>();
        int i = 0;
        while (i < b.length && words.size() < 12) {
            int end = Math.min(b.length, i + 42);
            while (end < b.length && end > i && (b[end] & 0xC0) == 0x80) end--;
            if (end <= i) end = Math.min(b.length, i + 42);
            words.add("=?UTF-8?B?" + Base64.getEncoder().encodeToString(Arrays.copyOfRange(b, i, end)) + "?=");
            i = end;
        }
        return "X-PBL4-Spam-Reasons: " + String.join(nl + " ", words) + nl;
    }

    // ------------------------------------------------------------------ học

    /** Học một thư là rác/không rác. Học lại cùng thư với nhãn khác = đổi nhãn (không đếm đôi). Không ném lỗi. */
    public static void learn(String user, byte[] raw, boolean isSpam) {
        try {
            if (raw == null || raw.length == 0) return;
            UserData ud = SpamStore.get(user);
            if (ud == null || !ud.settings().learning()) return;
            MailView v = MailView.of(raw);
            int code = ud.bayes().learn(Bayes.contentKey(v), Bayes.tokenize(v), isSpam);
            switch (code) {
                case 1 -> SpamStore.LEARNED_SPAM.incrementAndGet();
                case 2 -> SpamStore.LEARNED_HAM.incrementAndGet();
                case 3 -> {
                    SpamStore.LEARNED_SPAM.incrementAndGet();
                    SpamStore.LEARNED_HAM.updateAndGet(x -> Math.max(0, x - 1));
                }
                case 4 -> {
                    SpamStore.LEARNED_HAM.incrementAndGet();
                    SpamStore.LEARNED_SPAM.updateAndGet(x -> Math.max(0, x - 1));
                }
                default -> {
                }
            }
            if (code != 0) SpamStore.markGlobalDirty();
            if (isSpam) ud.removeContact(SpamAddr.extract(v.from));
        } catch (Throwable ignored) {
            // học thất bại không ảnh hưởng gì
        }
    }

    /** Người user từng gửi thư tới = liên hệ tin cậy (tối đa 2000, bỏ cái cũ nhất). */
    public static void noteRecipients(String user, Collection<String> addrs) {
        try {
            if (addrs == null || addrs.isEmpty()) return;
            UserData ud = SpamStore.get(user);
            if (ud != null) ud.noteRecipients(addrs);
        } catch (Throwable ignored) {
            // bỏ qua
        }
    }

    // ------------------------------------------------------------------ cài đặt, allowlist

    public static SpamSettings settings(String user) {
        try {
            UserData ud = SpamStore.get(user);
            return ud == null ? SpamSettings.DEFAULT : ud.settings();
        } catch (Throwable t) {
            return SpamSettings.DEFAULT;
        }
    }

    /** IllegalArgumentException nếu user không hợp lệ; UncheckedIOException nếu không ghi được file. */
    public static void saveSettings(String user, SpamSettings s) {
        if (s == null) throw new IllegalArgumentException("Thiếu cài đặt");
        UserData ud = SpamStore.get(user);
        if (ud == null) throw new IllegalArgumentException("Tên người dùng không hợp lệ");
        try {
            ud.setSettings(s);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public static List<String> allowlist(String user) {
        try {
            UserData ud = SpamStore.get(user);
            return ud == null ? new ArrayList<>() : ud.allowlist();
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /** true nếu đã thêm hoặc đã có sẵn; false nếu địa chỉ sai, danh sách đầy (500) hoặc lỗi ghi. Nhận cả "@domain.com". */
    public static boolean allow(String user, String addr) {
        try {
            UserData ud = SpamStore.get(user);
            String n = SpamStore.normAllow(addr);
            return ud != null && !n.isEmpty() && ud.allowAdd(n);
        } catch (Throwable t) {
            return false;
        }
    }

    /** true nếu đã xóa một mục. */
    public static boolean unallow(String user, String addr) {
        try {
            UserData ud = SpamStore.get(user);
            String n = SpamStore.normAllow(addr);
            return ud != null && !n.isEmpty() && ud.allowRemove(n);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ thống kê

    public static Map<String, Long> stats() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("total", SpamStore.TOTAL.get());
        m.put("spam", SpamStore.SPAM.get());
        m.put("ham", SpamStore.HAM.get());
        m.put("learnedSpam", SpamStore.LEARNED_SPAM.get());
        m.put("learnedHam", SpamStore.LEARNED_HAM.get());
        return m;
    }

    /** Ghi ngay mọi dữ liệu đang chờ xuống đĩa (dùng khi tắt server/test). */
    public static void flush() {
        SpamStore.flushAll(true);
    }

    static Set<String> unusedForCompilerHint() {
        return Set.of();
    }
}