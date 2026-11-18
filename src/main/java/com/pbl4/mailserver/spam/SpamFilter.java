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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Bộ lọc thư rác. Mọi hàm xử lý thư KHÔNG ném lỗi: lỗi hoặc hết thời gian => coi như không phải rác.
 * Chỉ phân tích 256 KB đầu của thư, không dùng mạng, ngân sách xử lý ~400 ms (thực tế vài chục ms).
 *
 * Thư NỘI BỘ THẬT = envelope-from thuộc MAIL_DOMAIN, là user có thật, VÀ thư không có Authentication-Results do server thêm.
 * Thư MX từ ngoài mạo danh domain nội bộ luôn có header đó nên bị coi là thư NGOÀI (chấm đủ SPF/DKIM/DMARC).
 * Thư nội bộ thật vẫn được phân tích (tiêu đề, nội dung, tín hiệu riêng); chỉ allowlist/liên hệ của người nhận được miễn trừ.
 */
public final class SpamFilter {
    private SpamFilter() {
    }

    private static final SpamVerdict HAM0 = new SpamVerdict(0.0, false, List.of());

    private record Result(SpamVerdict verdict, boolean internal) {
    }

    private static final Result HAM_R = new Result(HAM0, false);
    private static final long BUDGET_MS = 400;
    private static final long DAY_MS = 24L * 3600 * 1000;
    private static final String R_BAYES = "Nội dung giống thư rác đã học";
    private static final String R_NEW_ACCOUNT = "Tài khoản người gửi mới tạo (dưới 24 giờ)";
    private static final String R_MASS = "Gửi cùng lúc tới nhiều người nhận nội bộ";
    private static final String R_SAME = "Cùng nội dung gửi tới nhiều người trong thời gian ngắn";
    private static final String R_REPORTED = "Nhiều người dùng khác đã báo cáo người gửi này là thư rác";

    /** Cho phép Lead nối kiểm tra "user có thật" (mặc định gọi core.UserStore.exists bằng reflection). */
    public static void setUserExistsChecker(Predicate<String> checker) {
        SpamEnv.setUserExistsHook(checker);
    }

    /** Cho phép Lead nối tra ngày tạo tài khoản (mặc định gọi core.UserStore.createdAtOf bằng reflection). */
    public static void setUserCreatedAtProvider(Function<String, OptionalLong> provider) {
        SpamEnv.setCreatedAtHook(provider);
    }

    // ------------------------------------------------------------------ phân loại

    public static SpamVerdict classify(String user, byte[] raw, String envelopeFrom) {
        Result r;
        try {
            r = doClassify(user, raw, envelopeFrom);
        } catch (Throwable t) {
            r = HAM_R;
        }
        SpamVerdict v = r.verdict();
        try {
            SpamStore.count(v.spam());
            if (r.internal() && v.spam()) SpamReports.countInternalSpam();
        } catch (Throwable ignored) {
            // bộ đếm không được làm hỏng việc giao thư
        }
        return v;
    }

    private static Result doClassify(String user, byte[] raw, String envelopeFrom) {
        if (raw == null || raw.length == 0) return HAM_R;
        UserData ud = SpamStore.get(user);
        if (ud == null) return HAM_R;
        SpamSettings st = ud.settings();
        if (!st.enabled()) return HAM_R;

        Deadline dl = new Deadline(BUDGET_MS);
        MailView v = MailView.of(raw);
        String env = envAddr(envelopeFrom);
        String fromAddr = SpamAddr.extract(v.from);

        // Nội bộ thật: envelope thuộc MAIL_DOMAIN + user có thật + KHÔNG có Authentication-Results do server thêm.
        boolean internal = !v.trustedAuth && isLocalUser(env);

        // Xác thực của chính server báo thất bại thì không tin allowlist/liên hệ (người gửi có thể bị giả mạo).
        boolean authBad = v.trustedAuth && (v.spf.equals("fail") || v.dmarc.equals("fail"));

        if (internal) {
            // Miễn trừ DUY NHẤT của thư nội bộ: người gửi nằm trong allowlist hoặc liên hệ của người nhận.
            if (ud.isAllowed(env) || ud.isContact(env)) return new Result(HAM0, true);
        } else if (!authBad && (ud.isAllowed(fromAddr) || ud.isAllowed(env))) {
            return HAM_R;
        }

        Signals sg = new Signals();
        // Thư nội bộ thật không có Authentication-Results của server nên nhóm xác thực (SPF/DKIM/DMARC) tự bỏ qua;
        // các nhóm tiêu đề, liên kết, đính kèm và từ khóa vẫn được chấm.
        RuleEngine.apply(v, sg, dl);

        if (internal) {
            internalSignals(sg, v, env);
        } else if (!authBad && (ud.isContact(fromAddr) || ud.isContact(env))) {
            sg.add(-3.0, null);
        }

        if (st.learning() && !dl.over()) {
            Bayes b = ud.bayes();
            if (b.ready()) {
                double bs = b.score(Bayes.tokenize(v));
                sg.add(bs, bs >= 1.5 ? R_BAYES : null);
            }
        }

        double score = Math.round(sg.total() * 100.0) / 100.0;
        boolean spam = score >= st.threshold();
        return new Result(new SpamVerdict(score, spam, score > 0 ? sg.top(5) : List.of()), internal);
    }

