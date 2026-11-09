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

public class MailStorageEngine {

    public static final class StoredMail {
        public final String id;

        public final String content;
        private final byte[] raw;

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

    private static final String SENT_SUFFIX = "_sent";
    private static final String SPAM_SUFFIX = "_spam";
    private static final String DRAFTS_SUFFIX = "_drafts";
    private static final String TRASH_SUFFIX = "_trash";
    public static final long DEFAULT_QUOTA_BYTES = 100L * 1024L * 1024L;

    private static final long SPAM_MAX_AGE_MS = TimeUnit.DAYS.toMillis(30);

    private static final long SPAM_PURGE_PERIOD_MINUTES = 360;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final ConcurrentHashMap<String, MailboxIndex> indexes = new ConcurrentHashMap<>();
    private static final LruCache<MailSummary> SUMMARY_CACHE = new LruCache<>(ServerConfig.MAIL_SUMMARY_CACHE_MAX_ENTRIES);
    private static final LruCache<Long> META_CACHE = new LruCache<>(ServerConfig.MAIL_META_CACHE_MAX_ENTRIES);

    private static final ConcurrentHashMap<String, Object> quotaMonitors = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> userLocks = new ConcurrentHashMap<>();

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
        SUMMARY_CACHE.removeByPrefixIgnoreCase(key + "|");
        META_CACHE.removeByPrefixIgnoreCase(key + "|");
    }

