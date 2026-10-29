package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Chống dò mật khẩu / spam đăng ký (dùng chung cho HTTP, SMTP, POP3).
 * Ba loại bộ đếm (key có tiền tố riêng để không đụng nhau):
 *  - "p|ip|user": số lần sai của một cặp IP + tài khoản
 *  - "i|ip"     : tổng số lần sai của một IP (mọi tài khoản)
 *  - "r|ip"     : số lần đăng ký của một IP
 * Bộ đếm tự hết hạn sau THROTTLE_WINDOW_MS kể từ lần ghi nhận cuối; mục cũ được dọn nền định kỳ.
 */
public class LoginThrottle {

    private static final int MAX_ENTRIES = 200_000; // chặn kẻ tấn công làm phình bộ nhớ bằng username ngẫu nhiên

    private static final class Counter {
        int count;
        long lastEvent;
        long lockUntil;
    }

    private static final ConcurrentHashMap<String, Counter> attempts = new ConcurrentHashMap<>();
    private static volatile LongSupplier clock = System::currentTimeMillis;

    static {
        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "login-throttle-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(LoginThrottle::cleanup, 5, 5, TimeUnit.MINUTES);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }

    private static String pairKey(String clientIp, String username) {
        String ip = norm(clientIp);
        String user = norm(username).toLowerCase();
        if (!ip.isEmpty() && !user.isEmpty()) return "p|" + ip + "|" + user;
        return "p|" + (!ip.isEmpty() ? ip : user);
    }

    // ---------- Logic dùng chung ----------

    private static boolean isLocked(String key, long now) {
        Counter c = attempts.get(key);
        if (c == null) return false;
        synchronized (c) {
            if (c.lockUntil > now) return true;
            if (isStale(c, now)) attempts.remove(key, c); // hết khóa hoặc hết cửa sổ => dọn luôn
            return false;
        }
    }

    // Mục hết hạn: không còn khóa và đã quá cửa sổ kể từ sự kiện cuối
    private static boolean isStale(Counter c, long now) {
        return c.lockUntil <= now && now - c.lastEvent > ServerConfig.THROTTLE_WINDOW_MS;
    }

    private static void hit(String key, int max, long now) {
        if (attempts.size() >= MAX_ENTRIES) {
            cleanup();
            if (attempts.size() >= MAX_ENTRIES && !attempts.containsKey(key)) return;
        }
        // Lặp để tránh ghi vào Counter vừa bị dọn khỏi map
        while (true) {
            Counter c = attempts.computeIfAbsent(key, k -> new Counter());
            synchronized (c) {
                if (attempts.get(key) != c) continue;
                if (c.lockUntil > now) return; // đang khóa: không gia hạn thêm
                if (now - c.lastEvent > ServerConfig.THROTTLE_WINDOW_MS) c.count = 0; // hết cửa sổ => đếm lại
                c.lockUntil = 0;
                c.count++;
                c.lastEvent = now;
                if (c.count >= max) c.lockUntil = now + ServerConfig.THROTTLE_WINDOW_MS;
                return;
            }
        }
    }

    // ---------- API công khai (giữ nguyên chữ ký) ----------

    /** Bị chặn nếu cặp IP+user đang khóa HOẶC cả IP đang khóa. */
    public static boolean isThrottled(String clientIp, String username) {
        String pair = pairKey(clientIp, username);
        long now = clock.getAsLong();
        if (pair.length() <= 2) return false;
        if (isLocked(pair, now)) return true;
        String ip = norm(clientIp);
        return !ip.isEmpty() && isLocked("i|" + ip, now);
    }

    public static boolean isThrottled(String identity) {
        return isThrottled(identity, null);
    }

    public static void recordFailedAttempt(String clientIp, String username) {
        String pair = pairKey(clientIp, username);
        if (pair.length() <= 2) return;
        long now = clock.getAsLong();
        hit(pair, ServerConfig.THROTTLE_MAX_ATTEMPTS_PER_PAIR, now);
        String ip = norm(clientIp);
        if (!ip.isEmpty()) hit("i|" + ip, ServerConfig.THROTTLE_MAX_ATTEMPTS_PER_IP, now);
    }

    public static void recordFailedAttempt(String identity) {
        recordFailedAttempt(identity, null);
    }

    /** Đăng nhập đúng: xóa bộ đếm của cặp. KHÔNG xóa bộ đếm theo IP (tránh kẻ tấn công tự đăng nhập tài khoản của mình để reset). */
    public static void resetAttempts(String clientIp, String username) {
        String pair = pairKey(clientIp, username);
        if (pair.length() > 2) attempts.remove(pair);
    }

    public static void resetAttempts(String identity) {
        resetAttempts(identity, null);
    }

    // ---------- Giới hạn đăng ký theo IP ----------

    public static boolean isRegisterThrottled(String clientIp) {
        String ip = norm(clientIp);
        return !ip.isEmpty() && isLocked("r|" + ip, clock.getAsLong());
    }

    public static void recordRegistration(String clientIp) {
        String ip = norm(clientIp);
        if (!ip.isEmpty()) hit("r|" + ip, ServerConfig.THROTTLE_MAX_REGISTER_PER_IP, clock.getAsLong());
    }

    // ---------- Dọn dẹp ----------

    /** Xóa các mục đã hết khóa và quá cửa sổ không có lần sai mới. */
    static void cleanup() {
        long now = clock.getAsLong();
        for (var e : attempts.entrySet()) {
            Counter c = e.getValue();
            synchronized (c) {
                if (isStale(c, now)) attempts.remove(e.getKey(), c);
            }
        }
    }

    // ---------- Phục vụ test ----------

    static int size() {
        return attempts.size();
    }

    public static void resetForTest(LongSupplier testClock) {
        attempts.clear();
        clock = testClock == null ? System::currentTimeMillis : testClock;
    }
}