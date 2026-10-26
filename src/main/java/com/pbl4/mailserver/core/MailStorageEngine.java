package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MailStorageEngine {

    public static final class StoredMail {
        public final String id;
        public final String content;

        public StoredMail(String id, String content) {
            this.id = id;
            this.content = content;
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

    // ---- PHẦN 1 ----
    // Thông tin gọn của một thư (không giữ nội dung trong RAM) phục vụ POP3 STAT/LIST/UIDL
    public static final class MailMeta {
        public final String id;
        public final long sizeBytes;

        public MailMeta(String id, long sizeBytes) {
            this.id = id;
            this.sizeBytes = sizeBytes;
        }
    }

    // Kết quả lưu thư: cho phép tầng SMTP/Web phân biệt "vượt quota" với các lỗi khác
    public enum SaveResult { OK, NO_USER, OVER_QUOTA, ERROR }

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

    private static final ConcurrentHashMap<String, MailboxIndex> indexes = new ConcurrentHashMap<>();
    private static final LruCache<MailSummary> SUMMARY_CACHE = new LruCache<>(ServerConfig.MAIL_SUMMARY_CACHE_MAX_ENTRIES);
    private static final LruCache<Long> META_CACHE = new LruCache<>(ServerConfig.MAIL_META_CACHE_MAX_ENTRIES);
    // Monitor theo người dùng để kiểm tra quota (inbox + sent) nguyên tử giữa hai hòm thư có hai lock khác nhau
    private static final ConcurrentHashMap<String, Object> quotaMonitors = new ConcurrentHashMap<>();

    // Chỉ dùng cho test: đổi thư mục gốc dữ liệu (mặc định lấy từ ServerConfig.BASE_DATA_DIR)
    private static volatile Path baseDirOverride = null;

    static void setBaseDirForTest(Path dir) {
        baseDirOverride = dir;
        indexes.clear();
        SUMMARY_CACHE.clear();
        META_CACHE.clear();
        quotaMonitors.clear();
    }

    static boolean isWriteLockHeldByCurrentThread(String mailbox) {
        return getLockForUser(mailbox).isWriteLockedByCurrentThread();
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

    // Quota tính theo inbox + sent cộng lại (khớp /storage-usage), cùng thước đo: kích thước file .enc
    private static long usedBytesOfUser(String cleanUser) {
        return loadedIndex(cleanUser).totalBytes() + loadedIndex(cleanUser + "_sent").totalBytes();
    }

    private static long maxQuotaBytes() {
        try {
            return Math.multiplyExact(ServerConfig.MAX_MAILBOX_QUOTA_MB, 1024L * 1024L);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    // Kích thước file .enc dự kiến: "Base64(iv 16B)" + ":" + "Base64(AES-CBC PKCS5)" (đúng với định dạng của SecurityUtils.encrypt)
    private static long estimateEncryptedSize(long plainBytes) {
        long cipherBytes = (plainBytes / 16 + 1) * 16;
        return 24 + 1 + ((cipherBytes + 2) / 3) * 4;
    }

    // Số byte UTF-8 của chuỗi mà không phải cấp phát mảng byte (khớp String.getBytes(UTF_8))
    private static long utf8Length(String s) {
        long n = 0;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                n += 1;
            } else if (c < 0x800) {
                n += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                n += 4;
                i++;
            } else if (Character.isSurrogate(c)) {
                n += 1; // surrogate lẻ được mã hóa thành '?'
            } else {
                n += 3;
            }
        }
        return n;
    }

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> userLocks = new ConcurrentHashMap<>();

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

    private static String getCleanUsername(String username) {
        if (username != null && username.endsWith("_sent")) {
            return username.substring(0, username.length() - 5);
        }
        return username;
    }

    private static byte[] getUserSaltOrThrow(String username) throws IllegalArgumentException {
        String cleanUser = getCleanUsername(username);
        String base64Salt = UserStore.findSalt(cleanUser);

        if (base64Salt == null || base64Salt.trim().isEmpty()) {
            throw new IllegalArgumentException("Không tìm thấy Salt cho tài khoản: " + cleanUser);
        }

        return Base64.getDecoder().decode(base64Salt);
    }

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

    public static boolean saveEmail(String recipient, String rawEmailContent) {
        return saveEmailResult(recipient, rawEmailContent) == SaveResult.OK;
    }

    public static SaveResult saveEmailResult(String recipient, String rawEmailContent) {
        if (recipient == null || rawEmailContent == null) {
            return SaveResult.ERROR;
        }
        String cleanRecipient = getCleanUsername(recipient);

        if (!UserStore.exists(cleanRecipient)) {
            System.err.println("[MAIL STORAGE] Người nhận không tồn tại: " + cleanRecipient);
            return SaveResult.NO_USER;
        }

        try {
            initMailbox(recipient);

            long maxSizeBytes = maxQuotaBytes();

            // Kiểm tra nhanh quota bằng ước lượng kích thước sau mã hóa, TRƯỚC khi tốn CPU mã hóa và giữ lock
            long estimatedBytes = estimateEncryptedSize(utf8Length(rawEmailContent));
            if (usedBytesOfUser(cleanRecipient) + estimatedBytes > maxSizeBytes) {
                System.err.println("[MAIL STORAGE] Hòm thư " + recipient + " vượt Quota");
                return SaveResult.OVER_QUOTA;
            }

            // Mã hóa NGOÀI write-lock để không chặn các thao tác khác của cùng hòm thư
            byte[] saltBytes = getUserSaltOrThrow(recipient);
            String encryptedContent = SecurityUtils.encrypt(rawEmailContent, ServerConfig.SECRET_KEY, saltBytes);
            byte[] encryptedBytes = encryptedContent.getBytes(StandardCharsets.UTF_8);
            String fileName = "msg_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".enc";

            // Hòm thư kết thúc bằng "_sent" (bản sao thư đã gửi) thì KHÔNG bắn SSE
            boolean isSentBox = recipient.endsWith("_sent");
            String summaryJson = null;
            if (!isSentBox) {
                String senderFromRaw = parseFromFromRaw(rawEmailContent);
                summaryJson = String.format(
                    "{\"id\":\"%s\",\"sender\":\"%s\",\"recipient\":\"%s\",\"subject\":\"%s\",\"timestamp\":\"%s\"}",
                    escapeJson(fileName),
                    escapeJson(senderFromRaw.isEmpty() ? cleanRecipient : senderFromRaw),
                    escapeJson(recipient),
                    escapeJson(parseSubjectFromRaw(rawEmailContent)),
                    escapeJson(parseDateFromRaw(rawEmailContent))
                );
            }

            ReentrantReadWriteLock lock = getLockForUser(recipient);
            lock.writeLock().lock();
            try {
                // Kiểm tra lại dưới lock: tài khoản có thể vừa bị xóa (tránh tạo lại thư mục/index cho user đã xóa)
                if (!UserStore.exists(cleanRecipient)) {
                    return SaveResult.NO_USER;
                }

                synchronized (quotaMonitor(cleanRecipient)) {
                    long incomingBytes = encryptedBytes.length;
                    if (usedBytesOfUser(cleanRecipient) + incomingBytes > maxSizeBytes) {
                        System.err.println("[MAIL STORAGE] Hòm thư " + recipient + " vượt Quota");
                        return SaveResult.OVER_QUOTA;
                    }

                    Path dir = mailboxDir(recipient);
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
                    loadedIndex(recipient).add(fileName, incomingBytes);
                }
            } finally {
                lock.writeLock().unlock();
            }

            // 3. KÍCH HOẠT REALTIME EVENT: ngoài write-lock, chỉ MỘT lần, chỉ cho hòm thư nhận (không phải _sent)
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
                    byte[] saltBytes = getUserSaltOrThrow(username);
                    derivedKey = SecurityUtils.deriveKey(ServerConfig.SECRET_KEY, saltBytes);
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

                    String headerStr = readAndDecryptHeaderPartial(filePath.toFile(), derivedKey);
                    if (headerStr != null) {
                        MailSummary summary = parseHeaderToSummary(name, headerStr);
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

    private static String readAndDecryptHeaderPartial(File file, SecretKey derivedKey) {
        try {
            long fileLength = file.length();
            final int HEADER_READ_LIMIT = 12288; // Đọc ~12KB đầu tiên

            if (fileLength <= HEADER_READ_LIMIT) {
                byte[] fullBytes = Files.readAllBytes(file.toPath());
                String encryptedData = new String(fullBytes, StandardCharsets.UTF_8);
                return SecurityUtils.decryptWithKey(encryptedData, derivedKey);
            }

            byte[] buffer = new byte[HEADER_READ_LIMIT];
            int readBytes;
            try (FileInputStream fis = new FileInputStream(file)) {
                readBytes = fis.read(buffer);
            }

            if (readBytes <= 0) return null;
            String partialStr = new String(buffer, 0, readBytes, StandardCharsets.UTF_8);

            int colonIdx = partialStr.indexOf(':');
            if (colonIdx == -1) return null;

            String ivStr = partialStr.substring(0, colonIdx);
            String cipherB64Partial = partialStr.substring(colonIdx + 1);

            byte[] iv = Base64.getDecoder().decode(ivStr);

            // Cắt chuỗi Base64 phần mã hóa để chiều dài chia hết cho 4
            int validB64Len = (cipherB64Partial.length() / 4) * 4;
            if (validB64Len == 0) return null;

            byte[] rawCipherPartial = Base64.getDecoder().decode(cipherB64Partial.substring(0, validB64Len));

            // Cắt về bội số 16 byte cho khối mã hóa AES
            int validAesLen = (rawCipherPartial.length / 16) * 16;
            if (validAesLen == 0) return null;

            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, derivedKey, new IvParameterSpec(iv));

            byte[] decryptedPartial = cipher.doFinal(rawCipherPartial, 0, validAesLen);
            String decryptedHeader = new String(decryptedPartial, StandardCharsets.UTF_8);

            // Kiểm tra xem đã chứa dòng trống phân cách Header và Body hay chưa
            if (decryptedHeader.contains("\n\n") || decryptedHeader.contains("\r\n\r\n")) {
                return decryptedHeader;
            }

            // Nếu chưa tìm thấy dòng trống, giải mã cả file làm phương án dự phòng
            byte[] fullBytes = Files.readAllBytes(file.toPath());
            String encryptedData = new String(fullBytes, StandardCharsets.UTF_8);
            return SecurityUtils.decryptWithKey(encryptedData, derivedKey);

        } catch (Exception e) {
            try {
                byte[] fullBytes = Files.readAllBytes(file.toPath());
                String encryptedData = new String(fullBytes, StandardCharsets.UTF_8);
                return SecurityUtils.decryptWithKey(encryptedData, derivedKey);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static MailSummary parseHeaderToSummary(String id, String rawContent) {
        String from = "";
        String to = "";
        String subject = "";
        String date = "";

        if (rawContent != null) {
            String[] lines = rawContent.split("\r?\n");
            for (String line : lines) {
                if (line.isEmpty()) break; // Kết thúc phần Header
                String lower = line.toLowerCase();
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
        return new MailSummary(id, from, to, subject, date);
    }

    // Giữ nguyên listMails cũ phục vụ giao thức POP3
    public static List<StoredMail> listMails(String username) {
        initMailbox(username);
        List<StoredMail> mails = new ArrayList<>();
        ReentrantReadWriteLock lock = getLockForUser(username);
        lock.readLock().lock();

        try {
            byte[] saltBytes = getUserSaltOrThrow(username);
            File folder = mailboxDir(username).toFile();
            File[] files = folder.listFiles((dir, name) -> name.endsWith(".enc"));

            if (files != null && files.length > 0) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                SecretKey derivedKey = SecurityUtils.deriveKey(ServerConfig.SECRET_KEY, saltBytes);

                for (File file : files) {
                    try {
                        byte[] bytes = Files.readAllBytes(file.toPath());
                        String encryptedData = new String(bytes, StandardCharsets.UTF_8);
                        String decryptedContent = SecurityUtils.decryptWithKey(encryptedData, derivedKey);
                        mails.add(new StoredMail(file.getName(), decryptedContent));
                    } catch (Exception e) {
                        mails.add(new StoredMail(file.getName(), null));
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
    // sizeBytes = số byte UTF-8 của nội dung đã giải mã, tính một lần rồi cache theo tên file (file .enc bất biến).
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
                    byte[] saltBytes = getUserSaltOrThrow(mailbox);
                    derivedKey = SecurityUtils.deriveKey(ServerConfig.SECRET_KEY, saltBytes);
                }

                String content = SecurityUtils.decryptWithKey(new String(bytes, StandardCharsets.UTF_8), derivedKey);
                if (content == null) {
                    // Giống listMails/POP3 cũ: thư giải mã lỗi tính kích thước 0 (không cache để còn thử lại)
                    result.add(new MailMeta(name, 0));
                    continue;
                }
                long size = utf8Length(content); // lấy độ dài rồi bỏ nội dung
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

            byte[] saltBytes = getUserSaltOrThrow(username);
            SecretKey derivedKey = SecurityUtils.deriveKey(ServerConfig.SECRET_KEY, saltBytes);

            byte[] bytes = Files.readAllBytes(filePath);
            String encryptedData = new String(bytes, StandardCharsets.UTF_8);
            String decryptedContent = SecurityUtils.decryptWithKey(encryptedData, derivedKey);
            return new StoredMail(file.getName(), decryptedContent);
        } catch (Exception e) {
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

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

    public static void deleteMailsByIds(String username, Collection<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) return;
        initMailbox(username);
        ReentrantReadWriteLock lock = getLockForUser(username);
        lock.writeLock().lock();

        try {
            Path dir = mailboxDir(username);
            for (String fileId : fileIds) {
                if (fileId == null || fileId.contains("/") || fileId.contains("\\") || fileId.contains("..")) {
                    continue;
                }
                Path filePath = dir.resolve(fileId).normalize();
                if (filePath.startsWith(dir)) {
                    try {
                        Files.deleteIfExists(filePath);
                    } catch (IOException e) {
                        System.err.println("[MAIL STORAGE] Lỗi khi xóa file " + fileId + ": " + e.getMessage());
                    }
                    // Cập nhật index/cache theo trạng thái thực tế trên đĩa (xóa lỗi thì file vẫn còn trong index)
                    if (!Files.exists(filePath)) {
                        MailboxIndex idx = indexes.get(username.trim()); // lấy SAU khi xóa file để không lệch với lần quét lười
                        if (idx != null) idx.remove(fileId);
                        String key = cacheKey(username, fileId);
                        SUMMARY_CACHE.remove(key);
                        META_CACHE.remove(key);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MAIL STORAGE] Lỗi khi xóa thư: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static void deleteEmails(String username, Set<Integer> deletedIndices) {
        List<StoredMail> mails = listMails(username);
        List<String> idsToDelete = new ArrayList<>();
        for (int index : deletedIndices) {
            if (index >= 1 && index <= mails.size()) {
                idsToDelete.add(mails.get(index - 1).id);
            }
        }
        deleteMailsByIds(username, idsToDelete);
    }

    // Xóa thư mục inbox và _sent của người dùng DƯỚI write-lock, đồng thời dọn index/cache/lock của người dùng đó
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
        String sent = base + "_sent";

        ReentrantReadWriteLock lockInbox = getLockForUser(base);
        ReentrantReadWriteLock lockSent = getLockForUser(sent);
        // Luôn khóa theo thứ tự inbox -> sent; các hàm lưu/xóa chỉ giữ một lock nên không thể bế tắc
        lockInbox.writeLock().lock();
        lockSent.writeLock().lock();
        try {
            if (deleteFiles) {
                try {
                    deleteDirectoryQuietly(mailboxDir(base));
                    deleteDirectoryQuietly(mailboxDir(sent));
                } catch (Exception e) {
                    System.err.println("[MAIL STORAGE] Lỗi khi xóa hòm thư " + base + ": " + e.getMessage());
                }
            }

            // Dọn index + cache (không phân biệt hoa/thường vì khóa user trong UserStore là chữ thường)
            indexes.keySet().removeIf(k -> k.equalsIgnoreCase(base) || k.equalsIgnoreCase(sent));
            SUMMARY_CACHE.removeByPrefixIgnoreCase(base + "|");
            SUMMARY_CACHE.removeByPrefixIgnoreCase(sent + "|");
            META_CACHE.removeByPrefixIgnoreCase(base + "|");
            META_CACHE.removeByPrefixIgnoreCase(sent + "|");
            quotaMonitors.keySet().removeIf(k -> k.equalsIgnoreCase(base));
        } finally {
            // Bỏ lock khỏi map trước khi mở khóa: luồng đến sau sẽ dùng lock mới (user đã xóa nên không còn gì để tranh chấp)
            userLocks.remove(base, lockInbox);
            userLocks.remove(sent, lockSent);
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

    public static long getTotalMailCount() {
        File baseDir = new File(ServerConfig.BASE_DATA_DIR);
        if (!baseDir.exists() || !baseDir.isDirectory()) return 0;

        long count = 0;
        File[] userDirs = baseDir.listFiles((dir, name) -> new File(dir, name).isDirectory() && !name.endsWith("_sent"));
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
        File baseDir = new File(ServerConfig.BASE_DATA_DIR);
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

    // Các hàm helper hỗ trợ trích xuất thông tin Realtime SSE
    private static String parseFromFromRaw(String raw) {
        if (raw == null) return "";
        for (String line : raw.split("\r?\\n")) {
            if (line.toLowerCase().startsWith("from:")) return line.substring(5).trim();
        }
        return "";
    }

    private static String parseSubjectFromRaw(String raw) {
        if (raw == null) return "";
        for (String line : raw.split("\r?\\n")) {
            if (line.toLowerCase().startsWith("subject:")) return line.substring(8).trim();
        }
        return "";
    }

    private static String parseDateFromRaw(String raw) {
        if (raw == null) return "";
        for (String line : raw.split("\r?\\n")) {
            if (line.toLowerCase().startsWith("date:")) return line.substring(5).trim();
        }
        return "";
    }

    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}