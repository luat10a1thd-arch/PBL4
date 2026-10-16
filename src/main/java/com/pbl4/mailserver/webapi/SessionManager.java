package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.core.UserStore;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SessionManager {

    private static final long EXPIRY_MS = 2 * 60 * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();

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

    public static void invalidateUser(String username) {
        if (username == null) return;
        sessions.entrySet().removeIf(entry -> entry.getValue().username.equalsIgnoreCase(username.trim()));
    }

    private static void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> (now - entry.getValue().lastAccess) > EXPIRY_MS);
    }
}