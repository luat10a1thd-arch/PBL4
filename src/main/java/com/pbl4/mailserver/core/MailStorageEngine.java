package com.pbl4.mailserver.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MailStorageEngine {

    private static final String BASE_DATA_DIR = "data/mailboxes/";
    private static final String SECRET_KEY = System.getenv().getOrDefault(
            "PBL4_SECRET_KEY", "dev-only-fallback-key-DO-NOT-USE-IN-PRODUCTION");

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> userLocks = new ConcurrentHashMap<>();

    private static ReentrantReadWriteLock getLockForUser(String username) {
        return userLocks.computeIfAbsent(username, k -> new ReentrantReadWriteLock());
    }

    public static void initMailbox(String username) {
        File dir = new File(BASE_DATA_DIR + username);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public static boolean saveEmail(String recipient, String rawEmailContent) {
        initMailbox(recipient);
        ReentrantReadWriteLock lock = getLockForUser(recipient);
        lock.writeLock().lock();
        
        try {
            String cleanUser = recipient.replace("_sent", "");
            String base64Salt = UserStore.findSalt(cleanUser);
            byte[] saltBytes = (base64Salt != null) 
                    ? Base64.getDecoder().decode(base64Salt) 
                    : "PBL4_DEFAULT_SALT".getBytes(StandardCharsets.UTF_8);

            String encryptedContent = SecurityUtils.encrypt(rawEmailContent, SECRET_KEY, saltBytes);
            String fileName = "msg_" + System.currentTimeMillis() + ".enc";
            String filePath = BASE_DATA_DIR + recipient + "/" + fileName;

            Files.write(Paths.get(filePath), encryptedContent.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            e.printStackTrace();
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
            String cleanUser = username.replace("_sent", "");
            String base64Salt = UserStore.findSalt(cleanUser);
            byte[] saltBytes = (base64Salt != null) 
                    ? Base64.getDecoder().decode(base64Salt) 
                    : "PBL4_DEFAULT_SALT".getBytes(StandardCharsets.UTF_8);

            File folder = new File(BASE_DATA_DIR + username);
            File[] files = folder.listFiles((dir, name) -> name.endsWith(".enc"));

            if (files != null) {
                for (File file : files) {
                    byte[] bytes = Files.readAllBytes(file.toPath());
                    String encryptedData = new String(bytes, StandardCharsets.UTF_8);
                    String decryptedContent = SecurityUtils.decrypt(encryptedData, SECRET_KEY, saltBytes);
                    emails.add(decryptedContent);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            lock.readLock().unlock();
        }

        return emails;
    }
}