    /** Tín hiệu riêng cho người gửi nội bộ thật: tài khoản mới, gửi hàng loạt, cùng nội dung, bị báo cáo. */
    private static void internalSignals(Signals sg, MailView v, String env) {
        long now = SpamEnv.nowMillis();
        OptionalLong created = createdAt(env);
        if (created.isPresent() && now - created.getAsLong() < DAY_MS) sg.add(1.5, R_NEW_ACCOUNT);

        String sKey = SpamStore.normalize(env);
        if (sKey == null) return;

        OutgoingTracker.Content c = OutgoingTracker.content(v);
        OutgoingTracker.Info info = OutgoingTracker.lookup(
                new OutgoingTracker.Key(SpamHash.id64(sKey), c.h1(), c.h2()), now);
        if (info != null) {
            if (info.biggest() >= 10) sg.add(2.0, R_MASS);
            if (c.substantial() && info.distinct() >= 5) sg.add(4.0, R_SAME);
        }

        int n = SpamReports.reporters(SpamHash.hex(sKey), now);
        if (n >= 3) sg.add(5.0, R_REPORTED);
        else if (n >= 2) sg.add(3.0, R_REPORTED);
    }

    private static OptionalLong createdAt(String addrOrName) {
        if (addrOrName == null || addrOrName.isEmpty()) return OptionalLong.empty();
        OptionalLong o = SpamEnv.createdAtOf(SpamAddr.localOf(addrOrName));
        if (o.isEmpty() && addrOrName.indexOf('@') >= 0) o = SpamEnv.createdAtOf(addrOrName);
        return o;
    }

    /** Địa chỉ envelope thuần chữ thường; tên trần (không có @, ví dụ username đã chuẩn hóa) được ghép với MAIL_DOMAIN. */
    private static String envAddr(String envelopeFrom) {
        String a = SpamAddr.extract(envelopeFrom);
        if (!a.isEmpty() || envelopeFrom == null) return a;
        String t = envelopeFrom.trim().toLowerCase(Locale.ROOT);
        String dom = SpamEnv.domain();
        if (t.isEmpty() || dom.isEmpty() || t.indexOf('@') >= 0 || t.length() > 64) return "";
        String c = t + "@" + dom;
        return SpamAddr.valid(c) ? c : "";
    }

    private static boolean isLocalUser(String env) {
        if (env == null || env.isEmpty()) return false;
        String dom = SpamEnv.domain();
        if (dom.isEmpty() || !SpamAddr.domainOf(env).equalsIgnoreCase(dom)) return false;
        return SpamEnv.userExists(SpamAddr.localOf(env)) || SpamEnv.userExists(env);
    }

    // ------------------------------------------------------------------ gửi hàng loạt

