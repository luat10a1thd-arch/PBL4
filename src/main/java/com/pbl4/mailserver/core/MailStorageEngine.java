package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> userLocks = new ConcurrentHashMap<>();

    private static ReentrantReadWriteLock getLockForUser(String username) {
        return userLocks.computeIfAbsent(username, k -> new ReentrantReadWriteLock());
    }

    private static Path mailboxDir(String username) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Tên hòm thư không được để trống");
        }
        Path base = Paths.get(ServerConfig.BASE_DATA_DIR).toAbsolutePath().normalize();
        Path dir = base.resolve(username.trim()).normalize();
        if (!dir.startsWith(base) || dir.equals(base)) {
            throw new IllegalArgumentException("Đường dẫn hòm thư không hợp lệ");
        }
        return dir;
    }

    public static void initMailbox(String username) {
        ReentrantReadWriteLock lock = getLockForUser(username);
        lock.writeLock().lock();
        try {
            File dir = mailboxDir(username).toFile();
            if (!dir.exists()) {
                dir.mkdirs();
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

    public static long getMailboxSizeInBytes(String username) {
        ReentrantReadWriteLock lock = getLockForUser(username);
        lock.readLock().lock();
        try {
            File folder = mailboxDir(username).toFile();
            if (!folder.exists() || !folder.isDirectory()) return 0;

            long totalSize = 0;
            File[] files = folder.listFiles((dir, name) -> name.endsWith(".enc"));
            if (files != null) {
                for (File file : files) {
                    totalSize += file.length();
                }
            }
            return totalSize;
        } finally {
            lock.readLock().unlock();
        }
    }

    public static String findUserExceedingQuota(long newQuotaMb) {
        List<User> users = UserStore.getAllUsers();
        long newQuotaBytes = newQuotaMb * 1024L * 1024L;

        for (User u : users) {
            if (u == null || u.getUsername() == null) continue;
            String username = u.getUsername().trim();
            long inboxBytes = getMailboxSizeInBytes(username);
            long sentBytes = getMailboxSizeInBytes(username + "_sent");
            long totalUsedBytes = inboxBytes + sentBytes;

            if (totalUsedBytes > newQuotaBytes) {
                double usedMb = totalUsedBytes / (1024.0 * 1024.0);
                return String.format("%s (đang dùng %.2f MB)", username, usedMb);
            }
        }
        return null;
    }

    public static boolean saveEmail(String recipient, String rawEmailContent) {
        String cleanRecipient = getCleanUsername(recipient);

        if (!UserStore.exists(cleanRecipient)) {
            System.err.println("[MAIL STORAGE] Người nhận không tồn tại: " + cleanRecipient);
            return false;
        }

        initMailbox(recipient);

        ReentrantReadWriteLock lock = getLockForUser(recipient);
        lock.writeLock().lock();

        try {
            long inboxSizeBytes = getMailboxSizeInBytes(cleanRecipient);
            long sentSizeBytes = getMailboxSizeInBytes(cleanRecipient + "_sent");
            long totalUsedBytes = inboxSizeBytes + sentSizeBytes;

            long incomingSizeBytes = rawEmailContent.getBytes(StandardCharsets.UTF_8).length;

            long maxSizeBytes;
            try {
                maxSizeBytes = Math.multiplyExact(ServerConfig.MAX_MAILBOX_QUOTA_MB, 1024L * 1024L);
            } catch (ArithmeticException e) {
                maxSizeBytes = Long.MAX_VALUE;
            }

            if (totalUsedBytes + incomingSizeBytes > maxSizeBytes) {
                System.err.println("[MAIL STORAGE] Hòm thư " + cleanRecipient + " vượt Quota khi lưu thư mới");
                return false;
            }

            byte[] saltBytes = getUserSaltOrThrow(recipient);
            String encryptedContent = SecurityUtils.encrypt(rawEmailContent, ServerConfig.SECRET_KEY, saltBytes);
            String fileName = "msg_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".enc";
            Path filePath = mailboxDir(recipient).resolve(fileName);

            Files.write(filePath, encryptedContent.getBytes(StandardCharsets.UTF_8));

            String cleanRecipientUser = getCleanUsername(recipient);
            String senderFromRaw = parseFromFromRaw(rawEmailContent);

            String summaryJson = String.format(
                "{\"id\":\"%s\",\"sender\":\"%s\",\"recipient\":\"%s\",\"subject\":\"%s\",\"timestamp\":\"%s\"}",
                escapeJson(fileName),
                escapeJson(senderFromRaw.isEmpty() ? cleanRecipientUser : senderFromRaw),
                escapeJson(recipient),
                escapeJson(parseSubjectFromRaw(rawEmailContent)),
                escapeJson(parseDateFromRaw(rawEmailContent))
            );

            com.pbl4.mailserver.webapi.SseManager.notifyUser(cleanRecipientUser, summaryJson);
            com.pbl4.mailserver.webapi.SseManager.notifyUser(recipient, summaryJson);

            return true;
        } catch (Exception e) {
            System.err.println("[MAIL STORAGE] Lỗi khi lưu thư: " + e.getMessage());
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static MailPage listMailSummaries(String username, int page, int limit) {
        initMailbox(username);
        ReentrantReadWriteLock lock = getLockForUser(username);
        lock.readLock().lock();

        try {
            File folder = mailboxDir(username).toFile();
            File[] files = folder.listFiles((dir, name) -> name.endsWith(".enc"));

            if (files == null || files.length == 0) {
                return new MailPage(new ArrayList<>(), 0);
            }

            Arrays.sort(files, Comparator.comparing(File::getName).reversed());

            int total = files.length;
            int start = (page - 1) * limit;
            if (start >= total) {
                return new MailPage(new ArrayList<>(), total);
            }
            int end = Math.min(start + limit, total);

            byte[] saltBytes = getUserSaltOrThrow(username);
            SecretKey derivedKey = SecurityUtils.deriveKey(ServerConfig.SECRET_KEY, saltBytes);

            List<MailSummary> summaries = new ArrayList<>();
            for (int i = start; i < end; i++) {
                File file = files[i];
                String headerStr = readAndDecryptHeaderPartial(file, derivedKey);
                if (headerStr != null) {
                    summaries.add(parseHeaderToSummary(file.getName(), headerStr));
                } else {
                    summaries.add(new MailSummary(file.getName(), "", "", "", ""));
                }
            }

            return new MailPage(summaries, total);
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
            final int HEADER_READ_LIMIT = 12288;

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

            int validB64Len = (cipherB64Partial.length() / 4) * 4;
            if (validB64Len == 0) return null;

            byte[] rawCipherPartial = Base64.getDecoder().decode(cipherB64Partial.substring(0, validB64Len));

            int validAesLen = (rawCipherPartial.length / 16) * 16;
            if (validAesLen == 0) return null;

            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, derivedKey, new IvParameterSpec(iv));

            byte[] decryptedPartial = cipher.doFinal(rawCipherPartial, 0, validAesLen);
            String decryptedHeader = new String(decryptedPartial, StandardCharsets.UTF_8);

            if (decryptedHeader.contains("\n\n") || decryptedHeader.contains("\r\n\r\n")) {
                return decryptedHeader;
            }

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
                if (line.isEmpty()) break;
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

    public static long getTotalMailCount() {
        File baseDir = new File(ServerConfig.BASE_DATA_DIR);
        if (!baseDir.exists() || !baseDir.isDirectory()) return 0;

        long count = 0;
        File[] userDirs = baseDir.listFiles((dir, name) -> new File(dir, name).isDirectory());
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