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

    // ---------- Khôi phục tài khoản (chỉ thêm) ----------
    // Khóa khôi phục theo TÀI KHOẢN dùng bộ đếm "a|user" (cùng cơ chế hit/isLocked, cửa sổ THROTTLE_WINDOW_MS = 15 phút),
    // nên kẻ tấn công đổi IP cũng không làm lại được bộ đếm.
    // Giới hạn theo giờ dùng cửa sổ trượt riêng ("f|i|ip", "f|a|user", "v|user").

    public static final int FORGOT_MAX_PER_ACCOUNT_PER_HOUR = 3;
    public static final int FORGOT_MAX_PER_IP_PER_HOUR = 10;
    public static final int EMAIL_VERIFY_MAX_PER_ACCOUNT_PER_HOUR = 5;
    public static final int RECOVERY_MAX_FAILURES_PER_ACCOUNT = 10;
    private static final long HOUR_MS = 60L * 60 * 1000;

    private static final ConcurrentHashMap<String, java.util.ArrayDeque<Long>> windowEvents = new ConcurrentHashMap<>();

    /** Cửa sổ trượt: cho phép tối đa max sự kiện trong windowMs. Trả true và ghi nhận nếu còn hạn mức. */
    private static boolean tryAcquireWindow(String key, int max, long windowMs, long now) {
        if (windowEvents.size() >= MAX_ENTRIES && !windowEvents.containsKey(key)) {
            pruneWindows(now, windowMs);
            if (windowEvents.size() >= MAX_ENTRIES && !windowEvents.containsKey(key)) return false; // đầy: từ chối (fail closed)
        }
        while (true) {
            java.util.ArrayDeque<Long> dq = windowEvents.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
            synchronized (dq) {
                if (windowEvents.get(key) != dq) continue; // vừa bị dọn khỏi map, thử lại
                while (!dq.isEmpty() && now - dq.peekFirst() >= windowMs) dq.pollFirst();
                if (dq.size() >= max) return false;
                dq.addLast(now);
                return true;
            }
        }
    }

    private static void pruneWindows(long now, long windowMs) {
        for (var e : windowEvents.entrySet()) {
            java.util.ArrayDeque<Long> dq = e.getValue();
            synchronized (dq) {
                while (!dq.isEmpty() && now - dq.peekFirst() >= windowMs) dq.pollFirst();
                if (dq.isEmpty()) windowEvents.remove(e.getKey(), dq);
            }
        }
    }

    /**
     * Mỗi yêu cầu forgot-password (kể cả với tài khoản KHÔNG tồn tại, để phản hồi không lộ gì):
     * tối đa 10/giờ mỗi IP và 3/giờ mỗi tài khoản. Trả false nếu vượt hạn mức.
     */
    public static boolean tryAcquireForgotRequest(String clientIp, String username) {
        long now = clock.getAsLong();
        String ip = norm(clientIp);
        String user = norm(username).toLowerCase();
        if (user.isEmpty()) return false;
        // IP trước: kẻ tấn công từ một IP cạn hạn mức của IP mình trước khi đụng tới hạn mức của nạn nhân
        if (!ip.isEmpty() && !tryAcquireWindow("f|i|" + ip, FORGOT_MAX_PER_IP_PER_HOUR, HOUR_MS, now)) return false;
        return tryAcquireWindow("f|a|" + user, FORGOT_MAX_PER_ACCOUNT_PER_HOUR, HOUR_MS, now);
    }

    /** Giới hạn gửi mã xác minh email khôi phục: 5/giờ mỗi tài khoản (chống dùng server để spam hộp thư bên ngoài). */
    public static boolean tryAcquireEmailVerification(String username) {
        String user = norm(username).toLowerCase();
        if (user.isEmpty()) return false;
        return tryAcquireWindow("v|" + user, EMAIL_VERIFY_MAX_PER_ACCOUNT_PER_HOUR, HOUR_MS, clock.getAsLong());
    }

    /** Khôi phục đang bị khóa (>= 10 lần sai tổng theo tài khoản, bất kể IP). */
    public static boolean isRecoveryLocked(String username) {
        String user = norm(username).toLowerCase();
        return !user.isEmpty() && isLocked("a|" + user, clock.getAsLong());
    }

    public static void recordRecoveryFailure(String username) {
        String user = norm(username).toLowerCase();
        if (!user.isEmpty()) hit("a|" + user, RECOVERY_MAX_FAILURES_PER_ACCOUNT, clock.getAsLong());
    }

    public static void resetRecoveryFailures(String username) {
        String user = norm(username).toLowerCase();
        if (!user.isEmpty()) attempts.remove("a|" + user);
    }

    /** Chỉ cho test: xóa bộ đếm khôi phục (resetForTest cũ không đụng tới cửa sổ giờ). */
    public static void resetRecoveryForTest() {
        windowEvents.clear();
        attempts.keySet().removeIf(k -> k.startsWith("a|"));
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