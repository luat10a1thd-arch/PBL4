package com.pbl4.mailserver.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Động cơ lưu trữ email dạng Flat-File mã hóa AES-256[cite: 1].
 * Sử dụng ReentrantReadWriteLock để quản lý đồng bộ đa luồng[cite: 1].
 */
public class MailStorageEngine {

    private static final String BASE_DATA_DIR = "data/mailboxes/";
    private static final String SECRET_KEY = "PBL4_STORAGE_SECRET_KEY";

    // Quản lý Lock theo từng user để tối ưu hiệu năng[cite: 1]
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> userLocks = new ConcurrentHashMap<>();

    private static ReentrantReadWriteLock getLockForUser(String username) {
        return userLocks.computeIfAbsent(username, k -> new ReentrantReadWriteLock());
    }

    /**
     * Khởi tạo thư mục chứa hòm thư nếu chưa tồn tại[cite: 1]
     */
    public static void initMailbox(String username) {
        File dir = new File(BASE_DATA_DIR + username);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    /**
     * GHI MAIL (Mã hóa và lưu xuống file .enc)[cite: 1]
     */
    public static boolean saveEmail(String recipient, String rawEmailContent) {
        initMailbox(recipient);
        ReentrantReadWriteLock lock = getLockForUser(recipient);
        lock.writeLock().lock(); // Khóa ghi[cite: 1]
        
        try {
            String encryptedContent = SecurityUtils.encrypt(rawEmailContent, SECRET_KEY);
            String fileName = "msg_" + System.currentTimeMillis() + ".enc";
            String filePath = BASE_DATA_DIR + recipient + "/" + fileName;

            Files.write(Paths.get(filePath), encryptedContent.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            lock.writeLock().unlock(); // Giải phóng khóa ghi[cite: 1]
        }
    }

    /**
     * ĐỌC TẤT CẢ MAIL (Giải mã toàn bộ thư của user)[cite: 1]
     */
    public static List<String> readAllEmails(String username) {
        initMailbox(username);
        List<String> emails = new ArrayList<>();
        ReentrantReadWriteLock lock = getLockForUser(username);
        lock.readLock().lock(); // Khóa đọc[cite: 1]

        try {
            File folder = new File(BASE_DATA_DIR + username);
            File[] files = folder.listFiles((dir, name) -> name.endsWith(".enc"));

            if (files != null) {
                for (File file : files) {
                    byte[] bytes = Files.readAllBytes(file.toPath());
                    String encryptedData = new String(bytes, StandardCharsets.UTF_8);
                    String decryptedContent = SecurityUtils.decrypt(encryptedData, SECRET_KEY);
                    emails.add(decryptedContent);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            lock.readLock().unlock(); // Giải phóng khóa đọc[cite: 1]
        }

        return emails;
    }
}