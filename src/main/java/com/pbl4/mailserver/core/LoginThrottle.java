package com.pbl4.mailserver.core;

import java.util.concurrent.ConcurrentHashMap;

public class LoginThrottle {

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCK_TIME_MS = 15 * 60 * 1000;

    private static class AttemptInfo {
        int count;
        long lockUntil;

        AttemptInfo(int count, long lockUntil) {
            this.count = count;
            this.lockUntil = lockUntil;
        }
    }

    private static final ConcurrentHashMap<String, AttemptInfo> attempts = new ConcurrentHashMap<>();

    private static String buildKey(String clientIp, String username) {
        String ip = (clientIp != null) ? clientIp.trim() : "";
        String user = (username != null) ? username.trim().toLowerCase() : "";
        if (!ip.isEmpty() && !user.isEmpty()) {
            return ip + "|" + user;
        }
        return !ip.isEmpty() ? ip : user;
    }

    public static boolean isThrottled(String clientIp, String username) {
        String key = buildKey(clientIp, username);
        if (key.isEmpty()) return false;

        AttemptInfo info = attempts.get(key);
        if (info == null) return false;

        long now = System.currentTimeMillis();
        if (info.lockUntil > now) {
            return true;
        }

        if (info.lockUntil > 0 && info.lockUntil <= now) {
            attempts.remove(key);
            return false;
        }

        return false;
    }

    public static boolean isThrottled(String identity) {
        return isThrottled(identity, null);
    }

    public static void recordFailedAttempt(String clientIp, String username) {
        String key = buildKey(clientIp, username);
        if (key.isEmpty()) return;

        long now = System.currentTimeMillis();
        attempts.compute(key, (k, info) -> {
            if (info == null || (info.lockUntil > 0 && info.lockUntil <= now)) {
                return new AttemptInfo(1, 0);
            }
            int newCount = info.count + 1;
            long lockUntil = newCount >= MAX_ATTEMPTS ? now + LOCK_TIME_MS : 0;
            return new AttemptInfo(newCount, lockUntil);
        });
    }

    public static void recordFailedAttempt(String identity) {
        recordFailedAttempt(identity, null);
    }

    public static void resetAttempts(String clientIp, String username) {
        String key = buildKey(clientIp, username);
        if (!key.isEmpty()) {
            attempts.remove(key);
        }
    }

    public static void resetAttempts(String identity) {
        resetAttempts(identity, null);
    }
}