// FILE: src/main/java/com/pbl4/mailserver/spam/SpamStore.java
package com.pbl4.mailserver.spam;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lưu dữ liệu bộ lọc theo từng user trong data/spam/:
 *   <user>.json  : cài đặt, allowlist (<=500), liên hệ tin cậy (<=2000)
 *   <user>.bayes : dữ liệu Bayes (nhị phân)
 *   _global.stats: bộ đếm toàn cục
 * Tên file sinh từ user đã chuẩn hóa (chỉ [a-z0-9._-~]); ghi qua file tạm quyền 600 rồi rename nguyên tử.
 */
final class SpamStore {
    private SpamStore() {
    }

    static final int MAX_ALLOW = 500;
    static final int MAX_CONTACTS = 2000;
    private static final int MAX_CACHED_USERS = 300;
    private static final long FLUSH_INTERVAL_MS = 30_000L;
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    static final AtomicLong TOTAL = new AtomicLong();
    static final AtomicLong SPAM = new AtomicLong();
    static final AtomicLong HAM = new AtomicLong();
    static final AtomicLong LEARNED_SPAM = new AtomicLong();
    static final AtomicLong LEARNED_HAM = new AtomicLong();
    private static volatile boolean globalDirty;
    private static volatile long globalLast;
    private static final Object GLOBAL_LOCK = new Object();

