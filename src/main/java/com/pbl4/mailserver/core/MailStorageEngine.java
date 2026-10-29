package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.mime.MimeParser;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

/**
 * Kho lưu trữ thư (mã hóa trên đĩa).
 *
 * Thư là byte[] RFC 5322 nguyên vẹn (CRLF, 8bit, nhị phân) từ lúc lưu đến lúc đọc; engine KHÔNG đổi "\r\n" thành "\n"
 * và không đi qua String ở đường lưu/đọc chính. Các hàm String cũ chỉ là adapter @Deprecated (String <-> UTF-8).
 *
 * Định dạng file .enc (giữ nguyên như SecurityUtils.encrypt): Base64(iv 16B) + ":" + Base64(AES-CBC-PKCS5(plain)).
 *
 * Ba loại hòm thư của một người dùng "u": "u" (inbox), "u_sent", "u_spam". Cả ba chung một quota (của "u").
 */
public class MailStorageEngine {

    public static final class StoredMail {
        public final String id;
        /** Nội dung thư dạng String (UTF-8) - CHỈ để tương thích code cũ. Dùng rawBytes() để lấy byte nguyên vẹn. */
        public final String content;
        private final byte[] raw;

        /** Tương thích cũ: String -> UTF-8 byte. */
        public StoredMail(String id, String content) {
            this.id = id;
            this.content = content;
            this.raw = content == null ? null : content.getBytes(StandardCharsets.UTF_8);
        }

        private StoredMail(String id, byte[] raw, boolean fromBytes) {
            this.id = id;
            this.raw = raw;
            this.content = raw == null ? null : new String(raw, StandardCharsets.UTF_8);
        }

        /** Dựng từ byte nguyên vẹn (raw == null nghĩa là thư giải mã lỗi). */
        public static StoredMail ofBytes(String id, byte[] raw) {
            return new StoredMail(id, raw, true);
        }

        /** Byte nguyên vẹn của thư (null nếu giải mã lỗi). Không sửa mảng trả về. */
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

    // Thông tin gọn của một thư (không giữ nội dung trong RAM) phục vụ POP3 STAT/LIST/UIDL
    public static final class MailMeta {
        public final String id;
        /** Số byte THẬT của thư (byte[] RFC 5322 sau giải mã), không phải kích thước file .enc. */
        public final long sizeBytes;

        public MailMeta(String id, long sizeBytes) {
            this.id = id;
            this.sizeBytes = sizeBytes;
        }

        public long size() {
            return sizeBytes;
        }
    }

    // Kết quả lưu thư: cho phép tầng SMTP/Web phân biệt "vượt quota" với các lỗi khác
    public enum SaveResult { OK, NO_USER, OVER_QUOTA, ERROR }

    // ------------------------------------------------------------------------------------------------
    // Index trong RAM của MỘT hòm thư
    // ------------------------------------------------------------------------------------------------

    // Index trong RAM của MỘT hòm thư: tổng dung lượng + danh sách tên file .enc đã sắp xếp tăng dần.
    // Nạp lười bằng đúng một lần quét; sau đó chỉ được cập nhật khi lưu/xóa thư qua engine này.
    // Mọi thao tác đều synchronized trên chính index (đoạn găng rất ngắn, không làm I/O ngoài lần quét đầu).
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

