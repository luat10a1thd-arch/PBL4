package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;

import java.io.File;
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
import javax.crypto.SecretKey;

public class MailStorageEngine {

    public static final class StoredMail {
        public final String id;
        public final String content;

        public StoredMail(String id, String content) {
            this.id = id;
            this.content = content;
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
            long currentSizeBytes = getMailboxSizeInBytes(recipient);
            long incomingSizeBytes = rawEmailContent.getBytes(StandardCharsets.UTF_8).length;
            
            long maxSizeBytes;
            try {
                maxSizeBytes = Math.multiplyExact(ServerConfig.MAX_MAILBOX_QUOTA_MB, 1024L * 1024L);
            } catch (ArithmeticException e) {
                maxSizeBytes = Long.MAX_VALUE;
            }

            if (currentSizeBytes + incomingSizeBytes > maxSizeBytes) {
                System.err.println("[MAIL STORAGE] Hòm thư " + recipient + " vượt Quota");
                return false;
            }

            byte[] saltBytes = getUserSaltOrThrow(recipient);
            String encryptedContent = SecurityUtils.encrypt(rawEmailContent, ServerConfig.SECRET_KEY, saltBytes);
            String fileName = "msg_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".enc";
            Path filePath = mailboxDir(recipient).resolve(fileName);

            Files.write(filePath, encryptedContent.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            System.err.println("[MAIL STORAGE] Lỗi khi lưu thư: " + e.getMessage());
            return false;
        } finally {
            lock.writeLock().unlock();
        }
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
}