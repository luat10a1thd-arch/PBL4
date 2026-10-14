package com.pbl4.mailserver.webapi;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SessionManager {

    private static final long EXPIRY_MS = 2 * 60 * 60 * 1000; // Hết hạn sau 2 giờ
    private static final SecureRandom RANDOM = new SecureRandom();

    // Lớp Session lưu RAM - TUYỆT ĐỐI KHÔNG LƯU MẬT KHẨU GỐC (Đạt tiêu chí Mục 5 Báo cáo)
    private static class Session {
        final String username;
        long lastAccess;

        Session(String username) {
            this.username = username;
            this.lastAccess = System.currentTimeMillis();
        }
    }

    private static final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    static {
        // Tự động dọn dẹp các Session hết hạn mỗi 15 phút
        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();
        cleaner.scheduleAtFixedRate(SessionManager::cleanupExpiredSessions, 15, 15, TimeUnit.MINUTES);
    }

    public static String createSession(String username, String password) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new Session(username));
        return token;
    }

    public static String validate(String token) {
        Session s = getValidSession(token);
        return s != null ? s.username : null;
    }

    public static String getUsername(String token) {
        return validate(token);
    }

    private static Session getValidSession(String token) {
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        if (System.currentTimeMillis() - s.lastAccess > EXPIRY_MS) {
            sessions.remove(token);
            return null;
        }
        s.lastAccess = System.currentTimeMillis();
        return s;
    }

    public static void invalidate(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    private static void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> (now - entry.getValue().lastAccess) > EXPIRY_MS);
    }
}