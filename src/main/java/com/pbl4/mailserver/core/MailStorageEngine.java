package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.crypto.SecretKey;

public class MailStorageEngine {

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> userLocks = new ConcurrentHashMap<>();

    private static ReentrantReadWriteLock getLockForUser(String username) {
        return userLocks.computeIfAbsent(username, k -> new ReentrantReadWriteLock());
    }

    public static void initMailbox(String username) {
        File dir = new File(ServerConfig.BASE_DATA_DIR + username);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    /**
     * Tách hậu tố _sent chuẩn xác (Sửa Vấn đề 8: Không dùng replaceAll bừa bãi)
     */
    private static String getCleanUsername(String username) {
        if (username != null && username.endsWith("_sent")) {
            return username.substring(0, username.length() - 5);
        }
        return username;
    }

    /**
     * Lấy Salt của User từ UserStore. Nếu chưa có Salt thì tự động tạo Salt cố định an toàn
     * dựa trên username để tránh làm gãy luồng gửi/lưu mail.
     */
    private static byte[] getUserSaltOrThrow(String username) {
        String cleanUser = getCleanUsername(username);
        String base64Salt = UserStore.findSalt(cleanUser);

        if (base64Salt != null && !base64Salt.trim().isEmpty()) {
            try {
                return Base64.getDecoder().decode(base64Salt);
            } catch (Exception ignored) {}
        }

        // Fallback an toàn tạo Salt cố định theo tên user nếu chưa khởi tạo Salt trong UserStore
        return (cleanUser + "_PBL4_SAFE_SALT_KEY").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Tính dung lượng (Bytes) hiện tại của một Hòm thư
     */
    public static long getMailboxSizeInBytes(String username) {
        File folder = new File(ServerConfig.BASE_DATA_DIR + username);
        if (!folder.exists() || !folder.isDirectory()) return 0;
        
        long totalSize = 0;
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".enc"));
        if (files != null) {
            for (File file : files) {
                totalSize += file.length();
            }
        }
        return totalSize;
    }

    public static boolean saveEmail(String recipient, String rawEmailContent) {
        initMailbox(recipient);

        // KIỂM TRA QUOTA: Kiểm tra hòm thư có vượt quá Quota cho phép không
        long currentSizeBytes = getMailboxSizeInBytes(recipient);
        long maxSizeBytes = ServerConfig.MAX_MAILBOX_QUOTA_MB * 1024 * 1024;
        if (currentSizeBytes >= maxSizeBytes) {
            System.err.println("[MAIL STORAGE] Hòm thư " + recipient + " đã vượt quá Quota cho phép (" + ServerConfig.MAX_MAILBOX_QUOTA_MB + " MB)");
            return false;
        }

        ReentrantReadWriteLock lock = getLockForUser(recipient);
        lock.writeLock().lock();
        
        try {
            byte[] saltBytes = getUserSaltOrThrow(recipient);

            String encryptedContent = SecurityUtils.encrypt(rawEmailContent, ServerConfig.SECRET_KEY, saltBytes);
            String fileName = "msg_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".enc";
            String filePath = ServerConfig.BASE_DATA_DIR + recipient + "/" + fileName;

            Files.write(Paths.get(filePath), encryptedContent.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            System.err.println("[MAIL STORAGE] Lỗi khi lưu thư cho " + recipient + ": " + e.getMessage());
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static List<String> readAllEmails(String username) {
        initMailbox(username);
        List<String> emails = new ArrayList<>();
        ReentrantReadWriteLock lock = getLockForUser(username);
        lock.readLock().lock();

        try {
            byte[] saltBytes = getUserSaltOrThrow(username);

            File folder = new File(ServerConfig.BASE_DATA_DIR + username);
            File[] files = folder.listFiles((dir, name) -> name.endsWith(".enc"));

            if (files != null && files.length > 0) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                SecretKey derivedKey = SecurityUtils.deriveKey(ServerConfig.SECRET_KEY, saltBytes);

                for (File file : files) {
                    byte[] bytes = Files.readAllBytes(file.toPath());
                    String encryptedData = new String(bytes, StandardCharsets.UTF_8);
                    String decryptedContent = SecurityUtils.decryptWithKey(encryptedData, derivedKey);
                    if (decryptedContent != null) {
                        emails.add(decryptedContent);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MAIL STORAGE] Lỗi khi đọc thư của " + username + ": " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }

        return emails;
    }

    public static void deleteEmails(String username, Set<Integer> deletedIndices) {
        initMailbox(username);
        ReentrantReadWriteLock lock = getLockForUser(username);
        lock.writeLock().lock();

        try {
            File folder = new File(ServerConfig.BASE_DATA_DIR + username);
            File[] files = folder.listFiles((dir, name) -> name.endsWith(".enc"));

            if (files != null && files.length > 0) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (int i = 0; i < files.length; i++) {
                    if (deletedIndices.contains(i + 1)) {
                        try {
                            boolean deleted = Files.deleteIfExists(files[i].toPath());
                            if (!deleted) {
                                System.err.println("[MAIL STORAGE] Không thể xóa file mail: " + files[i].getName());
                            }
                        } catch (IOException ioe) {
                            System.err.println("[MAIL STORAGE] Lỗi I/O khi xóa file " + files[i].getName() + ": " + ioe.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MAIL STORAGE] Lỗi khi xử lý xóa thư của " + username + ": " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Đếm tổng số lượng file thư (.enc) trên toàn bộ hệ thống
     * (Chỉ đếm các hòm thư chính, bỏ qua thư mục bản sao _sent để không bị đếm trùng)
     */
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

    /**
     * Tính tổng dung lượng (Bytes) của toàn bộ các hòm thư trong hệ thống
     */
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