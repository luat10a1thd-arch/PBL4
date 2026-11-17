// FILE: src/main/java/com/pbl4/mailserver/webapi/SettingsHandler.java
package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.MailService;
import com.pbl4.mailserver.core.RecoveryStore;
import com.pbl4.mailserver.core.SecurityUtils;
import com.pbl4.mailserver.core.UserStore;
import com.pbl4.mailserver.forward.ForwardRule;
import com.pbl4.mailserver.forward.ForwardService;
import com.pbl4.mailserver.spam.SpamFilter;
import com.pbl4.mailserver.spam.SpamSettings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * /api/settings/*: cài đặt chuyển tiếp thư và lọc thư rác của CHÍNH người đăng nhập.
 * Người dùng luôn lấy từ phiên, không bao giờ từ tham số client.
 */
public class SettingsHandler implements HttpHandler {

    static final String PREFIX = "/api/settings";
    static final int MAX_ALLOWLIST = 500;
    static final int MAX_FILTER_CHARS = 200;
    static final int MAX_PASSWORD_CHARS = 200;
    static final int MAX_CODE_CHARS = 32;

    private static final Pattern ADDR =
            Pattern.compile("^[^\\s@<>\"(),:;\\[\\]\\\\]{1,64}@[\\p{L}\\p{N}][\\p{L}\\p{N}.-]{0,253}$");
    private static final Pattern RULE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> LEVELS = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Set<String> STAT_KEYS = Set.of("total", "spam", "ham", "learnedSpam", "learnedHam");

    private static final String MSG_PW = "Xác nhận mật khẩu không đúng!";
    private static final String MSG_BAD = "Dữ liệu không hợp lệ!";
    private static final String MSG_NOT_FOUND = "Không tìm thấy mục yêu cầu!";
    private static final String MSG_SERVER = "Lỗi hệ thống, vui lòng thử lại!";

    record Resp(int status, String json) {}

    // ---- Điểm móc (package-private) để test thay phần phụ thuộc ----

    interface ForwardApi {
        List<ForwardRule> list(String user);

        ForwardRule add(String user, String target, boolean keepCopy, String fromContains, String subjectContains);

        boolean verify(String user, String ruleId, String code);

        boolean update(String user, String ruleId, Boolean enabled, Boolean keepCopy);

        boolean remove(String user, String ruleId);
    }

    interface PasswordCheck {
        boolean verify(String user, String password);
    }

    interface Notifier {
        /** verb: "tạo" | "sửa" | "xóa". target: địa chỉ đích của luật. */
        void notifyChange(String user, String verb, String target);
    }

    private static final ForwardApi DEFAULT_FORWARD = new ForwardApi() {
        @Override public List<ForwardRule> list(String user) { return ForwardService.list(user); }
        @Override public ForwardRule add(String user, String target, boolean keepCopy, String f, String s) {
            return ForwardService.add(user, target, keepCopy, f, s);
        }
        @Override public boolean verify(String user, String id, String code) { return ForwardService.verify(user, id, code); }
        @Override public boolean update(String user, String id, Boolean enabled, Boolean keepCopy) {
            return ForwardService.update(user, id, enabled, keepCopy);
        }
        @Override public boolean remove(String user, String id) { return ForwardService.remove(user, id); }
    };

    // ĐIỂM DUY NHẤT cần đối chiếu với hàm kiểm tra mật khẩu sẵn có của UserStore/AuthHandler.
    private static final PasswordCheck DEFAULT_PASSWORD = (user, pw) -> {
    String hash = UserStore.findHash(user);
    return hash != null && SecurityUtils.verifyPassword(pw, hash);   // đổi tên và thứ tự tham số theo SecurityUtils thật
};