    private static Object quotaMonitor(String cleanUser) {
        return quotaMonitors.computeIfAbsent(cleanUser.trim().toLowerCase(Locale.ROOT), k -> new Object());
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

        if (Files.isDirectory(dirPath)) return;

        ReentrantReadWriteLock lock = getLockForUser(username);
        lock.writeLock().lock();
        try {
            File dir = dirPath.toFile();
            if (!dir.exists()) {
                dir.mkdirs();

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

    private static SecretKey deriveKeyFor(String mailbox) throws Exception {
        byte[] saltBytes = getUserSaltOrThrow(mailbox);
        return SecurityUtils.deriveKey(ServerConfig.SECRET_KEY, saltBytes);
    }

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

    private static byte[] decryptFileBytes(byte[] fileBytes, SecretKey key) {
        try {
            int colon = indexOfByte(fileBytes, (byte) ':', fileBytes.length);
            if (colon <= 0) return null;
            int end = fileBytes.length;
            while (end > colon + 1 && isAsciiSpace(fileBytes[end - 1])) end--;
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

        if (!isSentBox(mailbox) && !isSpamBox(mailbox) && !isDraftsBox(mailbox) && !isTrashBox(mailbox)) {
            if (isSenderBlockedFromRaw(cleanRecipient, raw)) {
                mailbox = cleanRecipient + SPAM_SUFFIX;
                System.out.println("[MAIL STORAGE] Tự động chuyển thư từ người gửi bị hạn chế vào Thư rác: " + mailbox);
            }
        }

        try {
            initMailbox(mailbox);

            long maxSizeBytes = maxQuotaBytes();

            long estimatedBytes = estimateEncryptedSize(raw.length);
            if (usedBytesOfUser(cleanRecipient) + estimatedBytes > maxSizeBytes) {
                System.err.println("[MAIL STORAGE] Hòm thư " + mailbox + " vượt Quota");
                return SaveResult.OVER_QUOTA;
            }

            SecretKey key = deriveKeyFor(mailbox);
            byte[] encryptedBytes = encryptToFileBytes(raw, key);
            String fileName = "msg_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".enc";

            String summaryJson = null;
            if (!isSentBox(mailbox) && !isSpamBox(mailbox) && !isDraftsBox(mailbox) && !isTrashBox(mailbox)) {
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
                    if (usedBytesOfUser(cleanRecipient) + incomingBytes > maxSizeBytes) {
                        System.err.println("[MAIL STORAGE] Hòm thư " + mailbox + " vượt Quota");
                        return SaveResult.OVER_QUOTA;
                    }

                    Path dir = mailboxDir(mailbox);
                    Path filePath = dir.resolve(fileName);
                    Path tmpPath = dir.resolve(fileName + ".tmp");

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

                    loadedIndex(mailbox).add(fileName, incomingBytes);
                }
            } finally {
                lock.writeLock().unlock();
            }

            if (summaryJson != null) {
                try {
                    com.pbl4.mailserver.webapi.SseManager.notifyUser(cleanRecipient, summaryJson);
                } catch (Exception e) {
                    System.err.println("[MAIL STORAGE] Lỗi khi bắn SSE: " + e.getMessage());
                }
            } else if (isSpamBox(mailbox)) {
                try {
                    com.pbl4.mailserver.webapi.SseManager.notifyStateChange(cleanRecipient, "NEW_SPAM", fileName, "spam");
                } catch (Exception ignored) {}
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

    private static byte[] readHeaderBytes(File file, SecretKey derivedKey) {
        final int HEADER_READ_LIMIT = 12288;
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

                int b64Len = ((readBytes - colonIdx - 1) / 4) * 4;
                if (b64Len > 0) {
                    byte[] rawCipherPartial = Base64.getDecoder()
                            .decode(Arrays.copyOfRange(buffer, colonIdx + 1, colonIdx + 1 + b64Len));

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

                if (derivedKey == null) {
                    derivedKey = deriveKeyFor(mailbox);
                }

                byte[] plain = decryptFileBytes(bytes, derivedKey);
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

    public static List<byte[]> readAllEmailBytes(String username) {
        List<byte[]> out = new ArrayList<>();
        for (StoredMail m : listMails(username)) {
            if (m.rawBytes() != null) {
                out.add(m.rawBytes());
            }
        }
        return out;
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
            Path metaPath = dir.resolve("meta").resolve(fileId + ".meta").normalize();
            if (metaPath.startsWith(dir)) Files.deleteIfExists(metaPath);
        } catch (IOException e) {
            System.err.println("[MAIL STORAGE] Lỗi khi xóa file " + fileId + ": " + e.getMessage());
        }

        if (!Files.exists(filePath)) {
            MailboxIndex idx = indexes.get(mailbox.trim());
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

            indexes.keySet().removeIf(k -> k.equalsIgnoreCase(base) || k.equalsIgnoreCase(sent) || k.equalsIgnoreCase(spam) || k.equalsIgnoreCase(drafts) || k.equalsIgnoreCase(trash));
            SUMMARY_CACHE.removeByPrefixIgnoreCase(base + "|");
            SUMMARY_CACHE.removeByPrefixIgnoreCase(sent + "|");
            SUMMARY_CACHE.removeByPrefixIgnoreCase(spam + "|");
            SUMMARY_CACHE.removeByPrefixIgnoreCase(drafts + "|");
            SUMMARY_CACHE.removeByPrefixIgnoreCase(trash + "|");
            META_CACHE.removeByPrefixIgnoreCase(base + "|");
            META_CACHE.removeByPrefixIgnoreCase(sent + "|");
            META_CACHE.removeByPrefixIgnoreCase(spam + "|");
            META_CACHE.removeByPrefixIgnoreCase(drafts + "|");
            META_CACHE.removeByPrefixIgnoreCase(trash + "|");
            quotaMonitors.keySet().removeIf(k -> k.equalsIgnoreCase(base));
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

    public static long getUsedQuotaBytes(String username) {
        if (username == null || username.trim().isEmpty()) return 0;
        String clean = getCleanUsername(username.trim());
        return getMailboxSizeInBytes(clean)
                + getMailboxSizeInBytes(clean + SENT_SUFFIX)
                + getMailboxSizeInBytes(clean + DRAFTS_SUFFIX)
                + getMailboxSizeInBytes(clean + SPAM_SUFFIX)
                + getMailboxSizeInBytes(clean + TRASH_SUFFIX);
    }

    public static boolean hasQuotaAvailable(String username, long incomingBytes) {
        if (username == null) return false;
        long estimated = estimateEncryptedSize(Math.max(0, incomingBytes));
        return (getUsedQuotaBytes(username) + estimated) <= maxQuotaBytes();
    }

    public static boolean moveEmail(String sourceMailbox, String targetMailbox, String fileId) {
        if (sourceMailbox == null || targetMailbox == null || fileId == null) return false;
        try {
            StoredMail mail = getSingleMail(sourceMailbox, fileId);
            if (mail == null || mail.rawBytes() == null) return false;

            java.util.Properties oldFlags = getMailFlags(sourceMailbox, fileId);
            SaveResult sr = saveEmailResult(targetMailbox, mail.rawBytes());
            if (sr == SaveResult.OK) {

                List<String> targetFiles = loadedIndex(targetMailbox).snapshotAscending();
                if (!targetFiles.isEmpty()) {
                    String newId = targetFiles.get(targetFiles.size() - 1);
                    Boolean seen = oldFlags.containsKey("seen") ? Boolean.parseBoolean(oldFlags.getProperty("seen")) : null;
                    Boolean starred = oldFlags.containsKey("starred") ? Boolean.parseBoolean(oldFlags.getProperty("starred")) : null;
                    if (seen != null || starred != null) {
                        updateMailFlags(targetMailbox, newId, seen, starred);
                    }
                }
                deleteMailsByIds(sourceMailbox, Collections.singletonList(fileId));
                return true;
            }
        } catch (Exception e) {
            System.err.println("[MAIL STORAGE] Lỗi khi chuyển thư " + fileId + ": " + e.getMessage());
        }
        return false;
    }

    public static boolean updateMailFlags(String mailbox, String fileId, Boolean seen, Boolean starred) {
        if (mailbox == null || fileId == null || fileId.contains("/") || fileId.contains("\\") || fileId.contains("..")) {
            return false;
        }
        initMailbox(mailbox);
        ReentrantReadWriteLock lock = getLockForUser(mailbox);
        lock.writeLock().lock();
        try {
            Path dir = mailboxDir(mailbox);
            Path mailFile = dir.resolve(fileId).normalize();
            if (!mailFile.startsWith(dir) || !Files.exists(mailFile)) {
                return false;
            }
            Path metaDir = dir.resolve("meta");
            Files.createDirectories(metaDir);
            Path metaFile = metaDir.resolve(fileId + ".meta").normalize();
            if (!metaFile.startsWith(metaDir)) return false;

            java.util.Properties props = new java.util.Properties();
            if (Files.exists(metaFile)) {
                try ( java.io.InputStream in = Files.newInputStream(metaFile)) {
                    props.load(in);
                }
            }
            if (seen != null) props.setProperty("seen", seen.toString());
            if (starred != null) props.setProperty("starred", starred.toString());

            try (java.io.OutputStream out = Files.newOutputStream(metaFile)) {
                props.store(out, "Mail Flags");
            }
            return true;
        } catch (IOException e) {
            System.err.println("[MAIL STORAGE] Lỗi cập nhật cờ thư " + fileId + ": " + e.getMessage());
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static java.util.Properties getMailFlags(String mailbox, String fileId) {
        java.util.Properties props = new java.util.Properties();
        if (mailbox == null || fileId == null || fileId.contains("/") || fileId.contains("\\") || fileId.contains("..")) {
            return props;
        }
        try {
            Path dir = mailboxDir(mailbox);
            Path metaFile = dir.resolve("meta").resolve(fileId + ".meta").normalize();
            if (metaFile.startsWith(dir) && Files.exists(metaFile)) {
                try (java.io.InputStream in = Files.newInputStream(metaFile)) {
                    props.load(in);
                }
            }
        } catch (Exception ignored) {}
        return props;
    }

    public static String extractCleanEmail(String rawAddress) {
        if (rawAddress == null) return "";
        String s = rawAddress.trim();
        int lt = s.lastIndexOf('<');
        int gt = s.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            s = s.substring(lt + 1, gt).trim();
        }

        String[] parts = s.split("[\\s,;]+");
        if (parts.length > 0) s = parts[0].trim();
        s = s.replaceAll("[\\s\"'<>]", "").toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return "";
        if (!s.contains("@")) s = s + "@" + ServerConfig.MAIL_DOMAIN.toLowerCase(Locale.ROOT);
        return s;
    }

    public static boolean isSenderBlockedFromRaw(String recipientUser, byte[] raw) {
        if (recipientUser == null || raw == null || raw.length == 0) return false;
        try {
            MailSummary sum = parseHeaderToSummary("", cutHeader(raw));
            String senderEmail = extractCleanEmail(sum.sender);
            String cleanRcpt = extractCleanEmail(recipientUser);
            if (!senderEmail.isEmpty() && isSenderBlocked(cleanRcpt, senderEmail)) {
                return true;
            }

            if (!senderEmail.isEmpty() && !cleanRcpt.isEmpty() && isSenderBlocked(senderEmail, cleanRcpt)) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean addBlockedSender(String username, String senderRaw) {
        String cleanUser = getCleanUsername(username);
        String sender = extractCleanEmail(senderRaw);
        if (cleanUser == null || cleanUser.isEmpty() || sender.isEmpty()) return false;

        initMailbox(cleanUser);
        ReentrantReadWriteLock lock = getLockForUser(cleanUser);
        lock.writeLock().lock();
        try {
            Path file = mailboxDir(cleanUser).resolve("blocked_senders.txt");
            Set<String> set = new java.util.LinkedHashSet<>();
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String t = line.trim().toLowerCase(Locale.ROOT);
                    if (!t.isEmpty()) set.add(t);
                }
            }
            set.add(sender);
            Files.write(file, set, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            System.err.println("[MAIL STORAGE] Lỗi lưu danh sách chặn thư rác: " + e.getMessage());
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static boolean removeBlockedSender(String username, String senderRaw) {
        String cleanUser = getCleanUsername(username);
        String sender = extractCleanEmail(senderRaw);
        if (cleanUser == null || cleanUser.isEmpty() || sender.isEmpty()) return false;

        ReentrantReadWriteLock lock = getLockForUser(cleanUser);
        lock.writeLock().lock();
        try {
            Path file = mailboxDir(cleanUser).resolve("blocked_senders.txt");
            if (!Files.exists(file)) return true;
            Set<String> set = new java.util.LinkedHashSet<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String t = line.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty() && !t.equals(sender)) set.add(t);
            }
            Files.write(file, set, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static boolean isSenderBlocked(String username, String senderRaw) {
        String cleanUser = getCleanUsername(username);
        String sender = extractCleanEmail(senderRaw);
        if (cleanUser == null || cleanUser.isEmpty() || sender.isEmpty()) return false;

        ReentrantReadWriteLock lock = getLockForUser(cleanUser);
        lock.readLock().lock();
        try {
            Path file = mailboxDir(cleanUser).resolve("blocked_senders.txt");
            if (!Files.exists(file)) return false;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (sender.equals(line.trim().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }
}
