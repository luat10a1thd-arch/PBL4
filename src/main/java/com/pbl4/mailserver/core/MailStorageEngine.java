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
     * Lấy Salt của User từ UserStore (Sửa Vấn đề 7: Loại bỏ default salt cố định)
     */
    private static byte[] getUserSaltOrThrow(String username) throws IllegalArgumentException {
        String cleanUser = getCleanUsername(username);
        String base64Salt = UserStore.findSalt(cleanUser);

        if (base64Salt == null || base64Salt.trim().isEmpty()) {
            throw new IllegalArgumentException("[MAIL STORAGE] Không tìm thấy Salt cho tài khoản: " + cleanUser);
        }

        return Base64.getDecoder().decode(base64Salt);
    }

    public static boolean saveEmail(String recipient, String rawEmailContent) {
        initMailbox(recipient);
        ReentrantReadWriteLock lock = getLockForUser(recipient);
        lock.writeLock().lock();
        
        try {
            // Lấy Salt chuẩn của user, nếu không có sẽ ném lỗi rõ ràng (Vấn đề 7)
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
            // Lấy Salt chuẩn của user (Vấn đề 7 & 8)
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
                        // Sửa Vấn đề 9: Dùng Files.deleteIfExists để bắt IOException chính xác
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
}