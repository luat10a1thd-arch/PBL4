package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.UserStore;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class SessionManager {

    private static final long EXPIRY_MS = 2 * 60 * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicLong SEQ = new AtomicLong();

    private static class Session {
        final String username;
        final long seq; // thứ tự tạo (đơn điệu tăng) để xác định phiên cũ nhất, không phụ thuộc độ phân giải đồng hồ
        // volatile: nhiều luồng HTTP cùng đọc/ghi lastAccess
        volatile long lastAccess;

        Session(String username) {
            this.username = username;
            this.seq = SEQ.incrementAndGet();
            this.lastAccess = System.currentTimeMillis();
        }
    }

    private static final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    // Khóa riêng cho thao tác tạo phiên để giới hạn số phiên/user không bị vượt khi đăng nhập song song
    private static final Object CREATE_LOCK = new Object();

    static {
        // Daemon để không giữ JVM khi tắt server; quét mỗi phút để đóng SSE của phiên hết hạn kịp thời
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

    /** Tạo phiên mới. Mỗi user tối đa SESSION_MAX_PER_USER phiên, vượt thì xóa phiên cũ nhất. */
    public static String createSession(String username) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
            sessions.put(token, new Session(user));
        }
        // Đóng SSE gắn với phiên bị đẩy ra (ngoài khóa để không giữ lock khi I/O)
        for (String old : evicted) SseManager.closeByToken(user, old);
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
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        long now = System.currentTimeMillis();
        if (now - s.lastAccess > EXPIRY_MS) {
            if (sessions.remove(token, s)) SseManager.closeByToken(s.username, token);
            return null;
        }
        s.lastAccess = now;
        return s;
    }

    public static void invalidate(String token) {
        if (token == null) return;
        Session s = sessions.remove(token);
        // Đăng xuất / bị khóa: đóng luôn kết nối SSE của phiên này
        if (s != null) SseManager.closeByToken(s.username, token);
    }

    public static void invalidateUser(String username) {
        if (username == null) return;
        String user = norm(username);
        sessions.entrySet().removeIf(entry -> entry.getValue().username.equals(user));
        SseManager.closeUser(user);
    }

    private static void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            Session s = e.getValue();
            if (now - s.lastAccess > EXPIRY_MS && sessions.remove(e.getKey(), s)) {
                SseManager.closeByToken(s.username, e.getKey());
            }
        }
    }
}
