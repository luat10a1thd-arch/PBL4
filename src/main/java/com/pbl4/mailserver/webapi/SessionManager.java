// FILE: src/main/java/com/pbl4/mailserver/webapi/SessionManager.java
package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.UserStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SessionManager {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicLong SEQ = new AtomicLong();

    private static class Session {
        final String username;
        final long seq; // thứ tự tạo (đơn điệu tăng) để xác định phiên cũ nhất, không phụ thuộc độ phân giải đồng hồ
        final long created; // mốc tạo phiên: tính thời hạn TUYỆT ĐỐI
        final String csrf;  // mã CSRF ngẫu nhiên của phiên
        // volatile: nhiều luồng HTTP cùng đọc/ghi lastAccess
        volatile long lastAccess;

        Session(String username, long created, long lastAccess, String csrf) {
            this.username = username;
            this.seq = SEQ.incrementAndGet();
            this.created = created;
            this.lastAccess = lastAccess;
            this.csrf = csrf;
        }
    }

    // Khóa = SHA-256(token) dạng hex: bộ nhớ (và file lưu phiên) không bao giờ giữ token thật
    private static final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    // Khóa riêng cho thao tác tạo phiên để giới hạn số phiên/user không bị vượt khi đăng nhập song song
    private static final Object CREATE_LOCK = new Object();

    // ---- Lưu phiên xuống đĩa (tùy chọn, PBL4_SESSION_PERSIST=true) ----
    private static final long MAX_PERSIST_FILE_BYTES = 16L * 1024 * 1024;
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Object FILE_LOCK = new Object();
    private static final AtomicBoolean dirty = new AtomicBoolean(false);
    private static volatile boolean persistEnabled = ServerConfig.SESSION_PERSIST;
    private static volatile Path persistFile = Paths.get("data", "sessions.json");

    private static final Pattern ENTRY = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern F_HASH = Pattern.compile("\"h\":\"([0-9a-f]{64})\"");
    private static final Pattern F_USER = Pattern.compile("\"u\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern F_CREATED = Pattern.compile("\"c\":(\\d{1,18})");
    private static final Pattern F_SEEN = Pattern.compile("\"l\":(\\d{1,18})");
    private static final Pattern F_CSRF = Pattern.compile("\"x\":\"([A-Za-z0-9_-]{16,128})\"");

    static {
        initPersistence();
        // Daemon để không giữ JVM khi tắt server; quét mỗi phút để đóng SSE của phiên hết hạn/bị khóa kịp thời
        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(SessionManager::cleanupExpiredSessions, 1, 1, TimeUnit.MINUTES);
    }

    private static String norm(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    /** SHA-256(token) dạng hex (64 ký tự). Cũng là định danh phiên dùng cho SseManager. */
    static String sessionKey(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 không khả dụng", e);
        }
    }

    private static String randomUrlToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Hết hạn khi quá thời gian không hoạt động (mặc định 2 giờ) HOẶC quá thời hạn tuyệt đối (mặc định 12 giờ). */
    private static boolean isExpired(Session s, long now) {
        return now - s.lastAccess > ServerConfig.SESSION_IDLE_MS
                || now - s.created > ServerConfig.SESSION_ABSOLUTE_MS;
    }

    // ===================================================================== Đọc token từ yêu cầu

    /** Yêu cầu có header Authorization kiểu Bearer (dù token có hợp lệ hay không). */
    static boolean isBearerRequest(HttpExchange ex) {
        try {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            return auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Xác thực bằng COOKIE: không có Bearer và có cookie pbl4_session hợp lệ về hình thức. */
    public static boolean isCookieRequest(HttpExchange ex) {
        return !isBearerRequest(ex) && SessionCookies.readSessionToken(ex) != null;
    }

    /**
     * Token của yêu cầu: Authorization: Bearer nếu có (sai hình thức thì null, KHÔNG quay sang cookie),
     * nếu không thì cookie pbl4_session. Chỉ nhận [A-Za-z0-9_-], dài 1..128. Không bao giờ ném lỗi.
     */
    public static String tokenFromRequest(HttpExchange ex) {
        if (ex == null) return null;
        try {
            if (isBearerRequest(ex)) {
                String t = ex.getRequestHeaders().getFirst("Authorization").substring(7).trim();
                return SessionCookies.isWellFormedToken(t) ? t : null;
            }
            return SessionCookies.readSessionToken(ex);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Mã CSRF của phiên (null nếu token không còn hợp lệ). */
    public static String csrfTokenOf(String token) {
        if (validate(token) == null) return null;
        Session s = sessions.get(sessionKey(token));
        return s == null ? null : s.csrf;
    }

    // ===================================================================== Phiên

    /**
     * Tạo phiên mới với token ngẫu nhiên MỚI mỗi lần gọi (không bao giờ dùng lại token cũ).
     * Mỗi user tối đa SESSION_MAX_PER_USER phiên, vượt thì xóa phiên cũ nhất.
     */
    public static String createSession(String username) {
        String token = randomUrlToken(32);
        String csrf = randomUrlToken(24);
        String key = sessionKey(token);
        String user = norm(username);

        List<String> evicted = new ArrayList<>();
        synchronized (CREATE_LOCK) {
            List<Map.Entry<String, Session>> mine = new ArrayList<>();
            for (Map.Entry<String, Session> e : sessions.entrySet()) {
                if (e.getValue().username.equals(user)) mine.add(e);
            }
            int excess = mine.size() - (ServerConfig.SESSION_MAX_PER_USER - 1);
            if (excess > 0) {
                mine.sort(Comparator.comparingLong(e -> e.getValue().seq));
                for (int i = 0; i < excess; i++) {
                    String old = mine.get(i).getKey();
                    if (sessions.remove(old) != null) evicted.add(old);
                }
            }
            long now = System.currentTimeMillis();
            sessions.put(key, new Session(user, now, now, csrf));
        }
        persistNow();
        // Đóng SSE gắn với phiên bị đẩy ra (ngoài khóa để không giữ lock khi I/O)
        for (String old : evicted) closeSse(user, old, null);
        return token;
    }

    public static String validate(String token) {
        Session s = getValidSession(token);
        if (s == null) return null;

        if (!UserStore.exists(s.username) || "LOCKED".equalsIgnoreCase(UserStore.getStatus(s.username))) {
            invalidate(token);
            return null;
        }

        return s.username;
    }

    public static String getUsername(String token) {
        return validate(token);
    }

    private static Session getValidSession(String token) {
        if (!SessionCookies.isWellFormedToken(token)) return null;
        String key = sessionKey(token);
        Session s = sessions.get(key);
        if (s == null) return null;
        long now = System.currentTimeMillis();
        if (isExpired(s, now)) {
            if (sessions.remove(key, s)) {
                persistNow();
                closeSse(s.username, key, token);
            }
            return null;
        }
        s.lastAccess = now;
        if (persistEnabled) dirty.set(true); // lastSeen được ghi định kỳ bởi luồng dọn phiên
        return s;
    }

    public static void invalidate(String token) {
        if (!SessionCookies.isWellFormedToken(token)) return;
        String key = sessionKey(token);
        Session s = sessions.remove(key);
        // Đăng xuất / bị khóa: đóng luôn kết nối SSE của phiên này
        if (s != null) {
            persistNow();
            closeSse(s.username, key, token);
        }
    }

    public static void invalidateUser(String username) {
        if (username == null) return;
        String user = norm(username);
        boolean removed = sessions.entrySet().removeIf(entry -> entry.getValue().username.equals(user));
        if (removed) persistNow();
        SseManager.closeUser(user);
    }

    /** SseManager nhận định danh phiên là sessionKey (băm); token thật chỉ dùng thêm khi đang có sẵn. */
    private static void closeSse(String user, String key, String realTokenOrNull) {
        try {
            SseManager.closeByToken(user, key);
            if (realTokenOrNull != null) SseManager.closeByToken(user, realTokenOrNull);
        } catch (RuntimeException e) {
            System.err.println("[SESSION] Lỗi đóng SSE: " + e.getClass().getSimpleName());
        }
    }

    private static void cleanupExpiredSessions() {
        try {
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (Map.Entry<String, Session> e : sessions.entrySet()) {
                Session s = e.getValue();
                boolean drop = isExpired(s, now);
                if (!drop) {
                    // Tài khoản bị Admin khóa/xóa: cắt phiên (và SSE) ngay trong vòng quét, không chờ request kế tiếp
                    drop = !UserStore.exists(s.username) || "LOCKED".equalsIgnoreCase(UserStore.getStatus(s.username));
                }
                if (drop && sessions.remove(e.getKey(), s)) {
                    changed = true;
                    closeSse(s.username, e.getKey(), null);
                }
            }
            if (persistEnabled && (changed || dirty.get())) persistNow();
        } catch (RuntimeException ex) {
            // scheduleAtFixedRate sẽ dừng hẳn nếu ném lỗi: nuốt lỗi để lần quét sau vẫn chạy
            System.err.println("[SESSION] Lỗi dọn phiên: " + ex.getMessage());
        }
    }

    // ===================================================================== Lưu phiên xuống đĩa

    private static void initPersistence() {
        if (persistEnabled) {
            loadFromDisk();
        } else {
            // Tắt lưu phiên: xóa file cũ (nếu có) để không còn dữ liệu phiên trên đĩa
            try {
                Files.deleteIfExists(persistFile);
            } catch (IOException | RuntimeException ignored) {
                // không xóa được: bỏ qua
            }
        }
    }

    /** Mô phỏng khởi động lại JVM (chỉ dùng trong test): xóa bộ nhớ, đặt cấu hình, nạp lại nếu bật. */
    static void restartForTest(boolean persist, Path file) {
        synchronized (CREATE_LOCK) {
            sessions.clear();
            dirty.set(false);
            persistEnabled = persist;
            if (file != null) persistFile = file;
            if (persist) loadFromDisk();
        }
    }

    private static void persistNow() {
        if (!persistEnabled) return;
        dirty.set(false);
        writeFile();
    }

    private static boolean safeForJson(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '{' || c == '}') return false;
        }
        return true;
    }

    private static String jsonEsc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String jsonUnesc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                sb.append(s.charAt(++i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Ghi nguyên tử (file tạm rồi rename), quyền 600 nếu hệ hỗ trợ. Lỗi thì chỉ cảnh báo, không ảnh hưởng phiên đang chạy. */
    private static void writeFile() {
        synchronized (FILE_LOCK) {
            try {
                Path file = persistFile.toAbsolutePath();
                Path dir = file.getParent();
                Files.createDirectories(dir);

                StringBuilder sb = new StringBuilder("[\n");
                boolean first = true;
                for (Map.Entry<String, Session> e : sessions.entrySet()) {
                    Session s = e.getValue();
                    if (!safeForJson(s.username)) continue;
                    if (!first) sb.append(",\n");
                    first = false;
                    sb.append("{\"h\":\"").append(e.getKey())
                      .append("\",\"u\":\"").append(jsonEsc(s.username))
                      .append("\",\"c\":").append(s.created)
                      .append(",\"l\":").append(s.lastAccess)
                      .append(",\"x\":\"").append(s.csrf).append("\"}");
                }
                sb.append("\n]\n");

                Path tmp = dir.resolve(file.getFileName().toString() + ".tmp");
                Files.deleteIfExists(tmp);
                if (POSIX) {
                    Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                }
                Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("[SESSION] Không ghi được file lưu phiên: " + e.getClass().getSimpleName());
            }
        }
    }

    private static String find(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** Nạp phiên từ đĩa, bỏ phiên hết hạn và mọi bản ghi sai định dạng. File hỏng thì bắt đầu rỗng. */
    private static void loadFromDisk() {
        Path file = persistFile;
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_PERSIST_FILE_BYTES) return;
            String text = Files.readString(file, StandardCharsets.UTF_8);
            long now = System.currentTimeMillis();

            record Loaded(String key, String user, long created, long seen, String csrf) {}
            List<Loaded> list = new ArrayList<>();
            Matcher m = ENTRY.matcher(text);
            while (m.find()) {
                String obj = m.group();
                String h = find(F_HASH, obj);
                String u = find(F_USER, obj);
                String c = find(F_CREATED, obj);
                String l = find(F_SEEN, obj);
                String x = find(F_CSRF, obj);
                if (h == null || u == null || c == null || l == null || x == null) continue;
                String user = norm(jsonUnesc(u));
                if (user.isEmpty()) continue;
                try {
                    list.add(new Loaded(h, user, Long.parseLong(c), Long.parseLong(l), x));
                } catch (NumberFormatException ignored) {
                    // bản ghi hỏng: bỏ qua
                }
            }
            list.sort(Comparator.comparingLong(Loaded::created));
            for (Loaded e : list) {
                Session s = new Session(e.user(), e.created(), e.seen(), e.csrf());
                if (isExpired(s, now)) continue;
                sessions.put(e.key(), s);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[SESSION] Không nạp được file lưu phiên, bỏ qua: " + e.getClass().getSimpleName());
        }
    }
}