        // Chưa nạp thì bỏ qua: lần quét đầu tiên sẽ thấy file mới trên đĩa
        synchronized void add(String name, long size) {
            if (!loaded) return;
            Long old = sizes.put(name, size);
            if (old == null) {
                int n = names.size();
                if (n == 0 || names.get(n - 1).compareTo(name) < 0) {
                    names.add(name); // trường hợp thường gặp: tên mới luôn lớn nhất (timestamp tăng dần)
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

        long totalBytes() {
            return totalBytes.get();
        }

        synchronized List<String> snapshotAscending() {
            return new ArrayList<>(names);
        }

        // Cắt một trang theo thứ tự GIẢM DẦN (mới nhất trước), total lấy cùng lúc để nhất quán
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

    // LRU cache có giới hạn, khóa dạng "hòm thư|tên file". File .enc bất biến nên giá trị không bao giờ cũ.
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

    // ------------------------------------------------------------------------------------------------
    // Trạng thái tĩnh
    // ------------------------------------------------------------------------------------------------

    private static final String SENT_SUFFIX = "_sent";
    private static final String SPAM_SUFFIX = "_spam";

    /** Thư rác cũ hơn ngần này thì bị dọn. */
    private static final long SPAM_MAX_AGE_MS = TimeUnit.DAYS.toMillis(30);
    /** Chu kỳ dọn thư rác nền. */
    private static final long SPAM_PURGE_PERIOD_MINUTES = 360;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final ConcurrentHashMap<String, MailboxIndex> indexes = new ConcurrentHashMap<>();
    private static final LruCache<MailSummary> SUMMARY_CACHE = new LruCache<>(ServerConfig.MAIL_SUMMARY_CACHE_MAX_ENTRIES);
    private static final LruCache<Long> META_CACHE = new LruCache<>(ServerConfig.MAIL_META_CACHE_MAX_ENTRIES);
    // Monitor theo người dùng để kiểm tra quota (inbox + sent + spam) nguyên tử giữa các hòm thư có lock khác nhau
    private static final ConcurrentHashMap<String, Object> quotaMonitors = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> userLocks = new ConcurrentHashMap<>();

    // Chỉ cho một lượt dọn thư rác chạy tại một thời điểm (luồng nền + gọi tay)
    private static final ReentrantLock PURGE_LOCK = new ReentrantLock();

    // ---- Điểm móc phục vụ test (package-private) ----

    /** Tra cứu người dùng; mặc định chuyển tiếp sang UserStore. Test thay bằng bản giả. */
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
    // Chỉ dùng cho test: đổi thư mục gốc dữ liệu (mặc định lấy từ ServerConfig.BASE_DATA_DIR)
    private static volatile Path baseDirOverride = null;
    // Chỉ dùng cho test: hạn mức quota theo byte (âm = dùng ServerConfig)
    private static volatile long quotaBytesOverride = -1;
    // Bộ giải mã header RFC 2047 (test thay để kiểm tra đường fallback)
    private static volatile Function<String, String> headerDecoder = MimeParser::decodeHeader;

    static void setBaseDirForTest(Path dir) {
        baseDirOverride = dir;
        indexes.clear();
        SUMMARY_CACHE.clear();
        META_CACHE.clear();
        quotaMonitors.clear();
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

    /** Trả mọi điểm móc test về mặc định và xóa trạng thái trong RAM. */
    static void resetTestHooks() {
        userDirectory = DEFAULT_USERS;
        quotaBytesOverride = -1;
        headerDecoder = MimeParser::decodeHeader;
        baseDirOverride = null;
        indexes.clear();
        SUMMARY_CACHE.clear();
        META_CACHE.clear();
        quotaMonitors.clear();
    }

    static boolean isWriteLockHeldByCurrentThread(String mailbox) {
        return getLockForUser(mailbox).isWriteLockedByCurrentThread();
    }

    // Luồng nền (daemon) dọn thư rác quá 30 ngày định kỳ
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
        }, 5, SPAM_PURGE_PERIOD_MINUTES, TimeUnit.MINUTES);
    }

    // ------------------------------------------------------------------------------------------------
    // Tiện ích đường dẫn / khóa / quota
    // ------------------------------------------------------------------------------------------------

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

    // Quên toàn bộ index/cache của một hòm thư (gọi khi đang giữ write-lock của hòm thư đó)
    private static void forgetMailbox(String mailbox) {
        String key = mailbox.trim();
        indexes.remove(key);
        SUMMARY_CACHE.removeByPrefixIgnoreCase(key + "|");
        META_CACHE.removeByPrefixIgnoreCase(key + "|");
    }

    private static Object quotaMonitor(String cleanUser) {
        return quotaMonitors.computeIfAbsent(cleanUser.trim().toLowerCase(Locale.ROOT), k -> new Object());
    }

    // Quota tính theo inbox + sent + spam cộng lại (khớp /storage-usage), cùng thước đo: kích thước file .enc
    private static long usedBytesOfUser(String cleanUser) {
        return loadedIndex(cleanUser).totalBytes()
                + loadedIndex(cleanUser + SENT_SUFFIX).totalBytes()
                + loadedIndex(cleanUser + SPAM_SUFFIX).totalBytes();
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

    // Kích thước file .enc dự kiến: "Base64(iv 16B)" + ":" + "Base64(AES-CBC PKCS5)" (đúng với định dạng của SecurityUtils.encrypt)
    static long estimateEncryptedSize(long plainBytes) {
        long cipherBytes = (plainBytes / 16 + 1) * 16;
        return 24 + 1 + ((cipherBytes + 2) / 3) * 4;
    }

    private static ReentrantReadWriteLock getLockForUser(String username) {
        return userLocks.computeIfAbsent(username, k -> new ReentrantReadWriteLock());
    }

    private static Path mailboxDir(String username) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Tên hòm thư không được để trống");
        }
        Path base = baseDir();
        Path dir = base.resolve(username.trim()).normalize();
        if (!dir.startsWith(base) || dir.equals(base)) {
            throw new IllegalArgumentException("Đường dẫn hòm thư không hợp lệ");
        }
        return dir;
    }

