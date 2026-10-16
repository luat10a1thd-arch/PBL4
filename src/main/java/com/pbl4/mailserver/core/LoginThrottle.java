package com.pbl4.mailserver.core;

import java.util.Locale;
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

    public static boolean isThrottled(String username) {
        if (username == null || username.trim().isEmpty()) return false;
        String key = username.trim().toLowerCase(Locale.ROOT);
        AttemptInfo info = attempts.get(key);
        if (info == null) return false;

        if (info.lockUntil > System.currentTimeMillis()) {
            return true;
        }

        if (info.lockUntil > 0 && info.lockUntil <= System.currentTimeMillis()) {
            attempts.remove(key);
            return false;
        }

        return false;
    }

    public static void recordFailedAttempt(String username) {
        if (username == null || username.trim().isEmpty()) return;
        String key = username.trim().toLowerCase(Locale.ROOT);
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

    public static void resetAttempts(String username) {
        if (username == null) return;
        attempts.remove(username.trim().toLowerCase(Locale.ROOT));
    }
}