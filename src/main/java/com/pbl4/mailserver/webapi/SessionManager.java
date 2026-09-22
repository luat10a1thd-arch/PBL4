package com.pbl4.mailserver.webapi;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quản lý phiên làm việc (Session) của người dùng qua Token an toàn.
 * Lưu tạm mật khẩu trong RAM để gọi lại SMTP/POP3 nội bộ mà không cần lưu xuống đĩa.
 */
public class SessionManager {

    private static final long EXPIRY_MS = 2 * 60 * 60 * 1000; // Thời gian sống: 2 giờ
    private static final SecureRandom RANDOM = new SecureRandom();

    private static class Session {
        final String username;
        final String password; 
        long lastAccess;

        Session(String username, String password) {
            this.username = username;
            this.password = password;
            this.lastAccess = System.currentTimeMillis();
        }
    }

    private static final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public static String createSession(String username, String password) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new Session(username, password));
        return token;
    }

    public static String validate(String token) {
        Session s = getValidSession(token);
        return s != null ? s.username : null;
    }

    public static String getPassword(String token) {
        Session s = getValidSession(token);
        return s != null ? s.password : null;
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
        sessions.remove(token);
    }
}