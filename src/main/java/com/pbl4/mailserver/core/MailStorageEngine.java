package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;

import java.io.File;
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

            String encryptedContent = SecurityUtils.encrypt(rawEmailContent, ServerConfig.SECRET_KEY, saltBytes);
            String fileName = "msg_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".enc";
            String filePath = ServerConfig.BASE_DATA_DIR + recipient + "/" + fileName;

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
            e.printStackTrace();
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
                        files[i].delete();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            lock.writeLock().unlock();
        }
    }
}