    // Khóa: UserData không bao giờ lấy khóa của lớp SpamStore => không có vòng khóa.
    private static final Map<String, UserData> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, UserData> eldest) {
            if (size() > MAX_CACHED_USERS) {
                eldest.getValue().flush(true);
                return true;
            }
            return false;
        }
    };

    static {
        loadGlobal();
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "spam-flusher");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(() -> {
            try {
                flushAll(false);
            } catch (Throwable ignored) {
                // không bao giờ được làm chết luồng nền
            }
        }, 30, 30, TimeUnit.SECONDS);
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    flushAll(true);
                } catch (Throwable ignored) {
                    // đang tắt
                }
            }, "spam-flush-hook"));
        } catch (RuntimeException ignored) {
            // không đăng ký được hook thì thôi
        }
    }

    // ------------------------------------------------------------------ tên file / IO

    /** Chuẩn hóa user thành tên file an toàn; null nếu rỗng/quá dài. Không bao giờ chứa '/', '\\' hay "..". */
    static String normalize(String user) {
        if (user == null) return null;
        String u = user.trim().toLowerCase(Locale.ROOT);
        String dom = "@" + SpamEnv.domain().toLowerCase(Locale.ROOT);
        if (dom.length() > 1 && u.endsWith(dom)) u = u.substring(0, u.length() - dom.length());
        if (u.isEmpty() || u.length() > 254) return null;
        StringBuilder sb = new StringBuilder(u.length());
        boolean changed = false;
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
                changed = true;
            }
        }
        String s = sb.toString();
        while (s.contains("..")) {
            s = s.replace("..", "._");
            changed = true;
        }
        if (s.startsWith(".")) {
            s = "_" + s;
            changed = true;
        }
        if (changed || s.length() > 80) {
            if (s.length() > 80) s = s.substring(0, 80);
            s = s + "~" + sha1hex(u).substring(0, 8);
        }
        return s;
    }

    private static String sha1hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void restrictDir(Path dir) {
        if (!POSIX) return;
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (IOException | RuntimeException ignored) {
            // chỉ là biện pháp phòng thủ thêm
        }
    }

    static void writeAtomic(Path target, byte[] data) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null && !Files.isDirectory(dir)) {
            Files.createDirectories(dir);
            restrictDir(dir);
        }
        Path tmp = dir.resolve(target.getFileName().toString() + ".tmp");
        try {
            Files.deleteIfExists(tmp);
            if (POSIX) {
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(tmp);
            }
            Files.write(tmp, data);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // bỏ qua
            }
            throw e;
        }
    }

    static byte[] readLimited(Path p) throws IOException {
        if (!Files.isRegularFile(p)) return null;
        if (Files.size(p) > MAX_FILE_BYTES) return null;
        return Files.readAllBytes(p);
    }

    private static Path fileFor(String stem, String ext) {
        Path base = SpamEnv.baseDir().toAbsolutePath().normalize();
        Path p = base.resolve(stem + ext).normalize();
        if (!p.startsWith(base)) throw new IllegalArgumentException("đường dẫn không hợp lệ");
        return p;
    }

    // ------------------------------------------------------------------ truy cập

    static synchronized UserData get(String user) {
        String stem = normalize(user);
        if (stem == null) return null;
        UserData u = CACHE.get(stem);
        if (u == null) {
            u = new UserData(stem);
            CACHE.put(stem, u);
        }
        return u;
    }

    static void flushAll(boolean force) {
        List<UserData> copy;
        synchronized (SpamStore.class) {
            copy = new ArrayList<>(CACHE.values());
        }
        for (UserData u : copy) {
            try {
                u.flush(force);
            } catch (Throwable ignored) {
                // lỗi của một user không ảnh hưởng user khác
            }
        }
        flushGlobal(force);
    }

    static synchronized void clearCacheForTest() {
        CACHE.clear();
    }

    static synchronized void resetForTest(Path base) {
        CACHE.clear();
        SpamEnv.setBaseDir(base);
        TOTAL.set(0);
        SPAM.set(0);
        HAM.set(0);
        LEARNED_SPAM.set(0);
        LEARNED_HAM.set(0);
        globalDirty = false;
    }

    // ------------------------------------------------------------------ bộ đếm toàn cục

    static void count(boolean spam) {
        TOTAL.incrementAndGet();
        (spam ? SPAM : HAM).incrementAndGet();
        globalDirty = true;
    }

    static void markGlobalDirty() {
        globalDirty = true;
    }

    private static void loadGlobal() {
        try {
            byte[] b = readLimited(fileFor("_global", ".stats"));
            if (b == null) return;
            Properties p = new Properties();
            p.load(new StringReader(new String(b, StandardCharsets.ISO_8859_1)));
            TOTAL.set(num(p, "total"));
            SPAM.set(num(p, "spam"));
            HAM.set(num(p, "ham"));
            LEARNED_SPAM.set(num(p, "learnedSpam"));
            LEARNED_HAM.set(num(p, "learnedHam"));
        } catch (Throwable ignored) {
            // bắt đầu từ 0
        }
    }

    private static long num(Properties p, String k) {
        try {
            return Math.max(0, Long.parseLong(p.getProperty(k, "0").trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void flushGlobal(boolean force) {
        synchronized (GLOBAL_LOCK) {
            if (!globalDirty) return;
            long now = System.currentTimeMillis();
            if (!force && now - globalLast < FLUSH_INTERVAL_MS) return;
            try {
                Properties p = new Properties();
                p.setProperty("total", String.valueOf(TOTAL.get()));
                p.setProperty("spam", String.valueOf(SPAM.get()));
                p.setProperty("ham", String.valueOf(HAM.get()));
                p.setProperty("learnedSpam", String.valueOf(LEARNED_SPAM.get()));
                p.setProperty("learnedHam", String.valueOf(LEARNED_HAM.get()));
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                p.store(bos, null);
                writeAtomic(fileFor("_global", ".stats"), bos.toByteArray());
                globalDirty = false;
                globalLast = now;
            } catch (IOException | RuntimeException ignored) {
                // thử lại lượt sau
            }
        }
    }

    // ------------------------------------------------------------------ allowlist

    /** "a@b.com" hoặc "@b.com" / "*@b.com" (cả domain); "" nếu không hợp lệ. */
    static String normAllow(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("*@")) t = t.substring(1);
        if (t.startsWith("@")) {
            String d = t.substring(1);
            if (d.length() < 3 || d.length() > 255 || d.indexOf('.') < 0) return "";
            for (int i = 0; i < d.length(); i++) {
                if (!SpamAddr.isDomainChar(d.charAt(i))) return "";
            }
            if (d.startsWith(".") || d.endsWith(".") || d.startsWith("-") || d.endsWith("-") || d.contains("..")) return "";
            return "@" + d;
        }
        return SpamAddr.extract(t);
    }

    // ------------------------------------------------------------------ dữ liệu một user

    static final class UserData {
        private final Path jsonFile;
        private final Path bayesFile;
        private SpamSettings settings = SpamSettings.DEFAULT;
        private final LinkedHashSet<String> allow = new LinkedHashSet<>();
        private final LinkedHashMap<String, Boolean> contacts = new LinkedHashMap<>(64, 0.75f, true);
        private boolean mainDirty;
        private long mainLast;
        private Bayes bayes;

        UserData(String stem) {
            this.jsonFile = fileFor(stem, ".json");
            this.bayesFile = fileFor(stem, ".bayes");
            loadMain();
        }

        private static boolean bool(Object o, boolean def) {
            return o instanceof Boolean b ? b : def;
        }

        private void loadMain() {
            try {
                byte[] b = readLimited(jsonFile);
                if (b == null) return;
                Object o = MiniJson.parse(new String(b, StandardCharsets.UTF_8));
                if (!(o instanceof Map<?, ?> m)) return;
                if (m.get("settings") instanceof Map<?, ?> sm) {
                    Object lv = sm.get("level");
                    settings = new SpamSettings(bool(sm.get("enabled"), true),
                            lv instanceof String ls ? ls : "MEDIUM", bool(sm.get("learning"), true));
                }
                if (m.get("allowlist") instanceof List<?> l) {
                    for (Object x : l) {
                        if (allow.size() >= MAX_ALLOW) break;
                        if (x instanceof String xs) {
                            String n = normAllow(xs);
                            if (!n.isEmpty()) allow.add(n);
                        }
                    }
                }
                if (m.get("contacts") instanceof List<?> l) {
                    for (Object x : l) {
                        if (contacts.size() >= MAX_CONTACTS) break;
                        if (x instanceof String xs) {
                            String n = SpamAddr.extract(xs);
                            if (!n.isEmpty()) contacts.put(n, Boolean.TRUE);
                        }
                    }
                }
            } catch (Throwable t) {
                // file hỏng: dùng mặc định
                settings = SpamSettings.DEFAULT;
                allow.clear();
                contacts.clear();
            }
        }

        private void saveMainNow() throws IOException {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("v", 1);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("enabled", settings.enabled());
            s.put("level", settings.level());
            s.put("learning", settings.learning());
            root.put("settings", s);
            root.put("allowlist", new ArrayList<>(allow));
            root.put("contacts", new ArrayList<>(contacts.keySet()));
            writeAtomic(jsonFile, MiniJson.write(root).getBytes(StandardCharsets.UTF_8));
            mainDirty = false;
            mainLast = System.currentTimeMillis();
        }

        synchronized SpamSettings settings() {
            return settings;
        }

        synchronized void setSettings(SpamSettings s) throws IOException {
            SpamSettings old = settings;
            settings = s;
            try {
                saveMainNow();
            } catch (IOException | RuntimeException e) {
                settings = old;
                throw e;
            }
        }

        synchronized List<String> allowlist() {
            return new ArrayList<>(allow);
        }

        synchronized boolean allowAdd(String entry) {
            if (allow.contains(entry)) return true;
            if (allow.size() >= MAX_ALLOW) return false;
            allow.add(entry);
            try {
                saveMainNow();
                return true;
            } catch (IOException | RuntimeException e) {
                allow.remove(entry);
                return false;
            }
        }

        synchronized boolean allowRemove(String entry) {
            if (!allow.remove(entry)) return false;
            try {
                saveMainNow();
            } catch (IOException | RuntimeException e) {
                allow.add(entry);
                return false;
            }
            return true;
        }

        synchronized boolean isAllowed(String addr) {
            if (addr == null || addr.isEmpty()) return false;
            if (allow.contains(addr)) return true;
            String d = SpamAddr.domainOf(addr);
            return !d.isEmpty() && allow.contains("@" + d);
        }

        synchronized void noteRecipients(Collection<String> addrs) {
            boolean changed = false;
            for (String a : addrs) {
                if (a == null) continue;
                for (String part : a.split("[,;]")) {
                    String n = SpamAddr.extract(part);
                    if (n.isEmpty()) continue;
                    contacts.put(n, Boolean.TRUE);
                    changed = true;
                    while (contacts.size() > MAX_CONTACTS) {
                        Iterator<String> it = contacts.keySet().iterator();
                        it.next();
                        it.remove();
                    }
                }
            }
            if (changed) mainDirty = true;
        }

        synchronized boolean isContact(String addr) {
            return addr != null && !addr.isEmpty() && contacts.containsKey(addr);
        }

        synchronized void removeContact(String addr) {
            if (addr != null && !addr.isEmpty() && contacts.remove(addr) != null) mainDirty = true;
        }

        synchronized Bayes bayes() {
            if (bayes == null) {
                Bayes b = null;
                try {
                    b = Bayes.load(bayesFile);
                } catch (Throwable ignored) {
                    // file hỏng: bắt đầu lại
                }
                bayes = b != null ? b : new Bayes();
            }
            return bayes;
        }

        synchronized void flush(boolean force) {
            long now = System.currentTimeMillis();
            if (mainDirty && (force || now - mainLast >= FLUSH_INTERVAL_MS)) {
                try {
                    saveMainNow();
                } catch (IOException | RuntimeException ignored) {
                    // giữ dirty, thử lại
                }
            }
            if (bayes != null) bayes.flushTo(bayesFile, force);
        }
    }
}