    public static void initMailbox(String username) {
        Path dirPath = mailboxDir(username);
        // Đường nhanh: thư mục đã có thì không cần khóa (trước đây mỗi lần liệt kê đều giành write-lock)
        if (Files.isDirectory(dirPath)) return;

        ReentrantReadWriteLock lock = getLockForUser(username);
        lock.writeLock().lock();
        try {
            File dir = dirPath.toFile();
            if (!dir.exists()) {
                dir.mkdirs();
                // Thư mục vừa được tạo mới (đăng ký lại / bị xóa ngoài engine): bỏ index cũ nếu còn sót
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

    // "u", "u_sent", "u_spam" -> "u"
    private static String getCleanUsername(String username) {
        if (username == null) return null;
        if (username.endsWith(SENT_SUFFIX)) {
            return username.substring(0, username.length() - SENT_SUFFIX.length());
        }
        if (username.endsWith(SPAM_SUFFIX)) {
            return username.substring(0, username.length() - SPAM_SUFFIX.length());
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

    private static SecretKey deriveKeyFor(String mailbox) throws Exception {
        byte[] saltBytes = getUserSaltOrThrow(mailbox);
        return SecurityUtils.deriveKey(ServerConfig.SECRET_KEY, saltBytes);
    }

    // ------------------------------------------------------------------------------------------------
    // Mã hóa / giải mã THEO BYTE (cùng định dạng file với SecurityUtils.encrypt: Base64(iv):Base64(cipher))
    // ------------------------------------------------------------------------------------------------

    private static byte[] encryptToFileBytes(byte[] plain, SecretKey key) throws Exception {
        byte[] iv = new byte[16];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
        byte[] cipherBytes = cipher.doFinal(plain);

        byte[] ivB64 = Base64.getEncoder().encode(iv);
        byte[] ctB64 = Base64.getEncoder().encode(cipherBytes);
        byte[] out = new byte[ivB64.length + 1 + ctB64.length];
        System.arraycopy(ivB64, 0, out, 0, ivB64.length);
        out[ivB64.length] = (byte) ':';
        System.arraycopy(ctB64, 0, out, ivB64.length + 1, ctB64.length);
        return out;
    }

    // Trả byte thư gốc; null nếu file hỏng / sai khóa
    private static byte[] decryptFileBytes(byte[] fileBytes, SecretKey key) {
        try {
            int colon = indexOfByte(fileBytes, (byte) ':', fileBytes.length);
            if (colon <= 0) return null;
            int end = fileBytes.length;
            while (end > colon + 1 && isAsciiSpace(fileBytes[end - 1])) end--; // bỏ CR/LF/space cuối file (nếu có)
            byte[] iv = Base64.getDecoder().decode(Arrays.copyOfRange(fileBytes, 0, colon));
            byte[] ct = Base64.getDecoder().decode(Arrays.copyOfRange(fileBytes, colon + 1, end));
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
            return cipher.doFinal(ct);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isAsciiSpace(byte b) {
        return b == '\r' || b == '\n' || b == ' ' || b == '\t';
    }

    private static int indexOfByte(byte[] b, byte v, int limit) {
        for (int i = 0; i < limit; i++) {
            if (b[i] == v) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------------------------------------
    // Thống kê dung lượng một hòm thư
    // ------------------------------------------------------------------------------------------------

    // Lấy từ bộ đếm trong RAM (O(1)); chỉ quét đĩa lần đầu tiên của mỗi hòm thư
    public static long getMailboxSizeInBytes(String username) {
        Path dir = mailboxDir(username);
        if (!Files.isDirectory(dir)) {
            // Thư mục không còn: nếu index cũ vẫn còn dữ liệu thì dọn dưới write-lock
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

    // ------------------------------------------------------------------------------------------------
    // LƯU THƯ
    // ------------------------------------------------------------------------------------------------

    /** @deprecated Dùng {@link #saveEmailResult(String, byte[])}. Adapter: String -> UTF-8 byte. */
    @Deprecated
    public static boolean saveEmail(String recipient, String rawEmailContent) {
        return saveEmailResult(recipient, rawEmailContent) == SaveResult.OK;
    }

    /** @deprecated Dùng {@link #saveEmailResult(String, byte[])}. Adapter: String -> UTF-8 byte (hành vi cũ). */
    @Deprecated
    public static SaveResult saveEmailResult(String recipient, String rawEmailContent) {
        if (recipient == null || rawEmailContent == null) {
            return SaveResult.ERROR;
        }
        return saveEmailResult(recipient, rawEmailContent.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Lưu thư byte-exact vào hòm thư "u", "u_sent" hoặc "u_spam".
     * Quota tính theo số byte thật (kích thước file mã hóa sinh ra từ đúng raw.length byte), chung cho cả ba hòm của user.
     */
    public static SaveResult saveEmailResult(String mailbox, byte[] raw) {
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

            // Kiểm tra nhanh quota bằng ước lượng kích thước sau mã hóa, TRƯỚC khi tốn CPU mã hóa và giữ lock
            long estimatedBytes = estimateEncryptedSize(raw.length);
            if (usedBytesOfUser(cleanRecipient) + estimatedBytes > maxSizeBytes) {
                System.err.println("[MAIL STORAGE] Hòm thư " + mailbox + " vượt Quota");
                return SaveResult.OVER_QUOTA;
            }

            // Mã hóa NGOÀI write-lock để không chặn các thao tác khác của cùng hòm thư
            SecretKey key = deriveKeyFor(mailbox);
            byte[] encryptedBytes = encryptToFileBytes(raw, key);
            String fileName = "msg_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".enc";

            // Chỉ hòm nhận thật (inbox) mới bắn SSE; "_sent" và "_spam" thì không
            String summaryJson = null;
            if (!isSentBox(mailbox) && !isSpamBox(mailbox)) {
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
                // Kiểm tra lại dưới lock: tài khoản có thể vừa bị xóa (tránh tạo lại thư mục/index cho user đã xóa)
                if (!userDirectory.exists(cleanRecipient)) {
                    return SaveResult.NO_USER;
                }

                synchronized (quotaMonitor(cleanRecipient)) {
                    long incomingBytes = encryptedBytes.length;
                    if (usedBytesOfUser(cleanRecipient) + incomingBytes > maxSizeBytes) {
                        System.err.println("[MAIL STORAGE] Hòm thư " + mailbox + " vượt Quota");
                        return SaveResult.OVER_QUOTA;
                    }

                    Path dir = mailboxDir(mailbox);
                    Path filePath = dir.resolve(fileName);
                    Path tmpPath = dir.resolve(fileName + ".tmp");

                    // 1. Ghi file mã hóa xuống đĩa (ghi file tạm rồi đổi tên để không ai thấy file ghi dở)
                    try {
                        Files.write(tmpPath, encryptedBytes);
                        try {
                            Files.move(tmpPath, filePath, StandardCopyOption.ATOMIC_MOVE);
                        } catch (AtomicMoveNotSupportedException e) {
                            Files.move(tmpPath, filePath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        try { Files.deleteIfExists(tmpPath); } catch (IOException ignored) {}
                        throw e;
                    }

                    // 2. Cập nhật index/bộ đếm trong RAM
                    loadedIndex(mailbox).add(fileName, incomingBytes);
                }
            } finally {
                lock.writeLock().unlock();
            }

            // 3. KÍCH HOẠT REALTIME EVENT: ngoài write-lock, chỉ MỘT lần, chỉ cho hòm thư nhận (inbox)
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

    // ------------------------------------------------------------------------------------------------
    // TÓM TẮT THƯ (chỉ đọc phần header)
    // ------------------------------------------------------------------------------------------------

    // Tối ưu hóa: Phân trang danh sách tóm tắt thư dựa trên index trong RAM, giải mã chỉ phần header (~12KB CBC)
    public static MailPage listMailSummaries(String username, int page, int limit) {
        initMailbox(username);
        ReentrantReadWriteLock lock = getLockForUser(username);
        lock.readLock().lock();

        try {
            Path dir = mailboxDir(username);
            long start = ((long) page - 1) * limit;
            SecretKey derivedKey = null;

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

                if (derivedKey == null) {
                    derivedKey = deriveKeyFor(username);
                }

                List<MailSummary> summaries = new ArrayList<>();
                boolean drift = false;
                for (String name : slice.names) {
                    Path filePath = dir.resolve(name);
                    if (!Files.exists(filePath)) {
                        // Index lệch với đĩa (file bị xóa ngoài engine): làm mới index rồi thử lại
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

                    byte[] headerBytes = readHeaderBytes(filePath.toFile(), derivedKey);
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
     * Đọc phần HEADER của một thư (byte, đã cắt trước dòng trống phân cách), không nạp cả thân thư lớn:
     * chỉ giải mã ~12KB đầu của file (CBC cho phép giải mã tiền tố). Không đủ thì giải mã toàn bộ làm dự phòng.
     * Trả null nếu file hỏng / không đọc được.
     */
    private static byte[] readHeaderBytes(File file, SecretKey derivedKey) {
        final int HEADER_READ_LIMIT = 12288; // Đọc ~12KB đầu tiên
        try {
            long fileLength = file.length();

            if (fileLength <= HEADER_READ_LIMIT) {
                byte[] plain = decryptFileBytes(Files.readAllBytes(file.toPath()), derivedKey);
                return plain == null ? null : cutHeader(plain);
            }

            byte[] buffer = new byte[HEADER_READ_LIMIT];
            int readBytes;
            try (FileInputStream fis = new FileInputStream(file)) {
                readBytes = fis.readNBytes(buffer, 0, buffer.length);
            }
            if (readBytes <= 0) return null;

            int colonIdx = indexOfByte(buffer, (byte) ':', readBytes);
            if (colonIdx > 0) {
                byte[] iv = Base64.getDecoder().decode(Arrays.copyOfRange(buffer, 0, colonIdx));

                // Cắt phần Base64 của bản mã để chiều dài chia hết cho 4
                int b64Len = ((readBytes - colonIdx - 1) / 4) * 4;
                if (b64Len > 0) {
                    byte[] rawCipherPartial = Base64.getDecoder()
                            .decode(Arrays.copyOfRange(buffer, colonIdx + 1, colonIdx + 1 + b64Len));

                    // Cắt về bội số 16 byte cho khối mã hóa AES
                    int validAesLen = (rawCipherPartial.length / 16) * 16;
                    if (validAesLen > 0) {
                        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
                        cipher.init(Cipher.DECRYPT_MODE, derivedKey, new IvParameterSpec(iv));
                        byte[] decryptedPartial = cipher.doFinal(rawCipherPartial, 0, validAesLen);

                        int end = findHeaderEnd(decryptedPartial);
                        if (end >= 0) {
                            return Arrays.copyOf(decryptedPartial, end);
                        }
                    }
                }
            }
            // Chưa thấy dòng trống phân cách trong ~12KB đầu: giải mã cả file làm phương án dự phòng
            return fullDecryptHeader(file, derivedKey);
        } catch (Exception e) {
            return fullDecryptHeader(file, derivedKey);
        }
    }

    private static byte[] fullDecryptHeader(File file, SecretKey derivedKey) {
        try {
            byte[] plain = decryptFileBytes(Files.readAllBytes(file.toPath()), derivedKey);
            return plain == null ? null : cutHeader(plain);
        } catch (Exception ignored) {
            return null;
        }
    }

    // Vị trí bắt đầu của dòng trống phân cách header/body (CRLFCRLF hoặc LFLF); -1 nếu chưa có
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

    // Phần header của thư (nếu không có dòng trống thì coi cả thư là header)
    private static byte[] cutHeader(byte[] raw) {
        int end = findHeaderEnd(raw);
        return end < 0 ? raw : Arrays.copyOf(raw, end);
    }

    // Giải mã header RFC 2047 an toàn: lỗi hoặc null thì dùng giá trị thô
    private static String safeDecodeHeader(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        try {
            String decoded = headerDecoder.apply(value);
            return decoded == null ? value : decoded;
        } catch (Exception e) {
            return value;
        }
    }

    // Phân tích header (đã mở gấp dòng) thành tóm tắt; sender/subject được giải mã RFC 2047
    private static MailSummary parseHeaderToSummary(String id, byte[] headerBytes) {
        String from = "";
        String to = "";
        String subject = "";
        String date = "";

        if (headerBytes != null && headerBytes.length > 0) {
            String text = new String(headerBytes, StandardCharsets.UTF_8);
            // Mở gấp dòng (RFC 5322 §2.2.3): CRLF/LF ngay trước khoảng trắng đầu dòng tiếp theo bị bỏ, giữ khoảng trắng
            text = text.replaceAll("\r?\n(?=[ \t])", "");
            String[] lines = text.split("\r?\n");
            for (String line : lines) {
                if (line.isEmpty()) break; // Kết thúc phần Header
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

    // ------------------------------------------------------------------------------------------------
    // ĐỌC THƯ
    // ------------------------------------------------------------------------------------------------

    // Giữ nguyên listMails cũ phục vụ giao thức POP3; nay đọc byte rồi giải mã byte, StoredMail mang rawBytes()
    public static List<StoredMail> listMails(String username) {
        initMailbox(username);
        List<StoredMail> mails = new ArrayList<>();
        ReentrantReadWriteLock lock = getLockForUser(username);
        lock.readLock().lock();

        try {
            SecretKey derivedKey = deriveKeyFor(username);
            File folder = mailboxDir(username).toFile();
            File[] files = folder.listFiles((dir, name) -> name.endsWith(".enc"));

            if (files != null && files.length > 0) {
                Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));

                for (File file : files) {
                    try {
                        byte[] fileBytes = Files.readAllBytes(file.toPath());
                        byte[] plain = decryptFileBytes(fileBytes, derivedKey);
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

    // Danh sách thư CŨ -> MỚI (thứ tự POP3) chỉ gồm id + kích thước, KHÔNG giữ nội dung thư trong RAM.
    // size = số byte thật của thư (sau giải mã), tính một lần rồi cache theo tên file (file .enc bất biến).
    // Không giữ read-lock cả phiên: chỉ khóa khi liệt kê tên file và khi đọc từng file.
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

            SecretKey derivedKey = null;
            boolean drift = false;

            for (String name : names) {
                String key = cacheKey(mailbox, name);
                Long cachedSize = META_CACHE.get(key);
                if (cachedSize != null) {
                    result.add(new MailMeta(name, cachedSize));
                    continue;
                }

                // Chưa có trong cache: đọc file này dưới read-lock ngắn, giải mã NGOÀI lock
                byte[] bytes;
                lock.readLock().lock();
                try {
                    bytes = Files.readAllBytes(dir.resolve(name));
                } catch (NoSuchFileException e) {
                    drift = true; // file đã bị xóa giữa chừng (hoặc index lệch): bỏ qua, làm mới index sau
                    continue;
                } catch (IOException e) {
                    result.add(new MailMeta(name, 0));
                    continue;
                } finally {
                    lock.readLock().unlock();
                }

                if (derivedKey == null) {
                    derivedKey = deriveKeyFor(mailbox);
                }

                byte[] plain = decryptFileBytes(bytes, derivedKey);
                if (plain == null) {
                    // Giống listMails/POP3 cũ: thư giải mã lỗi tính kích thước 0 (không cache để còn thử lại)
                    result.add(new MailMeta(name, 0));
                    continue;
                }
                long size = plain.length; // lấy độ dài rồi bỏ nội dung
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
        if (fileId == null || fileId.contains("/") || fileId.contains("\\") || fileId.contains("..")) {
            return null;
        }
        initMailbox(username);
        ReentrantReadWriteLock lock = getLockForUser(username);
        lock.readLock().lock();

        try {
            Path filePath = mailboxDir(username).resolve(fileId).normalize();
            File file = filePath.toFile();
            if (!file.exists()) return null;

            SecretKey derivedKey = deriveKeyFor(username);

            byte[] fileBytes = Files.readAllBytes(filePath);
            byte[] plain = decryptFileBytes(fileBytes, derivedKey);
            return StoredMail.ofBytes(file.getName(), plain);
        } catch (Exception e) {
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Toàn bộ thư (CŨ -> MỚI) dạng byte nguyên vẹn; bỏ qua thư giải mã lỗi. */
    public static List<byte[]> readAllEmailBytes(String username) {
        List<byte[]> out = new ArrayList<>();
        for (StoredMail m : listMails(username)) {
            if (m.rawBytes() != null) {
                out.add(m.rawBytes());
            }
        }
        return out;
    }

    /** @deprecated Dùng {@link #readAllEmailBytes(String)}. Adapter: byte -> String UTF-8. */
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

    // ------------------------------------------------------------------------------------------------
    // XÓA THƯ
    // ------------------------------------------------------------------------------------------------

    // Xóa một file thư (gọi khi ĐANG giữ write-lock của hòm thư). Trả true nếu file không còn trên đĩa.
    private static boolean deleteFileLocked(String mailbox, Path dir, String fileId) {
        if (fileId == null || fileId.contains("/") || fileId.contains("\\") || fileId.contains("..")) {
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
        // Cập nhật index/cache theo trạng thái thực tế trên đĩa (xóa lỗi thì file vẫn còn trong index)
        if (!Files.exists(filePath)) {
            MailboxIndex idx = indexes.get(mailbox.trim()); // lấy SAU khi xóa file để không lệch với lần quét lười
            if (idx != null) idx.remove(fileId);
            String key = cacheKey(mailbox, fileId);
            SUMMARY_CACHE.remove(key);
            META_CACHE.remove(key);
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
            lock.writeLock().unlock();
        }
    }

    // Tên file .enc của MỘT hòm thư theo thứ tự tăng dần (đọc thẳng từ đĩa, KHÔNG giải mã).
    // Thứ tự trùng với listMails/listMailMetas nên số thứ tự (POP3/giao diện) không lệch.
    private static List<String> listNamesOnDisk(String mailbox) {
        ReentrantReadWriteLock lock = getLockForUser(mailbox);
        lock.readLock().lock();
        try {
            File[] files = mailboxDir(mailbox).toFile().listFiles((d, name) -> name.endsWith(".enc"));
            List<String> names = new ArrayList<>();
            if (files != null) {
                for (File f : files) names.add(f.getName());
            }
            Collections.sort(names);
            return names;
        } finally {
            lock.readLock().unlock();
        }
    }

    // Xóa theo số thứ tự 1..N (cũ -> mới) TRONG PHẠM VI một hòm thư; các hòm _sent/_spam là danh sách riêng nên không bị lệch
    public static void deleteEmails(String username, Set<Integer> deletedIndices) {
        if (deletedIndices == null || deletedIndices.isEmpty()) return;
        initMailbox(username);
        List<String> names = listNamesOnDisk(username);
        List<String> idsToDelete = new ArrayList<>();
        for (int index : deletedIndices) {
            if (index >= 1 && index <= names.size()) {
                idsToDelete.add(names.get(index - 1));
            }
        }
        deleteMailsByIds(username, idsToDelete);
    }

    // Xóa thư mục inbox, _sent và _spam của người dùng DƯỚI write-lock, đồng thời dọn index/cache/lock của người dùng đó
    public static void deleteMailbox(String username) {
        purgeUser(username, true);
    }

    // Chỉ dọn index/cache/lock trong RAM (không đụng tới file). UserStore.deleteUser gọi hàm này để
    // tài khoản bị xóa rồi đăng ký lại cùng tên không bị dính quota/danh sách thư của người cũ.
    public static void evictUser(String username) {
        purgeUser(username, false);
    }

    private static void purgeUser(String username, boolean deleteFiles) {
        if (username == null || username.trim().isEmpty()) return;
        String base = getCleanUsername(username.trim());
        if (base == null || base.isEmpty()) return;
        String sent = base + SENT_SUFFIX;
        String spam = base + SPAM_SUFFIX;

        ReentrantReadWriteLock lockInbox = getLockForUser(base);
        ReentrantReadWriteLock lockSent = getLockForUser(sent);
        ReentrantReadWriteLock lockSpam = getLockForUser(spam);
        // Luôn khóa theo thứ tự inbox -> sent -> spam; các hàm lưu/xóa/dọn chỉ giữ một lock nên không thể bế tắc
        lockInbox.writeLock().lock();
        lockSent.writeLock().lock();
        lockSpam.writeLock().lock();
        try {
            if (deleteFiles) {
                try {
                    deleteDirectoryQuietly(mailboxDir(base));
                    deleteDirectoryQuietly(mailboxDir(sent));
                    deleteDirectoryQuietly(mailboxDir(spam));
                } catch (Exception e) {
                    System.err.println("[MAIL STORAGE] Lỗi khi xóa hòm thư " + base + ": " + e.getMessage());
                }
            }

            // Dọn index + cache (không phân biệt hoa/thường vì khóa user trong UserStore là chữ thường)
            indexes.keySet().removeIf(k -> k.equalsIgnoreCase(base) || k.equalsIgnoreCase(sent) || k.equalsIgnoreCase(spam));
            SUMMARY_CACHE.removeByPrefixIgnoreCase(base + "|");
            SUMMARY_CACHE.removeByPrefixIgnoreCase(sent + "|");
            SUMMARY_CACHE.removeByPrefixIgnoreCase(spam + "|");
            META_CACHE.removeByPrefixIgnoreCase(base + "|");
            META_CACHE.removeByPrefixIgnoreCase(sent + "|");
            META_CACHE.removeByPrefixIgnoreCase(spam + "|");
            quotaMonitors.keySet().removeIf(k -> k.equalsIgnoreCase(base));
        } finally {
            // Bỏ lock khỏi map trước khi mở khóa: luồng đến sau sẽ dùng lock mới (user đã xóa nên không còn gì để tranh chấp)
            userLocks.remove(base, lockInbox);
            userLocks.remove(sent, lockSent);
            userLocks.remove(spam, lockSpam);
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

    // ------------------------------------------------------------------------------------------------
    // DỌN THƯ RÁC QUÁ HẠN
    // ------------------------------------------------------------------------------------------------

    /**
     * Xóa thư trong MỌI hòm "*_spam" có thời điểm sửa file (mtime = lúc lưu) cũ hơn 30 ngày.
     * An toàn đa luồng: mỗi hòm được dọn dưới write-lock của chính nó; chỉ một lượt dọn chạy tại một thời điểm.
     * Cũng được luồng nền (daemon) gọi định kỳ.
     *
     * @return số thư đã xóa
     */
    public static int purgeOldSpam() {
        return purgeOldSpam(System.currentTimeMillis(), SPAM_MAX_AGE_MS);
    }

    // Bản có đồng hồ/hạn mức tùy chỉnh để test
    static int purgeOldSpam(long nowMs, long maxAgeMs) {
        PURGE_LOCK.lock();
        try {
            File[] dirs = baseDir().toFile().listFiles(f -> f.isDirectory() && f.getName().endsWith(SPAM_SUFFIX));
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
                    System.err.println("[MAIL STORAGE] Lỗi khi dọn thư rác của " + mailbox + ": " + e.getMessage());
                } finally {
                    lock.writeLock().unlock();
                }
            }
            return deleted;
        } finally {
            PURGE_LOCK.unlock();
        }
    }

    // ------------------------------------------------------------------------------------------------
    // THỐNG KÊ TOÀN HỆ THỐNG
    // ------------------------------------------------------------------------------------------------

    // Đếm số thư của các inbox (không tính _sent, _spam)
    public static long getTotalMailCount() {
        File baseDir = baseDir().toFile();
        if (!baseDir.exists() || !baseDir.isDirectory()) return 0;

        long count = 0;
        File[] userDirs = baseDir.listFiles((dir, name) -> new File(dir, name).isDirectory()
                && !name.endsWith(SENT_SUFFIX) && !name.endsWith(SPAM_SUFFIX));
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

    // Tổng dung lượng mọi hòm thư (inbox + _sent + _spam: duyệt mọi thư mục con)
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

    // Escape chuỗi để nhúng vào JSON (ký tự điều khiển được thay bằng dấu cách)
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
}