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

    public static boolean isThrottled(String clientIp) {
        if (clientIp == null || clientIp.trim().isEmpty()) return false;
        String key = clientIp.trim();
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

    public static void recordFailedAttempt(String clientIp) {
        if (clientIp == null || clientIp.trim().isEmpty()) return;
        String key = clientIp.trim();
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

    public static void resetAttempts(String clientIp) {
        if (clientIp == null) return;
        attempts.remove(clientIp.trim());
    }
}