    /**
     * Ghi nhận một lần gửi từ user tới các người nhận NỘI BỘ vào cửa sổ trượt 10 phút (trong RAM).
     * Phải gọi TRƯỚC khi từng người nhận được phân loại. Không bao giờ ném lỗi.
     */
    public static void noteOutgoing(String sender, Collection<String> localRecipients, byte[] raw) {
        try {
            if (sender == null || localRecipients == null || localRecipients.isEmpty() || raw == null || raw.length == 0) {
                return;
            }
            String sKey = SpamStore.normalize(sender);
            if (sKey == null) return;
            Set<String> seen = new HashSet<>();
            Set<Long> ids = new LinkedHashSet<>();
            for (String r : localRecipients) {
                if (r == null) continue;
                String rk = SpamStore.normalize(r);
                if (rk == null || rk.equals(sKey)) continue;
                if (seen.size() >= 5000) break;
                if (seen.add(rk) && ids.size() < OutgoingTracker.MAX_RCPT) ids.add(SpamHash.id64(rk));
            }
            if (seen.isEmpty()) return;
            MailView v = MailView.of(raw);
            OutgoingTracker.Content c = OutgoingTracker.content(v);
            OutgoingTracker.note(new OutgoingTracker.Key(SpamHash.id64(sKey), c.h1(), c.h2()),
                    SpamEnv.nowMillis(), ids, seen.size());
        } catch (Throwable ignored) {
            // theo dõi hàng loạt hỏng thì bỏ qua, thư vẫn được giao như cũ
        }
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

    /**
     * Học một thư là rác/không rác. Học lại cùng thư với nhãn khác = đổi nhãn (không đếm đôi). Không ném lỗi.
     * Khi isSpam=true và người gửi là nội bộ thật: ghi thêm một báo cáo (băm) về người gửi đó.
     */
    public static void learn(String user, byte[] raw, boolean isSpam) {
        try {
            if (raw == null || raw.length == 0) return;
            UserData ud = SpamStore.get(user);
            if (ud == null) return;
            MailView v = null;
            if (isSpam) {
                v = MailView.of(raw);
                try {
                    reportInternalSender(user, v);
                } catch (Throwable ignored) {
                    // báo cáo hỏng không ảnh hưởng việc học
                }
            }
            if (!ud.settings().learning()) return;
            if (v == null) v = MailView.of(raw);
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

    private static void reportInternalSender(String reporter, MailView v) {
        String from = SpamAddr.extract(v.from);
        if (from.isEmpty() || v.trustedAuth || !isLocalUser(from)) return;
        String sKey = SpamStore.normalize(from);
        String rKey = SpamStore.normalize(reporter);
        if (sKey == null || rKey == null || sKey.equals(rKey)) return;
        long now = SpamEnv.nowMillis();
        // Chỉ tài khoản đã tạo trên 24 giờ mới được tính; thiếu ngày tạo = tài khoản cũ (đủ điều kiện).
        OptionalLong rc = createdAt(reporter);
        if (rc.isPresent() && now - rc.getAsLong() < DAY_MS) return;
        SpamReports.addReport(SpamHash.hex(rKey), SpamHash.hex(sKey), now);
    }

    /** Người user từng gửi thư tới = liên hệ tin cậy (tối đa 2000, bỏ cái cũ nhất). Tên trần được ghép với MAIL_DOMAIN. */
    public static void noteRecipients(String user, Collection<String> addrs) {
        try {
            if (addrs == null || addrs.isEmpty()) return;
            UserData ud = SpamStore.get(user);
            if (ud != null) ud.noteRecipients(qualify(addrs));
        } catch (Throwable ignored) {
            // bỏ qua
        }
    }

    private static List<String> qualify(Collection<String> addrs) {
        String dom = SpamEnv.domain();
        List<String> out = new ArrayList<>();
        for (String a : addrs) {
            if (a == null) continue;
            if (out.size() >= 5000) break;
            String t = a.trim();
            boolean bare = !dom.isEmpty() && !t.isEmpty() && t.indexOf('@') < 0 && t.indexOf(' ') < 0
                    && t.indexOf(',') < 0 && t.indexOf(';') < 0 && t.indexOf('<') < 0;
            out.add(bare ? t + "@" + dom : a);
        }
        return out;
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
        long internal = 0;
        try {
            internal = SpamReports.internalSpam();
        } catch (Throwable ignored) {
            // thiếu số liệu thì báo 0
        }
        m.put("internalSpam", internal);
        return m;
    }

    /** Ghi ngay mọi dữ liệu đang chờ xuống đĩa (dùng khi tắt server/test). */
    public static void flush() {
        SpamStore.flushAll(true);
        SpamReports.flush(true);
    }

    /** Chỉ cho test: xóa cửa sổ trượt, báo cáo trong RAM và muối đã cache. */
    static void resetInternalStateForTest() {
        OutgoingTracker.clear();
        SpamReports.resetForTest();
        SpamHash.resetForTest();
    }

    static Set<String> unusedForCompilerHint() {
        return Set.of();
    }
}