    private static final Notifier DEFAULT_NOTIFIER = (user, verb, target) -> {
        String when = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (xxx)", Locale.ROOT));
        String subject = "Thông báo bảo mật: quy tắc chuyển tiếp thư đã thay đổi";
        String body = "Quy tắc chuyển tiếp thư tới " + target + " vừa được " + verb + " lúc " + when + ".\n\n"
                + "Nếu không phải bạn thực hiện, hãy đổi mật khẩu và kiểm tra lại các quy tắc chuyển tiếp ngay.";
        String recovery = "";
        try {
            recovery = RecoveryStore.getRecoveryEmail(user);   // chỉ có giá trị khi đã xác minh
        } catch (RuntimeException ignored) {
            // không có email khôi phục thì chỉ báo vào hộp thư
        }
        MailService.sendSystemNotice(user, subject, body, recovery);
    };

    private static volatile ForwardApi forwardApi = DEFAULT_FORWARD;
    private static volatile PasswordCheck passwordCheck = DEFAULT_PASSWORD;
    private static volatile Notifier notifier = DEFAULT_NOTIFIER;

    static void setHooksForTest(ForwardApi f, PasswordCheck p, Notifier n) {
        forwardApi = f != null ? f : DEFAULT_FORWARD;
        passwordCheck = p != null ? p : DEFAULT_PASSWORD;
        notifier = n != null ? n : DEFAULT_NOTIFIER;
    }

    static void resetHooksForTest() {
        setHooksForTest(null, null, null);
        THROTTLE.reset();
    }

    // ---- Chống dò mật khẩu / mã xác minh (trong bộ nhớ, cửa sổ trượt) ----

    static final class Throttle {
        private final Map<String, ArrayDeque<Long>> fails = new ConcurrentHashMap<>();
        private volatile LongSupplier clock = System::currentTimeMillis;

        boolean blocked(String key, int max) {
            ArrayDeque<Long> dq = fails.get(key);
            if (dq == null) return false;
            synchronized (dq) {
                purge(dq);
                return dq.size() >= max;
            }
        }

        void fail(String key) {
            if (fails.size() > 20000) sweep();
            ArrayDeque<Long> dq = fails.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (dq) {
                purge(dq);
                dq.addLast(clock.getAsLong());
            }
        }

        void clear(String key) {
            fails.remove(key);
        }

        void reset() {
            fails.clear();
            clock = System::currentTimeMillis;
        }

        private void purge(ArrayDeque<Long> dq) {
            long now = clock.getAsLong();
            while (!dq.isEmpty() && now - dq.peekFirst() >= ServerConfig.THROTTLE_WINDOW_MS) dq.pollFirst();
        }

        private void sweep() {
            for (Map.Entry<String, ArrayDeque<Long>> e : fails.entrySet()) {
                ArrayDeque<Long> dq = e.getValue();
                synchronized (dq) {
                    purge(dq);
                    if (dq.isEmpty()) fails.remove(e.getKey(), dq);
                }
            }
            if (fails.size() > 40000) fails.clear();
        }
    }

    private static final Throttle THROTTLE = new Throttle();

    // ------------------------------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------------------------------

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String token = SessionManager.tokenFromRequest(exchange);
        String authed = SessionManager.validate(token);
        if (authed == null) {
            ApiServer.sendJson(exchange, 401, "{\"success\": false, \"message\": \"Chưa đăng nhập hoặc phiên hết hạn!\"}", false);
            return;
        }
        String user = canonicalOf(authed);

