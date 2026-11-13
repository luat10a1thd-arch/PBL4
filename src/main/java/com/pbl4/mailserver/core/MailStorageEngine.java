// FILE: src/main/java/com/pbl4/mailserver/core/MailStorageEngine.java
package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.mime.MimeParser;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.regex.Pattern;

public class MailStorageEngine {

    public static final class StoredMail {
        public final String id;
        public final String content;
        private final byte[] raw;

        private StoredMail(String id, byte[] raw, boolean fromBytes) {
            this.id = id;
            this.raw = raw;
            this.content = raw == null ? null : new String(raw, StandardCharsets.UTF_8);
        }

        public static StoredMail ofBytes(String id, byte[] raw) {
            return new StoredMail(id, raw, true);
        }

        public byte[] rawBytes() {
            return raw;
        }
    }

    public static final class MailSummary {
        public final String id;
        public final String sender;
        public final String recipient;
        public final String subject;
        public final String timestamp;

        public MailSummary(String id, String sender, String recipient, String subject, String timestamp) {
            this.id = id;
            this.sender = sender;
            this.recipient = recipient;
            this.subject = subject;
            this.timestamp = timestamp;
        }
    }

    public static final class MailPage {
        public final List<MailSummary> mails;
        public final int total;

        public MailPage(List<MailSummary> mails, int total) {
            this.mails = mails;
            this.total = total;
        }
    }

    public static final class MailMeta {
        public final String id;
        public final long sizeBytes;

        public MailMeta(String id, long sizeBytes) {
            this.id = id;
            this.sizeBytes = sizeBytes;
        }

        public long size() {
            return sizeBytes;
        }
    }

    public enum SaveResult { OK, NO_USER, OVER_QUOTA, ERROR }

    private static final class MailboxIndex {
        private final ArrayList<String> names = new ArrayList<>();
        private final HashMap<String, Long> sizes = new HashMap<>();
        private final AtomicLong totalBytes = new AtomicLong();
        private boolean loaded = false;

        synchronized void ensureLoaded(Path dir) {
            if (loaded) return;
            names.clear();
            sizes.clear();
            long sum = 0;
            File[] files = dir.toFile().listFiles((d, name) -> name.endsWith(".enc"));
            if (files != null) {
                for (File f : files) {
                    long len = f.length();
                    names.add(f.getName());
                    sizes.put(f.getName(), len);
                    sum += len;
                }
                Collections.sort(names);
            }
            totalBytes.set(sum);
            loaded = true;
        }

        synchronized void reload(Path dir) {
            loaded = false;
            ensureLoaded(dir);
        }

        synchronized void add(String name, long size) {
            if (!loaded) return;
            Long old = sizes.put(name, size);
            if (old == null) {
                int n = names.size();
                if (n == 0 || names.get(n - 1).compareTo(name) < 0) {
                    names.add(name);
                } else {
                    int pos = Collections.binarySearch(names, name);
                    names.add(pos >= 0 ? pos : -pos - 1, name);
                }
                totalBytes.addAndGet(size);
            } else {
                totalBytes.addAndGet(size - old);
            }
        }

        synchronized void remove(String name) {
            if (!loaded) return;
            Long old = sizes.remove(name);
            if (old != null) {
                int pos = Collections.binarySearch(names, name);
                if (pos >= 0) names.remove(pos);
                totalBytes.addAndGet(-old);
            }
        }

        synchronized boolean isEmpty() {
            return names.isEmpty() && totalBytes.get() == 0;
        }

        synchronized int size() {
            return names.size();
        }

        synchronized boolean contains(String name) {
            return sizes.containsKey(name);
        }

        synchronized long sizeOf(String name) {
            Long v = sizes.get(name);
            return v == null ? 0L : v;
        }

        long totalBytes() {
            return totalBytes.get();
        }

        synchronized List<String> snapshotAscending() {
            return new ArrayList<>(names);
        }

        synchronized PageSlice sliceDescending(long start, int limit) {
            int total = names.size();
            List<String> out = new ArrayList<>();
            if (start >= 0 && start < total) {
                long end = Math.min(start + limit, (long) total);
                for (long i = start; i < end; i++) {
                    out.add(names.get(total - 1 - (int) i));
                }
            }
            return new PageSlice(out, total);
        }
    }

    private static final class PageSlice {
        final List<String> names;
        final int total;

        PageSlice(List<String> names, int total) {
            this.names = names;
            this.total = total;
        }
    }

    private static final class LruCache<V> {
        private final LinkedHashMap<String, V> map;

