// FILE: src/main/java/com/pbl4/mailserver/spam/SpamEnv.java
package com.pbl4.mailserver.spam;

import com.pbl4.mailserver.config.ServerConfig;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.Predicate;

/** Cấu hình và móc nối của bộ lọc. Đọc ServerConfig lười (để test không chạm vào ServerConfig). */
final class SpamEnv {
    private SpamEnv() {
    }

    private static final Path DEFAULT_BASE = Paths.get("data", "spam");

    private static volatile String hostOverride;
    private static volatile String domainOverride;
    private static volatile Clock clock = Clock.systemUTC();
    private static volatile Predicate<String> userExistsHook;
    private static volatile Function<String, OptionalLong> createdAtHook;
    private static volatile Path baseDir = DEFAULT_BASE;
    private static volatile Method userStoreExists;
    private static volatile boolean userStoreLookedUp;
    private static volatile Method userStoreCreatedAt;
    private static volatile boolean createdAtLookedUp;

    static String hostname() {
        String h = hostOverride;
        if (h != null) return h;
        try {
            return ServerConfig.MAIL_HOSTNAME;
        } catch (Throwable t) {
            return "";
        }
    }

    static String domain() {
        String d = domainOverride;
        if (d != null) return d;
        try {
            return ServerConfig.MAIL_DOMAIN;
        } catch (Throwable t) {
            return "";
        }
    }

    static long nowMillis() {
        return clock.millis();
    }

    static Path baseDir() {
        return baseDir;
    }

    static void setBaseDir(Path p) {
        baseDir = p == null ? DEFAULT_BASE : p;
    }

    static void setUserExistsHook(Predicate<String> p) {
        userExistsHook = p;
    }

    static void setCreatedAtHook(Function<String, OptionalLong> f) {
        createdAtHook = f;
    }

    /** user = phần tên (không kèm @domain). Lỗi => false (không miễn trừ). */
    static boolean userExists(String user) {
        if (user == null || user.isEmpty()) return false;
        try {
            Predicate<String> p = userExistsHook;
            if (p != null) return p.test(user);
            return reflectExists(user);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Ngày tạo tài khoản (epoch ms). Rỗng nếu không biết (tài khoản cũ, thiếu dữ liệu hoặc lỗi) => KHÔNG bị coi là tài khoản mới.
     * Mặc định gọi core.UserStore.createdAtOf(String) bằng reflection.
     */
    static OptionalLong createdAtOf(String user) {
        if (user == null || user.isEmpty()) return OptionalLong.empty();
        try {
            Function<String, OptionalLong> h = createdAtHook;
            if (h != null) {
                OptionalLong r = h.apply(user);
                return r == null ? OptionalLong.empty() : r;
            }
            return reflectCreatedAt(user);
        } catch (Throwable t) {
            return OptionalLong.empty();
        }
    }

    /** Mặc định gọi core.UserStore.exists(String) bằng reflection (không phụ thuộc UserStore có public hay không). */
    private static boolean reflectExists(String user) throws Exception {
        if (!userStoreLookedUp) {
            synchronized (SpamEnv.class) {
                if (!userStoreLookedUp) {
                    try {
                        Class<?> c = Class.forName("com.pbl4.mailserver.core.UserStore");
                        Method m = c.getDeclaredMethod("exists", String.class);
                        m.setAccessible(true);
                        userStoreExists = m;
                    } catch (Throwable t) {
                        userStoreExists = null;
                    }
                    userStoreLookedUp = true;
                }
            }
        }
        Method m = userStoreExists;
        return m != null && Boolean.TRUE.equals(m.invoke(null, user));
    }

    private static OptionalLong reflectCreatedAt(String user) throws Exception {
        if (!createdAtLookedUp) {
            synchronized (SpamEnv.class) {
                if (!createdAtLookedUp) {
                    try {
                        Class<?> c = Class.forName("com.pbl4.mailserver.core.UserStore");
                        Method m = c.getDeclaredMethod("createdAtOf", String.class);
                        m.setAccessible(true);
                        userStoreCreatedAt = m;
                    } catch (Throwable t) {
                        userStoreCreatedAt = null;
                    }
                    createdAtLookedUp = true;
                }
            }
        }
        Method m = userStoreCreatedAt;
        if (m == null) return OptionalLong.empty();
        Object r = m.invoke(null, user);
        return r instanceof OptionalLong o ? o : OptionalLong.empty();
    }

    // ---- móc cho test ----
    static void setHostForTest(String h) {
        hostOverride = h;
    }

    static void setDomainForTest(String d) {
        domainOverride = d;
    }

    static void setClockForTest(Clock c) {
        clock = c == null ? Clock.systemUTC() : c;
    }

    static void resetTestHooks() {
        hostOverride = null;
        domainOverride = null;
        clock = Clock.systemUTC();
        userExistsHook = null;
        createdAtHook = null;
        baseDir = DEFAULT_BASE;
    }
}