        String path = exchange.getRequestURI().getPath();
        String sub = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : path;
        while (sub.length() > 1 && sub.endsWith("/")) sub = sub.substring(0, sub.length() - 1);
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);

        Map<String, Object> params = new HashMap<>();
        if ("POST".equals(method)) {
            String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES,
                    ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
            if (body == null) return;
            Map<String, Object> parsed = parseBody(body);
            if (parsed == null) {
                ApiServer.sendJson(exchange, 400, err(MSG_BAD), false);
                return;
            }
            params.putAll(parsed);
        } else if ("DELETE".equals(method)) {
            for (Map.Entry<String, String> e : MailHandler.parseQuery(exchange.getRequestURI().getRawQuery()).entrySet()) {
                params.put(e.getKey(), e.getValue());
            }
            String cl = exchange.getRequestHeaders().getFirst("Content-Length");
            if (!params.containsKey("currentpassword") && cl != null && !cl.trim().equals("0")) {
                String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES,
                        ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
                if (body == null) return;
                Map<String, Object> parsed = parseBody(body);
                if (parsed != null) {
                    for (Map.Entry<String, Object> e : parsed.entrySet()) params.putIfAbsent(e.getKey(), e.getValue());
                }
            }
        }

        Resp r = route(method, sub, params, user, remoteIp(exchange));
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        ApiServer.sendJson(exchange, r.status(), r.json(), false);
    }

    private static String remoteIp(HttpExchange exchange) {
        try {
            InetSocketAddress a = exchange.getRemoteAddress();
            return (a == null || a.getAddress() == null) ? "?" : a.getAddress().getHostAddress();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static String canonicalOf(String authed) {
        try {
            String c = UserStore.canonical(authed);
            return c != null ? c : authed;
        } catch (RuntimeException e) {
            return authed;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Định tuyến (thuần, dễ test)
    // ------------------------------------------------------------------------------------------

    static Resp route(String method, String sub, Map<String, Object> p, String user, String ip) {
        try {
            switch (sub) {
                case "/forward":
                    if ("GET".equals(method)) return forwardList(user);
                    if ("POST".equals(method)) return forwardAdd(user, p, ip);
                    if ("DELETE".equals(method)) return forwardRemove(user, p, ip);
                    return methodNotAllowed();
                case "/forward/verify":
                    return "POST".equals(method) ? forwardVerify(user, p) : methodNotAllowed();
                case "/forward/update":
                    return "POST".equals(method) ? forwardUpdate(user, p, ip) : methodNotAllowed();
                case "/spam":
                    if ("GET".equals(method)) return spamGet(user);
                    if ("POST".equals(method)) return spamPost(user, p);
                    return methodNotAllowed();
                case "/spam/allow":
                    if ("POST".equals(method)) return allowAdd(user, p);
                    if ("DELETE".equals(method)) return allowRemove(user, p);
                    return methodNotAllowed();
                default:
                    return new Resp(404, err("Endpoint không tồn tại!"));
            }
        } catch (IllegalArgumentException e) {
            return new Resp(400, err(MSG_BAD));
        } catch (RuntimeException e) {
            System.err.println("[SETTINGS API] Lỗi xử lý " + sub + ": " + e.getClass().getSimpleName());
            return new Resp(500, err(MSG_SERVER));
        }
    }

    private static Resp methodNotAllowed() {
        return new Resp(405, err("Phương thức không hỗ trợ!"));
    }

    // ---------------- Chuyển tiếp ----------------

    private static Resp forwardList(String user) {
        List<ForwardRule> rules = forwardApi.list(user);
        StringBuilder sb = new StringBuilder("{\"success\": true, \"featureEnabled\": ")
                .append(ServerConfig.FORWARD_ENABLED)
                .append(", \"maxRules\": ").append(ServerConfig.FORWARD_MAX_RULES)
                .append(", \"rules\": [");
        for (int i = 0; i < rules.size(); i++) {
            if (i > 0) sb.append(',');
            appendRule(sb, rules.get(i));
        }
        sb.append("]}");
        return new Resp(200, sb.toString());
    }

    private static Resp forwardAdd(String user, Map<String, Object> p, String ip) {
        if (!ServerConfig.FORWARD_ENABLED) return new Resp(503, err("Tính năng chuyển tiếp đang tắt!"));
        Resp denied = requirePassword(user, p, ip);
        if (denied != null) return denied;

        String target = str(p, "target");
        if (target == null) return new Resp(400, err(MSG_BAD));
        target = target.trim().toLowerCase(Locale.ROOT);
        if (target.length() > 254 || !ADDR.matcher(target).matches()) return new Resp(400, err(MSG_BAD));

        String from = cleanFilter(str(p, "fromcontains"));
        String subj = cleanFilter(str(p, "subjectcontains"));
        Boolean keep = bool(p, "keepcopy");
        if (from == null || subj == null) return new Resp(400, err(MSG_BAD));

        if (forwardApi.list(user).size() >= ServerConfig.FORWARD_MAX_RULES) {
            return new Resp(400, err("Đã đạt số quy tắc chuyển tiếp tối đa!"));
        }

        ForwardRule rule;
        try {
            rule = forwardApi.add(user, target, keep == null || keep, from, subj);
        } catch (IllegalArgumentException e) {
            return new Resp(400, err("Không thể tạo quy tắc chuyển tiếp. Vui lòng kiểm tra lại thông tin!"));
        }
        if (rule == null) return new Resp(400, err("Không thể tạo quy tắc chuyển tiếp. Vui lòng kiểm tra lại thông tin!"));

        notifyQuietly(user, "tạo", rule.target());
        StringBuilder sb = new StringBuilder("{\"success\": true, \"needsVerification\": ")
                .append(!rule.verified()).append(", \"rule\": ");
        appendRule(sb, rule);
        sb.append('}');
        return new Resp(200, sb.toString());
    }

    private static Resp forwardVerify(String user, Map<String, Object> p) {
        String id = ruleId(p);
        String code = str(p, "code");
        if (id == null || code == null || code.trim().isEmpty() || code.length() > MAX_CODE_CHARS) {
            return new Resp(400, err(MSG_BAD));
        }
        String key = "v:" + user;
        if (THROTTLE.blocked(key, ServerConfig.THROTTLE_MAX_ATTEMPTS_PER_PAIR)) {
            return new Resp(429, err("Quá nhiều lần thử, vui lòng thử lại sau!"));
        }
        boolean ok = forwardApi.verify(user, id, code.trim());
        if (!ok) {
            THROTTLE.fail(key);
            return new Resp(400, err("Mã xác minh không đúng hoặc đã hết hạn!"));
        }
        THROTTLE.clear(key);
        return new Resp(200, "{\"success\": true, \"message\": \"Đã xác minh địa chỉ chuyển tiếp!\"}");
    }

    private static Resp forwardUpdate(String user, Map<String, Object> p, String ip) {
        Resp denied = requirePassword(user, p, ip);
        if (denied != null) return denied;
        String id = ruleId(p);
        if (id == null) return new Resp(400, err(MSG_BAD));
        Boolean enabled = bool(p, "enabled");
        Boolean keep = bool(p, "keepcopy");
        if (enabled == null && keep == null) return new Resp(400, err(MSG_BAD));

        String target = targetOf(user, id);
        if (!forwardApi.update(user, id, enabled, keep)) return new Resp(404, err(MSG_NOT_FOUND));
        notifyQuietly(user, "sửa", target);
        return new Resp(200, "{\"success\": true, \"message\": \"Đã cập nhật quy tắc chuyển tiếp!\"}");
    }

    private static Resp forwardRemove(String user, Map<String, Object> p, String ip) {
        Resp denied = requirePassword(user, p, ip);
        if (denied != null) return denied;
        String id = ruleId(p);
        if (id == null) return new Resp(400, err(MSG_BAD));

        String target = targetOf(user, id);
        if (!forwardApi.remove(user, id)) return new Resp(404, err(MSG_NOT_FOUND));
        notifyQuietly(user, "xóa", target);
        return new Resp(200, "{\"success\": true, \"message\": \"Đã xóa quy tắc chuyển tiếp!\"}");
    }

    /** Địa chỉ đích của luật thuộc user (dùng cho thông báo); rỗng nếu không thấy. */
    private static String targetOf(String user, String id) {
        try {
            for (ForwardRule r : forwardApi.list(user)) {
                if (r.id().equals(id)) return r.target();
            }
        } catch (RuntimeException ignored) {
            // chỉ phục vụ nội dung thông báo
        }
        return "";
    }

    private static void notifyQuietly(String user, String verb, String target) {
        try {
            notifier.notifyChange(user, verb, target == null ? "" : target);
        } catch (RuntimeException e) {
            System.err.println("[SETTINGS API] Không gửi được thông báo thay đổi: " + e.getClass().getSimpleName());
        }
    }

    /** Trả null nếu mật khẩu đúng; ngược lại trả phản hồi lỗi chung chung (403/429). */
    private static Resp requirePassword(String user, Map<String, Object> p, String ip) {
        String pw = str(p, "currentpassword");
        if (pw == null || pw.isEmpty() || pw.length() > MAX_PASSWORD_CHARS) return new Resp(403, err(MSG_PW));

        String userKey = "u:" + user;
        String ipKey = "i:" + ip;
        if (THROTTLE.blocked(userKey, ServerConfig.THROTTLE_MAX_ATTEMPTS_PER_PAIR)
                || THROTTLE.blocked(ipKey, ServerConfig.THROTTLE_MAX_ATTEMPTS_PER_IP)) {
            return new Resp(429, err("Quá nhiều lần thử, vui lòng thử lại sau!"));
        }
        boolean ok;
        try {
            ok = passwordCheck.verify(user, pw);
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok) {
            THROTTLE.fail(userKey);
            THROTTLE.fail(ipKey);
            return new Resp(403, err(MSG_PW));
        }
        THROTTLE.clear(userKey);
        return null;
    }

    // ---------------- Thư rác ----------------

    private static Resp spamGet(String user) {
        return new Resp(200, spamJson(user));
    }

    private static Resp spamPost(String user, Map<String, Object> p) {
        Boolean enabled = bool(p, "enabled");
        Boolean learning = bool(p, "learning");
        String level = str(p, "level");
        if (enabled == null && learning == null && level == null) return new Resp(400, err(MSG_BAD));

        SpamSettings cur = SpamFilter.settings(user);
        String newLevel = cur.level();
        if (level != null) {
            newLevel = level.trim().toUpperCase(Locale.ROOT);
            if (!LEVELS.contains(newLevel)) return new Resp(400, err(MSG_BAD));
        }
        SpamFilter.saveSettings(user, new SpamSettings(
                enabled != null ? enabled : cur.enabled(),
                newLevel,
                learning != null ? learning : cur.learning()));
        return new Resp(200, spamJson(user));
    }

    private static Resp allowAdd(String user, Map<String, Object> p) {
        String addr = cleanAddress(str(p, "address"));
        if (addr == null) return new Resp(400, err("Địa chỉ không hợp lệ!"));
        if (SpamFilter.allowlist(user).size() >= MAX_ALLOWLIST) {
            return new Resp(400, err("Danh sách an toàn đã đầy!"));
        }
        if (!SpamFilter.allow(user, addr)) return new Resp(400, err(MSG_BAD));
        return new Resp(200, spamJson(user));
    }

    private static Resp allowRemove(String user, Map<String, Object> p) {
        String addr = cleanAddress(str(p, "address"));
        if (addr == null) return new Resp(400, err("Địa chỉ không hợp lệ!"));
        if (!SpamFilter.unallow(user, addr)) return new Resp(404, err(MSG_NOT_FOUND));
        return new Resp(200, spamJson(user));
    }

    private static String spamJson(String user) {
        SpamSettings s = SpamFilter.settings(user);
        List<String> allow = SpamFilter.allowlist(user);
        Map<String, Long> stats = SpamFilter.stats();

        StringBuilder sb = new StringBuilder("{\"success\": true, \"globalEnabled\": ").append(ServerConfig.SPAM_ENABLED)
                .append(", \"enabled\": ").append(s.enabled())
                .append(", \"level\": \"").append(MailHandler.escapeJson(s.level())).append('"')
                .append(", \"learning\": ").append(s.learning())
                .append(", \"allowlist\": [");
        for (int i = 0; i < allow.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(MailHandler.escapeJson(allow.get(i))).append('"');
        }
        sb.append("], \"stats\": {");
        boolean first = true;
        for (String k : STAT_KEYS.stream().sorted().toList()) {
            Long v = stats == null ? null : stats.get(k);
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(k).append("\":").append(v == null ? 0L : v);
        }
        sb.append("}}");
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Tiện ích
    // ------------------------------------------------------------------------------------------

    private static void appendRule(StringBuilder sb, ForwardRule r) {
        sb.append("{\"id\":\"").append(MailHandler.escapeJson(r.id())).append("\",")
          .append("\"target\":\"").append(MailHandler.escapeJson(r.target())).append("\",")
          .append("\"verified\":").append(r.verified()).append(',')
          .append("\"enabled\":").append(r.enabled()).append(',')
          .append("\"keepCopy\":").append(r.keepCopy()).append(',')
          .append("\"fromContains\":\"").append(MailHandler.escapeJson(r.fromContains())).append("\",")
          .append("\"subjectContains\":\"").append(MailHandler.escapeJson(r.subjectContains())).append("\",")
          .append("\"createdAt\":").append(r.createdAt())
          .append('}');
    }

    private static String err(String message) {
        return "{\"success\": false, \"message\": \"" + MailHandler.escapeJson(message) + "\"}";
    }

    private static String ruleId(Map<String, Object> p) {
        String id = str(p, "id");
        if (id == null) return null;
        id = id.trim();
        return RULE_ID.matcher(id).matches() ? id : null;
    }

    private static String cleanAddress(String raw) {
        if (raw == null) return null;
        String a = raw.trim().toLowerCase(Locale.ROOT);
        if (a.length() > 254 || !ADDR.matcher(a).matches()) return null;
        return a;
    }

    /** Chuỗi lọc: rỗng nếu không có; null nếu không hợp lệ (quá dài hoặc có ký tự điều khiển). */
    private static String cleanFilter(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() > MAX_FILTER_CHARS) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) return null;
        }
        return s;
    }

    private static String str(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return (v instanceof String) ? (String) v : null;
    }

    /** null nếu vắng; ném IllegalArgumentException nếu có nhưng không phải true/false. */
    private static Boolean bool(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null) return null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) {
            String t = ((String) v).trim();
            if (t.equalsIgnoreCase("true")) return Boolean.TRUE;
            if (t.equalsIgnoreCase("false")) return Boolean.FALSE;
            if (t.isEmpty()) return null;
        }
        throw new IllegalArgumentException("bool");
    }

    // ------------------------------------------------------------------------------------------
    // Phân tích body: JSON phẳng (không lồng) hoặc form; khóa chuyển về chữ thường
    // ------------------------------------------------------------------------------------------

    static Map<String, Object> parseBody(String body) {
        if (body == null) return null;
        String t = body.trim();
        if (t.isEmpty()) return new HashMap<>();
        if (t.charAt(0) != '{') {
            return new HashMap<>(MailHandler.parseForm(body));
        }
        try {
            return new JsonReader(t).object();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class JsonReader {
        private final String s;
        private int i = 0;

        JsonReader(String s) {
            this.s = s;
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            expect('{');
            ws();
            if (peek() == '}') {
                i++;
                finish();
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                ws();
                Object v = value();
                m.put(k.toLowerCase(Locale.ROOT), v);
                if (m.size() > 32) throw new IllegalArgumentException("too many keys");
                ws();
                char c = next();
                if (c == ',') continue;
                if (c == '}') break;
                throw new IllegalArgumentException("bad object");
            }
            finish();
            return m;
        }

        private void finish() {
            ws();
            if (i != s.length()) throw new IllegalArgumentException("trailing");
        }

        private Object value() {
            char c = peek();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            if (c == '{' || c == '[') throw new IllegalArgumentException("nested");
            int start = i;
            while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            if (i == start) throw new IllegalArgumentException("bad value");
            return s.substring(start, i);
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new IllegalArgumentException("unterminated");
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c < 0x20) throw new IllegalArgumentException("control");
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) throw new IllegalArgumentException("escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new IllegalArgumentException("unicode");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("escape");
                }
            }
            return sb.toString();
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private char peek() {
            if (i >= s.length()) throw new IllegalArgumentException("eof");
            return s.charAt(i);
        }

        private char next() {
            char c = peek();
            i++;
            return c;
        }

        private void expect(char c) {
            if (next() != c) throw new IllegalArgumentException("expected " + c);
        }
    }

    // Phục vụ test danh sách tham số
    static List<String> unusedForCompilerHints() {
        return new ArrayList<>();
    }
}