        LruCache(int maxEntries) {
            final int max = Math.max(16, maxEntries);
            this.map = new LinkedHashMap<String, V>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                    return size() > max;
                }
            };
        }

        synchronized V get(String key) {
            return map.get(key);
        }

        synchronized void put(String key, V value) {
            map.put(key, value);
        }

        synchronized void remove(String key) {
            map.remove(key);
        }

        synchronized void removeByPrefixIgnoreCase(String prefix) {
            map.keySet().removeIf(k -> k.regionMatches(true, 0, prefix, 0, prefix.length()));
        }

        synchronized void clear() {
            map.clear();
        }
    }

    /**
     * Bảng cờ seen/starred của MỘT hòm thư. Lưu trong <hòm>/meta/flags.properties,
     * nạp lười, ghi nguyên tử (file tạm quyền 600 rồi rename). Giá trị mỗi id gồm 2 ký tự:
     * ký tự 1 = seen, ký tự 2 = starred; mỗi ký tự là 'T' (true), 'F' (false) hoặc '-' (chưa đặt).
     */
    private static final class FlagTable {
        private final HashMap<String, String> map = new HashMap<>();
        private boolean loaded = false;
        private boolean dirty = false;

        synchronized void ensureLoaded(Path dir) {
            if (loaded) return;
            map.clear();
            dirty = false;
            Path file = dir.resolve(FLAGS_DIR).resolve(FLAGS_FILE);
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    Properties p = new Properties();
                    p.load(in);
                    for (String k : p.stringPropertyNames()) {
                        String v = p.getProperty(k);
                        if (isSafeId(k) && validFlagValue(v)) map.put(k, v);
                    }
                } catch (IOException | IllegalArgumentException e) {
                    map.clear();
                    System.err.println("[MAIL STORAGE] Không đọc được file cờ thư, dùng trạng thái rỗng");
                }
            }
            loaded = true;
        }

        synchronized String get(Path dir, String id) {
            ensureLoaded(dir);
            return map.get(id);
        }

        synchronized void remove(Path dir, String id) {
            ensureLoaded(dir);
            if (map.remove(id) != null) dirty = true;
        }

        synchronized void mergeAndFlush(Path dir, String id, Boolean seen, Boolean starred) throws IOException {
            ensureLoaded(dir);
            String old = map.get(id);
            char s = (old != null) ? old.charAt(0) : '-';
            char t = (old != null) ? old.charAt(1) : '-';
            if (seen != null) s = seen ? 'T' : 'F';
            if (starred != null) t = starred ? 'T' : 'F';
            map.put(id, "" + s + t);
            dirty = true;
            try {
                flush(dir);
            } catch (IOException e) {
                loaded = false;
                dirty = false;
                throw e;
            }
        }

        synchronized void flush(Path dir) throws IOException {
            if (!dirty) return;
            if (!Files.isDirectory(dir)) {
                dirty = false;
                return;
            }
            Path metaDir = dir.resolve(FLAGS_DIR);
            Path file = metaDir.resolve(FLAGS_FILE);
            if (map.isEmpty()) {
                Files.deleteIfExists(file);
                dirty = false;
                return;
            }
            Files.createDirectories(metaDir);
            SecurityUtils.restrictToOwner(metaDir, true);
            Properties p = new Properties();
            p.putAll(map);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            p.store(bos, null);
            Path tmp = metaDir.resolve(FLAGS_FILE + ".tmp");
            try {
                SecurityUtils.writeOwnerOnly(tmp, bos.toByteArray());
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                throw e;
            }
            dirty = false;
        }

        private static boolean validFlagValue(String v) {
            if (v == null || v.length() != 2) return false;
            for (int i = 0; i < 2; i++) {
                char c = v.charAt(i);
                if (c != 'T' && c != 'F' && c != '-') return false;
            }
            return true;
        }
    }

    private static final String SENT_SUFFIX = "_sent";
    private static final String SPAM_SUFFIX = "_spam";
    private static final String DRAFTS_SUFFIX = "_drafts";
    private static final String TRASH_SUFFIX = "_trash";

    private static final String FLAGS_DIR = "meta";
    private static final String FLAGS_FILE = "flags.properties";
    private static final String BLOCKED_FILE = "blocked_senders.txt";
    private static final int MAX_BLOCKED_SENDERS = 500;

    private static final long SPAM_MAX_AGE_MS = TimeUnit.DAYS.toMillis(30);
    private static final long TRASH_MAX_AGE_MS = TimeUnit.DAYS.toMillis(30);
    private static final long SPAM_PURGE_PERIOD_MINUTES = 360;

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final Pattern EMAIL_SHAPE = Pattern.compile("[a-z0-9._%+=-]{1,64}@[a-z0-9.-]{1,255}");

    private static final ConcurrentHashMap<String, MailboxIndex> indexes = new ConcurrentHashMap<>();
    private static final LruCache<MailSummary> SUMMARY_CACHE = new LruCache<>(ServerConfig.MAIL_SUMMARY_CACHE_MAX_ENTRIES);
    private static final LruCache<Long> META_CACHE = new LruCache<>(ServerConfig.MAIL_META_CACHE_MAX_ENTRIES);
    private static final ConcurrentHashMap<String, Object> quotaMonitors = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> userLocks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, FlagTable> flagTables = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Set<String>> blockedCache = new ConcurrentHashMap<>();
    // Tuần tự hóa các thao tác nhiều bước (move/saveDraft) của cùng một người dùng.
    // Thứ tự khóa luôn là: opMonitor -> khóa hòm thư -> quotaMonitor (không bao giờ ngược lại).
    private static final ConcurrentHashMap<String, Object> opMonitors = new ConcurrentHashMap<>();

    private static final ReentrantLock PURGE_LOCK = new ReentrantLock();

    interface UserDirectory {
        boolean exists(String user);
        String findSalt(String user);
    }

    private static final UserDirectory DEFAULT_USERS = new UserDirectory() {
        @Override
        public boolean exists(String user) {
            return UserStore.exists(user);
        }

        @Override
        public String findSalt(String user) {
            return UserStore.findSalt(user);
        }
    };

    private static volatile UserDirectory userDirectory = DEFAULT_USERS;
    private static volatile Path baseDirOverride = null;
    private static volatile long quotaBytesOverride = -1;
    private static volatile Function<String, String> headerDecoder = MimeParser::decodeHeader;

    private static void clearAllCaches() {
        indexes.clear();
        SUMMARY_CACHE.clear();
        META_CACHE.clear();
        quotaMonitors.clear();
        flagTables.clear();
        blockedCache.clear();
        opMonitors.clear();
    }

    static void setBaseDirForTest(Path dir) {
        baseDirOverride = dir;
        clearAllCaches();
    }

    static void setUserDirectoryForTest(UserDirectory dir) {
        userDirectory = dir == null ? DEFAULT_USERS : dir;
    }

    static void setQuotaBytesForTest(long bytes) {
        quotaBytesOverride = bytes;
    }

    static void setHeaderDecoderForTest(Function<String, String> decoder) {
        headerDecoder = decoder == null ? MimeParser::decodeHeader : decoder;
    }

    static void resetTestHooks() {
        userDirectory = DEFAULT_USERS;
        quotaBytesOverride = -1;
        headerDecoder = MimeParser::decodeHeader;
        baseDirOverride = null;
        clearAllCaches();
    }

    static boolean isWriteLockHeldByCurrentThread(String mailbox) {
        return getLockForUser(mailbox).isWriteLockedByCurrentThread();
    }

    private static final ScheduledExecutorService SPAM_PURGER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "spam-purger");
        t.setDaemon(true);
        return t;
    });

    static {
        SPAM_PURGER.scheduleWithFixedDelay(() -> {
            try {
                int n = purgeOldSpam();
                if (n > 0) {
                    System.out.println("[MAIL STORAGE] Đã dọn " + n + " thư rác quá hạn");
                }
            } catch (Throwable t) {
                System.err.println("[MAIL STORAGE] Lỗi khi dọn thư rác nền: " + t.getMessage());
            }
            try {
                int n = purgeOldTrash();
                if (n > 0) {
                    System.out.println("[MAIL STORAGE] Đã dọn " + n + " thư trong thùng rác quá hạn");
                }
            } catch (Throwable t) {
                System.err.println("[MAIL STORAGE] Lỗi khi dọn thùng rác nền: " + t.getMessage());
            }
        }, 5, SPAM_PURGE_PERIOD_MINUTES, TimeUnit.MINUTES);
    }

    // Siết quyền 700/600 cho cây dữ liệu thư và thư mục khóa DKIM một lần khi nạp lớp (chạy nền, không chặn khởi động).
    static {
        Thread t = new Thread(MailStorageEngine::hardenPermissions, "perm-hardener");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Thư mục data/ (cha của hòm thư) và data/mailboxes/ -> 700, file bên trong -> 600; thư mục khóa DKIM -> 700, file khóa -> 600.
     * Không làm gì trên hệ thống không hỗ trợ POSIX. Chỉ đổi quyền, không đổi nội dung.
     */
    public static void hardenPermissions() {
        try {
            Path base = baseDir();
            if (Files.isDirectory(base)) {
                if (baseDirOverride == null && base.getParent() != null) {
                    SecurityUtils.restrictToOwner(base.getParent(), true);
                }
                SecurityUtils.restrictTreeToOwner(base);
            }
            Path dkim = Paths.get(ServerConfig.DKIM_KEY_DIR).toAbsolutePath().normalize();
            if (Files.isDirectory(dkim)) {
                SecurityUtils.restrictTreeToOwner(dkim);
            }
        } catch (Throwable ignored) {
            // chỉ là biện pháp phòng thủ thêm, không được làm hỏng khởi động
        }
    }

    private static Path baseDir() {
        Path override = baseDirOverride;
        return (override != null ? override : Paths.get(ServerConfig.BASE_DATA_DIR)).toAbsolutePath().normalize();
    }

    private static String cacheKey(String mailbox, String fileName) {
        return mailbox.trim() + "|" + fileName;
    }

    private static MailboxIndex loadedIndex(String mailbox) {
        Path dir = mailboxDir(mailbox);
        MailboxIndex idx = indexes.computeIfAbsent(mailbox.trim(), k -> new MailboxIndex());
        idx.ensureLoaded(dir);
        return idx;
    }

    private static void forgetMailbox(String mailbox) {
        String key = mailbox.trim();
        indexes.remove(key);
        flagTables.remove(key);
        SUMMARY_CACHE.removeByPrefixIgnoreCase(key + "|");
        META_CACHE.removeByPrefixIgnoreCase(key + "|");
    }

    private static Object quotaMonitor(String cleanUser) {
        return quotaMonitors.computeIfAbsent(cleanUser.trim().toLowerCase(Locale.ROOT), k -> new Object());
    }

    private static Object opMonitor(String cleanUser) {
        return opMonitors.computeIfAbsent(cleanUser.trim().toLowerCase(Locale.ROOT), k -> new Object());
    }

    private static long usedBytesOfUser(String cleanUser) {
        return loadedIndex(cleanUser).totalBytes()
                + loadedIndex(cleanUser + SENT_SUFFIX).totalBytes()
                + loadedIndex(cleanUser + SPAM_SUFFIX).totalBytes()
                + loadedIndex(cleanUser + DRAFTS_SUFFIX).totalBytes()
                + loadedIndex(cleanUser + TRASH_SUFFIX).totalBytes();
    }

    private static long maxQuotaBytes() {
        long override = quotaBytesOverride;
        if (override >= 0) return override;
        try {
            return Math.multiplyExact(ServerConfig.MAX_MAILBOX_QUOTA_MB, 1024L * 1024L);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /** Kích thước file GCM trên đĩa: 1 byte phiên bản + IV 12 + dữ liệu + tag 16. */
    static long estimateEncryptedSize(long plainBytes) {
        return plainBytes + MailCrypto.GCM_OVERHEAD;
    }

    private static ReentrantReadWriteLock getLockForUser(String username) {
        return userLocks.computeIfAbsent(username, k -> new ReentrantReadWriteLock());
    }

    private static Path mailboxDir(String username) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Tên hòm thư không được để trống");
        }
        String name = username.trim();
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Đường dẫn hòm thư không hợp lệ");
        }
        Path base = baseDir();
        Path dir = base.resolve(name).normalize();
        if (!dir.startsWith(base) || dir.equals(base)) {
            throw new IllegalArgumentException("Đường dẫn hòm thư không hợp lệ");
        }
        return dir;
    }

    // ---------- Kiểm tra đầu vào từ bên ngoài ----------

    /** id file thư an toàn: ^[A-Za-z0-9_.-]{1,100}$ và không chứa "..". */
    static boolean isSafeId(String id) {
        return id != null && !id.contains("..") && SAFE_ID.matcher(id).matches();
    }

    private static boolean isSafeMailboxName(String mailbox) {
        if (mailbox == null) return false;
        String t = mailbox.trim();
        if (t.isEmpty() || t.length() > 254) return false;
        if (t.contains("..")) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 0x20 || c == 0x7f) return false;
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|') return false;
        }
        return true;
    }

    /** Tên hòm hợp lệ VÀ thuộc về một người dùng đang tồn tại. */
    private static boolean isValidMailboxForUser(String mailbox) {
        if (!isSafeMailboxName(mailbox)) return false;
        String clean = getCleanUsername(mailbox.trim());
        return clean != null && !clean.isEmpty() && userDirectory.exists(clean);
    }

    /** id phải hợp lệ và nằm trong chỉ mục của hòm thư. */
    private static boolean isKnownId(String mailbox, String id) {
        try {
            return isSafeId(id) && loadedIndex(mailbox).contains(id);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static void initMailbox(String username) {
        Path dirPath = mailboxDir(username);
        if (Files.isDirectory(dirPath)) return;

        ReentrantReadWriteLock lock = getLockForUser(username);
        lock.writeLock().lock();
        try {
            File dir = dirPath.toFile();
            if (!dir.exists()) {
                dir.mkdirs();
                SecurityUtils.restrictToOwner(dirPath, true);
                forgetMailbox(username);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static boolean isSentBox(String mailbox) {
        return mailbox != null && mailbox.endsWith(SENT_SUFFIX);
    }

    private static boolean isSpamBox(String mailbox) {
        return mailbox != null && mailbox.endsWith(SPAM_SUFFIX);
    }

    private static boolean isDraftsBox(String mailbox) {
        return mailbox != null && mailbox.endsWith(DRAFTS_SUFFIX);
    }

    private static boolean isTrashBox(String mailbox) {
        return mailbox != null && mailbox.endsWith(TRASH_SUFFIX);
    }

    private static String getCleanUsername(String username) {
        if (username == null) return null;
        if (username.endsWith(SENT_SUFFIX)) {
            return username.substring(0, username.length() - SENT_SUFFIX.length());
        }
        if (username.endsWith(SPAM_SUFFIX)) {
            return username.substring(0, username.length() - SPAM_SUFFIX.length());
        }
        if (username.endsWith(DRAFTS_SUFFIX)) {
            return username.substring(0, username.length() - DRAFTS_SUFFIX.length());
        }
        if (username.endsWith(TRASH_SUFFIX)) {
            return username.substring(0, username.length() - TRASH_SUFFIX.length());
        }
        return username;
    }

    private static byte[] getUserSaltOrThrow(String username) throws IllegalArgumentException {
        String cleanUser = getCleanUsername(username);
        String base64Salt = userDirectory.findSalt(cleanUser);

        if (base64Salt == null || base64Salt.trim().isEmpty()) {
            throw new IllegalArgumentException("Không tìm thấy Salt cho tài khoản: " + cleanUser);
        }

        return Base64.getDecoder().decode(base64Salt);
    }

    /** Bộ khóa (GCM mới + CBC cũ để đọc) của người dùng sở hữu hòm thư này. */
    private static MailCrypto.KeyRing keyRingFor(String mailbox) {
        byte[] salt = getUserSaltOrThrow(mailbox.trim());
        return new MailCrypto.KeyRing(ServerConfig.SECRET_KEY, MailCrypto.cleanUserOf(mailbox), salt);
    }

    /** Giải mã file thư của (hòm, id). null nếu sai khóa, bị sửa, hoặc bị tráo sang hòm/id khác. */
    private static byte[] decryptMail(byte[] fileBytes, MailCrypto.KeyRing ring, String mailbox, String id) {
        byte[] plain = MailCrypto.decrypt(fileBytes, ring, MailCrypto.aad(mailbox, id));
        if (plain == null) {
            // Chỉ ghi tên hòm và id file, tuyệt đối không ghi nội dung.
            System.err.println("[MAIL STORAGE] Không giải mã/xác thực được thư (hòm " + mailbox.trim() + ", id " + id + ")");
        }
        return plain;
    }

    public static long getMailboxSizeInBytes(String username) {
        Path dir = mailboxDir(username);
        if (!Files.isDirectory(dir)) {
            MailboxIndex stale = indexes.get(username.trim());
            if (stale != null && !stale.isEmpty()) {
                ReentrantReadWriteLock lock = getLockForUser(username);
                lock.writeLock().lock();
                try {
                    if (!Files.isDirectory(dir)) forgetMailbox(username);
                } finally {
                    lock.writeLock().unlock();
                }
            }
            return 0;
        }
        return loadedIndex(username).totalBytes();
    }

    @Deprecated
    public static boolean saveEmail(String recipient, String rawEmailContent) {
        return saveEmailResult(recipient, rawEmailContent) == SaveResult.OK;
    }

    @Deprecated
    public static SaveResult saveEmailResult(String recipient, String rawEmailContent) {
        if (recipient == null || rawEmailContent == null) {
            return SaveResult.ERROR;
        }
        return saveEmailResult(recipient, rawEmailContent.getBytes(StandardCharsets.UTF_8));
    }

    public static SaveResult saveEmailResult(String mailbox, byte[] raw) {
        return saveInternal(mailbox, raw, null, true, true, 0L);
    }

    /**
     * Lõi lưu thư. outId (nếu khác null, độ dài >= 1) nhận ĐÚNG tên file vừa được sinh ra.
     * notify=false: không bắn SSE thư mới (dùng cho move/draft).
     * enforceQuota=false: bỏ kiểm tra quota (move trong cùng một người dùng, tổng dung lượng không đổi).
     * creditBytes: số byte sắp được giải phóng (bản nháp cũ sắp bị thay) được trừ khi kiểm tra quota.
     * Luôn ghi AES-256-GCM, AAD = hòm thư + tên file nên tên file được sinh TRƯỚC khi mã hóa.
     */
    private static SaveResult saveInternal(String mailbox, byte[] raw, String[] outId,
                                           boolean notify, boolean enforceQuota, long creditBytes) {
        if (mailbox == null || raw == null) {
            return SaveResult.ERROR;
        }
        mailbox = mailbox.trim();
        if (mailbox.isEmpty()) {
            return SaveResult.ERROR;
        }
        String cleanRecipient = getCleanUsername(mailbox);

        if (!userDirectory.exists(cleanRecipient)) {
            System.err.println("[MAIL STORAGE] Người nhận không tồn tại: " + cleanRecipient);
            return SaveResult.NO_USER;
        }

        try {
            initMailbox(mailbox);

            long maxSizeBytes = maxQuotaBytes();

            if (enforceQuota) {
                long estimatedBytes = estimateEncryptedSize(raw.length);
                if (usedBytesOfUser(cleanRecipient) - creditBytes + estimatedBytes > maxSizeBytes) {
                    System.err.println("[MAIL STORAGE] Hòm thư " + mailbox + " vượt Quota");
                    return SaveResult.OVER_QUOTA;
                }
            }

            String fileName = "msg_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".enc";
            MailCrypto.KeyRing ring = keyRingFor(mailbox);
            byte[] encryptedBytes = MailCrypto.encryptGcm(raw, ring.gcm(), MailCrypto.aad(mailbox, fileName));

            boolean plainInbox = !isSentBox(mailbox) && !isSpamBox(mailbox)
                    && !isDraftsBox(mailbox) && !isTrashBox(mailbox);
            String summaryJson = null;
            if (notify && plainInbox) {
                MailSummary s = parseHeaderToSummary(fileName, cutHeader(raw));
                summaryJson = String.format(
                        "{\"id\":\"%s\",\"sender\":\"%s\",\"recipient\":\"%s\",\"subject\":\"%s\",\"timestamp\":\"%s\"}",
                        escapeJson(fileName),
                        escapeJson(s.sender.isEmpty() ? cleanRecipient : s.sender),
                        escapeJson(mailbox),
                        escapeJson(s.subject),
                        escapeJson(s.timestamp)
                );
            }

            ReentrantReadWriteLock lock = getLockForUser(mailbox);
            lock.writeLock().lock();
            try {
                if (!userDirectory.exists(cleanRecipient)) {
                    return SaveResult.NO_USER;
                }

                synchronized (quotaMonitor(cleanRecipient)) {
                    long incomingBytes = encryptedBytes.length;
                    if (enforceQuota && usedBytesOfUser(cleanRecipient) - creditBytes + incomingBytes > maxSizeBytes) {
                        System.err.println("[MAIL STORAGE] Hòm thư " + mailbox + " vượt Quota");
                        return SaveResult.OVER_QUOTA;
                    }

                    Path dir = mailboxDir(mailbox);
                    Path filePath = dir.resolve(fileName);
                    Path tmpPath = dir.resolve(fileName + ".tmp");

                    try {
                        // Tạo file tạm với quyền 600 ngay từ đầu, rename nguyên tử, lỗi thì xóa file tạm.
                        SecurityUtils.writeOwnerOnly(tmpPath, encryptedBytes);
                        try {
                            Files.move(tmpPath, filePath, StandardCopyOption.ATOMIC_MOVE);
                        } catch (AtomicMoveNotSupportedException e) {
                            Files.move(tmpPath, filePath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        try { Files.deleteIfExists(tmpPath); } catch (IOException ignored) {}
                        throw e;
                    }

                    loadedIndex(mailbox).add(fileName, incomingBytes);
                }
            } finally {
                lock.writeLock().unlock();
            }

            if (outId != null && outId.length > 0) {
                outId[0] = fileName;
            }

            if (summaryJson != null) {
                try {
                    com.pbl4.mailserver.webapi.SseManager.notifyUser(cleanRecipient, summaryJson);
                } catch (Exception e) {
                    System.err.println("[MAIL STORAGE] Lỗi khi bắn SSE: " + e.getMessage());
                }
            }

            return SaveResult.OK;
        } catch (Exception e) {
            System.err.println("[MAIL STORAGE] Lỗi khi lưu thư: " + e.getMessage());
            return SaveResult.ERROR;
        }
    }

    public static MailPage listMailSummaries(String username, int page, int limit) {
        initMailbox(username);
        ReentrantReadWriteLock lock = getLockForUser(username);
        lock.readLock().lock();

        try {
            Path dir = mailboxDir(username);
            long start = ((long) page - 1) * limit;
            MailCrypto.KeyRing ring = null;

            for (int attempt = 0; attempt < 2; attempt++) {
                boolean lastTry = (attempt == 1);
                MailboxIndex idx = loadedIndex(username);

                if (page < 1 || limit < 1) {
                    return new MailPage(new ArrayList<>(), idx.size());
                }

                PageSlice slice = idx.sliceDescending(start, limit);
                if (slice.names.isEmpty()) {
                    return new MailPage(new ArrayList<>(), slice.total);
                }

                if (ring == null) {
                    ring = keyRingFor(username);
                }

                List<MailSummary> summaries = new ArrayList<>();
                boolean drift = false;
                for (String name : slice.names) {
                    Path filePath = dir.resolve(name);
                    if (!Files.exists(filePath)) {
                        drift = true;
                        if (!lastTry) break;
                        continue;
                    }

                    String key = cacheKey(username, name);
                    MailSummary cached = SUMMARY_CACHE.get(key);
                    if (cached != null) {
                        summaries.add(cached);
                        continue;
                    }

                    byte[] headerBytes = readHeaderBytes(filePath.toFile(), ring, username);
                    if (headerBytes != null) {
                        MailSummary summary = parseHeaderToSummary(name, headerBytes);
                        SUMMARY_CACHE.put(key, summary);
                        summaries.add(summary);
                    } else if (!Files.exists(filePath)) {
                        drift = true;
                        if (!lastTry) break;
                    } else {
                        summaries.add(new MailSummary(name, "", "", "", ""));
                    }
                }

                if (!drift || lastTry) {
                    return new MailPage(summaries, slice.total);
                }
                idx.reload(dir);
            }

            return new MailPage(new ArrayList<>(), 0);
        } catch (Exception e) {
            System.err.println("[MAIL STORAGE] Lỗi khi tạo danh sách tóm tắt thư: " + e.getMessage());
            return new MailPage(new ArrayList<>(), 0);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Đọc phần tiêu đề của thư cho danh sách tóm tắt.
     * File nhỏ (<= 12 KB): giải mã và xác thực toàn bộ.
     * File lớn: chỉ đọc 12 KB đầu. GCM: giải mã CTR không xác thực phần đầu (chỉ để hiển thị tóm tắt);
     * CBC cũ: giải mã khối đầu như trước. Không tìm được hết tiêu đề thì giải mã đầy đủ.
     */
    private static byte[] readHeaderBytes(File file, MailCrypto.KeyRing ring, String mailbox) {
        final int HEADER_READ_LIMIT = 12288;
        try {
            long fileLength = file.length();

            if (fileLength <= HEADER_READ_LIMIT) {
                byte[] plain = decryptMail(Files.readAllBytes(file.toPath()), ring, mailbox, file.getName());
                return plain == null ? null : cutHeader(plain);
            }

            byte[] buffer = new byte[HEADER_READ_LIMIT];
            int readBytes;
            try (FileInputStream fis = new FileInputStream(file)) {
                readBytes = fis.readNBytes(buffer, 0, buffer.length);
            }
            if (readBytes <= 0) return null;

            if (MailCrypto.isGcm(buffer)) {
                byte[] prefix = MailCrypto.peekGcmPrefix(buffer, readBytes, ring.gcm());
                if (prefix != null) {
                    int end = findHeaderEnd(prefix);
                    if (end >= 0) {
                        return Arrays.copyOf(prefix, end);
                    }
                }
                return fullDecryptHeader(file, ring, mailbox);
            }

            if (!MailCrypto.isLegacyAllowed()) {
                return null;
            }

            SecretKey legacyKey = ring.legacy();
            int colonIdx = -1;
            for (int i = 0; i < readBytes; i++) {
                if (buffer[i] == (byte) ':') { colonIdx = i; break; }
            }
            if (colonIdx > 0) {
                byte[] iv = Base64.getDecoder().decode(Arrays.copyOfRange(buffer, 0, colonIdx));

                int b64Len = ((readBytes - colonIdx - 1) / 4) * 4;
                if (b64Len > 0) {
                    byte[] rawCipherPartial = Base64.getDecoder()
                            .decode(Arrays.copyOfRange(buffer, colonIdx + 1, colonIdx + 1 + b64Len));

                    int validAesLen = (rawCipherPartial.length / 16) * 16;
                    if (validAesLen > 0) {
                        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
                        cipher.init(Cipher.DECRYPT_MODE, legacyKey, new IvParameterSpec(iv));
                        byte[] decryptedPartial = cipher.doFinal(rawCipherPartial, 0, validAesLen);

                        int end = findHeaderEnd(decryptedPartial);
                        if (end >= 0) {
                            return Arrays.copyOf(decryptedPartial, end);
                        }
                    }
                }
            }
            return fullDecryptHeader(file, ring, mailbox);
        } catch (Exception e) {
            return fullDecryptHeader(file, ring, mailbox);
        }
    }

    private static byte[] fullDecryptHeader(File file, MailCrypto.KeyRing ring, String mailbox) {
        try {
            byte[] plain = decryptMail(Files.readAllBytes(file.toPath()), ring, mailbox, file.getName());
            return plain == null ? null : cutHeader(plain);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int findHeaderEnd(byte[] b) {
        int n = b.length;
        for (int i = 0; i < n; i++) {
            if (b[i] == '\r' && i + 3 < n && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n') {
                return i;
            }
            if (b[i] == '\n' && i + 1 < n && b[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static byte[] cutHeader(byte[] raw) {
        int end = findHeaderEnd(raw);
        return end < 0 ? raw : Arrays.copyOf(raw, end);
    }

    private static String safeDecodeHeader(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        try {
            String decoded = headerDecoder.apply(value);
            return decoded == null ? value : decoded;
        } catch (Exception e) {
            return value;
        }
    }

    private static MailSummary parseHeaderToSummary(String id, byte[] headerBytes) {
        String from = "";
        String to = "";
        String subject = "";
        String date = "";

        if (headerBytes != null && headerBytes.length > 0) {
            String text = new String(headerBytes, StandardCharsets.UTF_8);
            text = text.replaceAll("\r?\n(?=[ \t])", "");
            String[] lines = text.split("\r?\n");
            for (String line : lines) {
                if (line.isEmpty()) break;
                String lower = line.toLowerCase(Locale.ROOT);
                if (from.isEmpty() && lower.startsWith("from:")) {
                    from = line.substring(5).trim();
                } else if (to.isEmpty() && lower.startsWith("to:")) {
                    to = line.substring(3).trim();
                } else if (subject.isEmpty() && lower.startsWith("subject:")) {
                    subject = line.substring(8).trim();
                } else if (date.isEmpty() && lower.startsWith("date:")) {
                    date = line.substring(5).trim();
                }
            }
        }
        return new MailSummary(id, safeDecodeHeader(from), to, safeDecodeHeader(subject), date);
    }

    public static List<StoredMail> listMails(String username) {
        initMailbox(username);
        List<StoredMail> mails = new ArrayList<>();
        ReentrantReadWriteLock lock = getLockForUser(username);
        lock.readLock().lock();

        try {
            MailCrypto.KeyRing ring = keyRingFor(username);
            File folder = mailboxDir(username).toFile();
            File[] files = folder.listFiles((dir, name) -> name.endsWith(".enc"));

            if (files != null && files.length > 0) {
                Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));

                for (File file : files) {
                    try {
                        byte[] fileBytes = Files.readAllBytes(file.toPath());
                        byte[] plain = decryptMail(fileBytes, ring, username, file.getName());
                        mails.add(StoredMail.ofBytes(file.getName(), plain));
                    } catch (Exception e) {
                        mails.add(StoredMail.ofBytes(file.getName(), null));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MAIL STORAGE] Lỗi khi đọc danh sách thư: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }

        return mails;
    }

    public static List<MailMeta> listMailMetas(String mailbox) {
        List<MailMeta> result = new ArrayList<>();
        try {
            initMailbox(mailbox);
            Path dir = mailboxDir(mailbox);
            ReentrantReadWriteLock lock = getLockForUser(mailbox);

            List<String> names;
            lock.readLock().lock();
            try {
                names = loadedIndex(mailbox).snapshotAscending();
            } finally {
                lock.readLock().unlock();
            }

            MailCrypto.KeyRing ring = null;
            boolean drift = false;

            for (String name : names) {
                String key = cacheKey(mailbox, name);
                Long cachedSize = META_CACHE.get(key);
                if (cachedSize != null) {
                    result.add(new MailMeta(name, cachedSize));
                    continue;
                }

                byte[] bytes;
                lock.readLock().lock();
                try {
                    bytes = Files.readAllBytes(dir.resolve(name));
                } catch (NoSuchFileException e) {
                    drift = true;
                    continue;
                } catch (IOException e) {
                    result.add(new MailMeta(name, 0));
                    continue;
                } finally {
                    lock.readLock().unlock();
                }

                if (ring == null) {
                    ring = keyRingFor(mailbox);
                }

                byte[] plain = decryptMail(bytes, ring, mailbox, name);
                if (plain == null) {
                    result.add(new MailMeta(name, 0));
                    continue;
                }
                long size = plain.length;
                META_CACHE.put(key, size);
                result.add(new MailMeta(name, size));
            }

            if (drift) {
                loadedIndex(mailbox).reload(dir);
            }
        } catch (Exception e) {
            System.err.println("[MAIL STORAGE] Lỗi khi đọc danh sách thư (meta): " + e.getMessage());
            return new ArrayList<>();
        }
        return result;
    }

    public static StoredMail getSingleMail(String username, String fileId) {
        if (!isSafeId(fileId)) {
            return null;
        }
        initMailbox(username);
        ReentrantReadWriteLock lock = getLockForUser(username);
        lock.readLock().lock();

        try {
            Path filePath = mailboxDir(username).resolve(fileId).normalize();
            File file = filePath.toFile();
            if (!file.exists()) return null;

            MailCrypto.KeyRing ring = keyRingFor(username);

            byte[] fileBytes = Files.readAllBytes(filePath);
            byte[] plain = decryptMail(fileBytes, ring, username, file.getName());
            return StoredMail.ofBytes(file.getName(), plain);
        } catch (Exception e) {
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Deprecated
    public static List<String> readAllEmails(String username) {
        List<StoredMail> storedMails = listMails(username);
        List<String> emails = new ArrayList<>();
        for (StoredMail m : storedMails) {
            if (m.content != null) {
                emails.add(m.content);
            }
        }
        return emails;
    }

    /** Gọi khi đang giữ write lock của hòm thư. Cờ bị xóa trong RAM, caller gọi flushFlags() khi xong lô. */
    private static boolean deleteFileLocked(String mailbox, Path dir, String fileId) {
        if (!isSafeId(fileId)) {
            return false;
        }
        Path filePath = dir.resolve(fileId).normalize();
        if (!filePath.startsWith(dir)) {
            return false;
        }
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            System.err.println("[MAIL STORAGE] Lỗi khi xóa file " + fileId + ": " + e.getMessage());
        }
        if (!Files.exists(filePath)) {
            MailboxIndex idx = indexes.get(mailbox.trim());
            if (idx != null) idx.remove(fileId);
            String key = cacheKey(mailbox, fileId);
            SUMMARY_CACHE.remove(key);
            META_CACHE.remove(key);
            try {
                flagTable(mailbox).remove(dir, fileId);
            } catch (RuntimeException ignored) {}
            return true;
        }
        return false;
    }

    public static void deleteMailsByIds(String username, Collection<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) return;
        initMailbox(username);
        ReentrantReadWriteLock lock = getLockForUser(username);
        lock.writeLock().lock();

        try {
            Path dir = mailboxDir(username);
            for (String fileId : fileIds) {
                deleteFileLocked(username, dir, fileId);
            }
        } catch (Exception e) {
            System.err.println("[MAIL STORAGE] Lỗi khi xóa thư: " + e.getMessage());
        } finally {
            flushFlags(username);
            lock.writeLock().unlock();
        }
    }

    private static boolean deleteOne(String mailbox, String fileId) {
        if (!isSafeId(fileId)) return false;
        ReentrantReadWriteLock lock = getLockForUser(mailbox);
        lock.writeLock().lock();
        try {
            return deleteFileLocked(mailbox, mailboxDir(mailbox), fileId);
        } catch (Exception e) {
            return false;
        } finally {
            flushFlags(mailbox);
            lock.writeLock().unlock();
        }
    }

    public static void deleteMailbox(String username) {
        purgeUser(username, true);
    }

    public static void evictUser(String username) {
        purgeUser(username, false);
    }

    private static void purgeUser(String username, boolean deleteFiles) {
        if (username == null || username.trim().isEmpty()) return;
        String base = getCleanUsername(username.trim());
        if (base == null || base.isEmpty()) return;
        String sent = base + SENT_SUFFIX;
        String spam = base + SPAM_SUFFIX;
        String drafts = base + DRAFTS_SUFFIX;
        String trash = base + TRASH_SUFFIX;

        ReentrantReadWriteLock lockInbox = getLockForUser(base);
        ReentrantReadWriteLock lockSent = getLockForUser(sent);
        ReentrantReadWriteLock lockSpam = getLockForUser(spam);
        ReentrantReadWriteLock lockDrafts = getLockForUser(drafts);
        ReentrantReadWriteLock lockTrash = getLockForUser(trash);

        lockInbox.writeLock().lock();
        lockSent.writeLock().lock();
        lockSpam.writeLock().lock();
        lockDrafts.writeLock().lock();
        lockTrash.writeLock().lock();
        try {
            if (deleteFiles) {
                try {
                    deleteDirectoryQuietly(mailboxDir(base));
                    deleteDirectoryQuietly(mailboxDir(sent));
                    deleteDirectoryQuietly(mailboxDir(spam));
                    deleteDirectoryQuietly(mailboxDir(drafts));
                    deleteDirectoryQuietly(mailboxDir(trash));
                } catch (Exception e) {
                    System.err.println("[MAIL STORAGE] Lỗi khi xóa hòm thư " + base + ": " + e.getMessage());
                }
            }

            indexes.keySet().removeIf(k -> k.equalsIgnoreCase(base) || k.equalsIgnoreCase(sent)
                    || k.equalsIgnoreCase(spam) || k.equalsIgnoreCase(drafts) || k.equalsIgnoreCase(trash));
            flagTables.keySet().removeIf(k -> k.equalsIgnoreCase(base) || k.equalsIgnoreCase(sent)
                    || k.equalsIgnoreCase(spam) || k.equalsIgnoreCase(drafts) || k.equalsIgnoreCase(trash));
            for (String box : new String[]{base, sent, spam, drafts, trash}) {
                SUMMARY_CACHE.removeByPrefixIgnoreCase(box + "|");
                META_CACHE.removeByPrefixIgnoreCase(box + "|");
            }
            quotaMonitors.keySet().removeIf(k -> k.equalsIgnoreCase(base));
            blockedCache.keySet().removeIf(k -> k.equalsIgnoreCase(base));
            opMonitors.keySet().removeIf(k -> k.equalsIgnoreCase(base));
        } finally {
            userLocks.remove(base, lockInbox);
            userLocks.remove(sent, lockSent);
            userLocks.remove(spam, lockSpam);
            userLocks.remove(drafts, lockDrafts);
            userLocks.remove(trash, lockTrash);
            lockTrash.writeLock().unlock();
            lockDrafts.writeLock().unlock();
            lockSpam.writeLock().unlock();
            lockSent.writeLock().unlock();
            lockInbox.writeLock().unlock();
        }
    }

    private static void deleteDirectoryQuietly(Path dir) {
        if (!Files.exists(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try { Files.deleteIfExists(file); } catch (IOException e) {
                        System.err.println("[MAIL STORAGE] Không xóa được " + file + ": " + e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) {
                    try { Files.deleteIfExists(d); } catch (IOException e) {
                        System.err.println("[MAIL STORAGE] Không xóa được thư mục " + d + ": " + e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("[MAIL STORAGE] Lỗi khi duyệt thư mục " + dir + ": " + e.getMessage());
        }
    }

    public static int purgeOldSpam() {
        return purgeOldSpam(System.currentTimeMillis(), SPAM_MAX_AGE_MS);
    }

    static int purgeOldSpam(long nowMs, long maxAgeMs) {
        return purgeOldBoxes(SPAM_SUFFIX, nowMs, maxAgeMs);
    }

    /** Xóa thư trong các hòm _trash quá 30 ngày (tính từ lúc file được ghi vào thùng rác). */
    public static int purgeOldTrash() {
        return purgeOldTrash(System.currentTimeMillis(), TRASH_MAX_AGE_MS);
    }

    static int purgeOldTrash(long nowMs, long maxAgeMs) {
        return purgeOldBoxes(TRASH_SUFFIX, nowMs, maxAgeMs);
    }

    private static int purgeOldBoxes(String suffix, long nowMs, long maxAgeMs) {
        PURGE_LOCK.lock();
        try {
            File[] dirs = baseDir().toFile().listFiles(f -> f.isDirectory() && f.getName().endsWith(suffix));
            if (dirs == null) return 0;

            int deleted = 0;
            for (File d : dirs) {
                String mailbox = d.getName();
                ReentrantReadWriteLock lock = getLockForUser(mailbox);
                lock.writeLock().lock();
                try {
                    Path dir = mailboxDir(mailbox);
                    File[] files = dir.toFile().listFiles((x, name) -> name.endsWith(".enc"));
                    if (files == null) continue;
                    for (File f : files) {
                        if (nowMs - f.lastModified() > maxAgeMs) {
                            if (deleteFileLocked(mailbox, dir, f.getName())) deleted++;
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[MAIL STORAGE] Lỗi khi dọn thư quá hạn của " + mailbox + ": " + e.getMessage());
                } finally {
                    flushFlags(mailbox);
                    lock.writeLock().unlock();
                }
            }
            return deleted;
        } finally {
            PURGE_LOCK.unlock();
        }
    }

    public static long getTotalMailCount() {
        File baseDir = baseDir().toFile();
        if (!baseDir.exists() || !baseDir.isDirectory()) return 0;

        long count = 0;
        File[] userDirs = baseDir.listFiles((dir, name) -> new File(dir, name).isDirectory()
                && !name.endsWith(SENT_SUFFIX) && !name.endsWith(SPAM_SUFFIX)
                && !name.endsWith(DRAFTS_SUFFIX) && !name.endsWith(TRASH_SUFFIX));
        if (userDirs != null) {
            for (File dir : userDirs) {
                File[] encFiles = dir.listFiles((d, name) -> name.endsWith(".enc"));
                if (encFiles != null) {
                    count += encFiles.length;
                }
            }
        }
        return count;
    }

    public static long getTotalStorageSizeBytes() {
        File baseDir = baseDir().toFile();
        if (!baseDir.exists() || !baseDir.isDirectory()) return 0;

        long totalBytes = 0;
        File[] userDirs = baseDir.listFiles(File::isDirectory);
        if (userDirs != null) {
            for (File dir : userDirs) {
                File[] encFiles = dir.listFiles((d, name) -> name.endsWith(".enc"));
                if (encFiles != null) {
                    for (File file : encFiles) {
                        totalBytes += file.length();
                    }
                }
            }
        }
        return totalBytes;
    }

    private static String escapeJson(String str) {
        if (str == null) return "";
        StringBuilder sb = new StringBuilder(str.length() + 8);
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '"') {
                sb.append("\\\"");
            } else if (c < 0x20) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // =====================================================================
    //                      CHỨC NĂNG MỚI (L1)
    // =====================================================================

    // ---------- Quota ----------

    public static long getUsedQuotaBytes(String username) {
        if (username == null || !isSafeMailboxName(username)) return 0;
        String clean = getCleanUsername(username.trim());
        if (clean == null || clean.isEmpty()) return 0;
        try {
            return getMailboxSizeInBytes(clean)
                    + getMailboxSizeInBytes(clean + SENT_SUFFIX)
                    + getMailboxSizeInBytes(clean + SPAM_SUFFIX)
                    + getMailboxSizeInBytes(clean + DRAFTS_SUFFIX)
                    + getMailboxSizeInBytes(clean + TRASH_SUFFIX);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ---------- Cờ seen / starred ----------

    private static FlagTable flagTable(String mailbox) {
        return flagTables.computeIfAbsent(mailbox.trim(), k -> new FlagTable());
    }

    private static void flushFlags(String mailbox) {
        try {
            FlagTable t = flagTables.get(mailbox.trim());
            if (t != null) t.flush(mailboxDir(mailbox));
        } catch (Exception e) {
            System.err.println("[MAIL STORAGE] Lỗi ghi file cờ thư: " + e.getMessage());
        }
    }

    /** Đặt cờ (null = giữ nguyên). false nếu id/hòm không hợp lệ hoặc không có trong chỉ mục. */
    public static boolean updateMailFlags(String mailbox, String fileId, Boolean seen, Boolean starred) {
        if (!isSafeId(fileId) || !isValidMailboxForUser(mailbox)) return false;
        String mb = mailbox.trim();
        initMailbox(mb);
        ReentrantReadWriteLock lock = getLockForUser(mb);
        lock.writeLock().lock();
        try {
            if (!loadedIndex(mb).contains(fileId)) return false;
            if (seen == null && starred == null) return true;
            flagTable(mb).mergeAndFlush(mailboxDir(mb), fileId, seen, starred);
            return true;
        } catch (IOException e) {
            System.err.println("[MAIL STORAGE] Lỗi cập nhật cờ thư: " + e.getMessage());
            return false;
        } catch (RuntimeException e) {
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Trả Properties có khóa "seen"/"starred" ("true"/"false"); chỉ có khóa nếu đã từng đặt. */
    public static Properties getMailFlags(String mailbox, String fileId) {
        Properties props = new Properties();
        if (!isSafeId(fileId) || !isSafeMailboxName(mailbox)) return props;
        String mb = mailbox.trim();
        ReentrantReadWriteLock lock = getLockForUser(mb);
        lock.readLock().lock();
        try {
            String v = flagTable(mb).get(mailboxDir(mb), fileId);
            if (v != null && v.length() == 2) {
                if (v.charAt(0) != '-') props.setProperty("seen", String.valueOf(v.charAt(0) == 'T'));
                if (v.charAt(1) != '-') props.setProperty("starred", String.valueOf(v.charAt(1) == 'T'));
            }
        } catch (RuntimeException ignored) {
        } finally {
            lock.readLock().unlock();
        }
        return props;
    }

    // ---------- Chuyển thư giữa các hòm của cùng một người dùng ----------

    /**
     * Chép sang hòm đích (lưu xong mới xóa nguồn), chuyển cờ, trả ĐÚNG id file mới.
     * Trả null nếu lỗi (khi đó thư nguồn vẫn còn nguyên, không để lại bản sao ở đích).
     * Không bắn SSE và không kiểm tra quota (chỉ di chuyển trong cùng người dùng, tổng dung lượng không đổi).
     * Bản ghi ở đích luôn là GCM với AAD của hòm đích + id mới.
     */
    public static String moveEmail(String sourceMailbox, String targetMailbox, String fileId) {
        if (sourceMailbox == null || targetMailbox == null || fileId == null) return null;
        String src = sourceMailbox.trim();
        String dst = targetMailbox.trim();
        if (!isSafeId(fileId) || !isValidMailboxForUser(src) || !isValidMailboxForUser(dst)) return null;
        if (src.equals(dst)) return null;
        String clean = getCleanUsername(src);
        if (!clean.equals(getCleanUsername(dst))) return null;

        try {
            synchronized (opMonitor(clean)) {
                if (!isKnownId(src, fileId)) return null;

                StoredMail mail = getSingleMail(src, fileId);
                if (mail == null || mail.rawBytes() == null) return null;
                Properties oldFlags = getMailFlags(src, fileId);

                String[] newId = new String[1];
                SaveResult sr = saveInternal(dst, mail.rawBytes(), newId, false, false, 0L);
                if (sr != SaveResult.OK || newId[0] == null) return null;

                Boolean seen = oldFlags.containsKey("seen")
                        ? Boolean.valueOf(oldFlags.getProperty("seen")) : null;
                Boolean starred = oldFlags.containsKey("starred")
                        ? Boolean.valueOf(oldFlags.getProperty("starred")) : null;
                if (seen != null || starred != null) {
                    updateMailFlags(dst, newId[0], seen, starred);
                }

                if (!deleteOne(src, fileId)) {
                    deleteOne(dst, newId[0]); // hoàn tác: không để nhân đôi thư
                    return null;
                }
                return newId[0];
            }
        } catch (Exception e) {
            System.err.println("[MAIL STORAGE] Lỗi khi chuyển thư: " + e.getMessage());
            return null;
        }
    }

    // ---------- Bản nháp ----------

    /**
     * Lưu bản nháp vào <user>_drafts. Nếu existingIdOrNull hợp lệ và có trong hòm nháp thì thay thế
     * (lưu bản mới xong mới xóa bản cũ). Trả id mới, null nếu lỗi/quá quota/id không hợp lệ.
     * mailbox có thể là tên user hoặc bất kỳ hòm nào của user đó.
     */
    public static String saveDraft(String mailbox, String existingIdOrNull, byte[] raw) {
        if (raw == null || !isValidMailboxForUser(mailbox)) return null;
        String clean = getCleanUsername(mailbox.trim());
        String box = clean + DRAFTS_SUFFIX;

        String existing = null;
        if (existingIdOrNull != null && !existingIdOrNull.trim().isEmpty()) {
            existing = existingIdOrNull.trim();
            if (!isSafeId(existing)) return null;
        }

        try {
            synchronized (opMonitor(clean)) {
                String oldId = null;
                long credit = 0;
                if (existing != null && isKnownId(box, existing)) {
                    oldId = existing;
                    credit = loadedIndex(box).sizeOf(existing);
                }
                String[] out = new String[1];
                SaveResult sr = saveInternal(box, raw, out, false, true, credit);
                if (sr != SaveResult.OK || out[0] == null) return null;
                if (oldId != null) {
                    deleteOne(box, oldId);
                }
                return out[0];
            }
        } catch (Exception e) {
            System.err.println("[MAIL STORAGE] Lỗi khi lưu bản nháp: " + e.getMessage());
            return null;
        }
    }

    // ---------- Danh sách chặn ----------

    /**
     * Rút địa chỉ email thuần, chữ thường, từ địa chỉ trần hoặc giá trị header From
     * ("Tên <a@b>", "a@b (Tên)", ...). Địa chỉ không có '@' được gắn miền của server. Trả "" nếu không hợp lệ.
     */
    public static String extractCleanEmail(String rawAddress) {
        if (rawAddress == null) return "";
        String s = rawAddress.trim();
        if (s.isEmpty() || s.length() > 1000) return "";
        int lt = s.lastIndexOf('<');
        int gt = s.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            s = s.substring(lt + 1, gt).trim();
        } else {
            String chosen = null;
            String[] parts = s.split("[\\s,;]+");
            for (String p : parts) {
                if (p.indexOf('@') >= 0) { chosen = p; break; }
            }
            if (chosen == null && parts.length > 0) chosen = parts[0];
            s = chosen == null ? "" : chosen;
        }
        s = s.replaceAll("[\\s\"'<>()]", "").toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return "";
        if (!s.contains("@")) s = s + "@" + ServerConfig.MAIL_DOMAIN.toLowerCase(Locale.ROOT);
        return EMAIL_SHAPE.matcher(s).matches() ? s : "";
    }

    private static Set<String> readBlockedFile(String cleanUser) {
        Set<String> set = new LinkedHashSet<>();
        try {
            Path file = mailboxDir(cleanUser).resolve(BLOCKED_FILE);
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String t = line.trim().toLowerCase(Locale.ROOT);
                    if (!t.isEmpty() && set.size() < MAX_BLOCKED_SENDERS) set.add(t);
                }
            }
            return set;
        } catch (IOException | RuntimeException e) {
            return null; // lỗi đọc: không cache
        }
    }

    private static void writeBlockedFile(String cleanUser, Set<String> set) throws IOException {
        Path dir = mailboxDir(cleanUser);
        Path file = dir.resolve(BLOCKED_FILE);
        if (set.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Path tmp = dir.resolve(BLOCKED_FILE + ".tmp");
        try {
            String text = String.join("\n", set) + "\n";
            SecurityUtils.writeOwnerOnly(tmp, text.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw e;
        }
    }

    private static Set<String> blockedSnapshot(String cleanUser) {
        String key = cleanUser.toLowerCase(Locale.ROOT);
        Set<String> s = blockedCache.get(key);
        if (s == null) {
            Set<String> fresh = readBlockedFile(cleanUser);
            if (fresh == null) return Collections.emptySet();
            Set<String> frozen = Collections.unmodifiableSet(fresh);
            Set<String> prev = blockedCache.putIfAbsent(key, frozen);
            s = prev != null ? prev : frozen;
        }
        return s;
    }

    public static boolean addBlockedSender(String username, String senderRaw) {
        if (username == null) return false;
        String clean = getCleanUsername(username.trim());
        if (!isValidMailboxForUser(clean)) return false;
        String sender = extractCleanEmail(senderRaw);
        if (sender.isEmpty()) return false;

        initMailbox(clean);
        ReentrantReadWriteLock lock = getLockForUser(clean);
        lock.writeLock().lock();
        try {
            Set<String> cur = readBlockedFile(clean);
            if (cur == null) return false;
            if (cur.contains(sender)) return true;
            if (cur.size() >= MAX_BLOCKED_SENDERS) return false;
            Set<String> next = new LinkedHashSet<>(cur);
            next.add(sender);
            writeBlockedFile(clean, next);
            blockedCache.put(clean.toLowerCase(Locale.ROOT), Collections.unmodifiableSet(next));
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("[MAIL STORAGE] Lỗi lưu danh sách chặn: " + e.getMessage());
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static boolean removeBlockedSender(String username, String senderRaw) {
        if (username == null) return false;
        String clean = getCleanUsername(username.trim());
        if (!isValidMailboxForUser(clean)) return false;
        String sender = extractCleanEmail(senderRaw);
        if (sender.isEmpty()) return false;

        ReentrantReadWriteLock lock = getLockForUser(clean);
        lock.writeLock().lock();
        try {
            Set<String> cur = readBlockedFile(clean);
            if (cur == null) return false;
            if (!cur.contains(sender)) return true;
            Set<String> next = new LinkedHashSet<>(cur);
            next.remove(sender);
            writeBlockedFile(clean, next);
            blockedCache.put(clean.toLowerCase(Locale.ROOT), Collections.unmodifiableSet(next));
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("[MAIL STORAGE] Lỗi cập nhật danh sách chặn: " + e.getMessage());
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Nhận cả địa chỉ trần lẫn giá trị header From. */
    public static boolean isSenderBlocked(String username, String fromAddrOrHeader) {
        if (username == null) return false;
        try {
            if (!isSafeMailboxName(username)) return false;
            String clean = getCleanUsername(username.trim());
            if (clean == null || clean.isEmpty() || !userDirectory.exists(clean)) return false;
            String sender = extractCleanEmail(fromAddrOrHeader);
            if (sender.isEmpty()) return false;
            return blockedSnapshot(clean).contains(sender);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static List<String> listBlockedSenders(String username) {
        if (username == null) return new ArrayList<>();
        try {
            if (!isSafeMailboxName(username)) return new ArrayList<>();
            String clean = getCleanUsername(username.trim());
            if (clean == null || clean.isEmpty() || !userDirectory.exists(clean)) return new ArrayList<>();
            return new ArrayList<>(blockedSnapshot(clean));
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
    }
}