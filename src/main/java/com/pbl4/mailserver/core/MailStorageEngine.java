package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

public class MailStorageEngine {

    private final Map<String, ReentrantReadWriteLock> mailboxLocks = new ConcurrentHashMap<>();
    private final Set<String> pendingDeletion = ConcurrentHashMap.newKeySet();

    private ReentrantReadWriteLock getLockFor(String username) {
        return mailboxLocks.computeIfAbsent(username, u -> new ReentrantReadWriteLock());
    }

    public void writeMail(String sender, String receiver, String rawContent) {
        ReentrantReadWriteLock lock = getLockFor(receiver);
        lock.writeLock().lock();
        try {
            Path mailboxDir = Paths.get(ServerConfig.getDataRoot(), receiver);
            Files.createDirectories(mailboxDir);

            String fullContent = "From: " + sender + "\n" + rawContent;
            byte[] encrypted = SecurityUtils.encryptAES(fullContent);

            String fileName = "msg_" + System.currentTimeMillis() + ".enc";
            Path filePath = mailboxDir.resolve(fileName);
            Files.write(filePath, encrypted);

            System.out.println("[MailStorageEngine] Đã lưu mail (mã hóa) tại: " + filePath);
        } catch (IOException e) {
            System.err.println("[MailStorageEngine] Lỗi khi ghi mail: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<String> listMailFiles(String username) {
        ReentrantReadWriteLock lock = getLockFor(username);
        lock.readLock().lock();
        try {
            Path mailboxDir = Paths.get(ServerConfig.getDataRoot(), username);
            if (!Files.exists(mailboxDir)) {
                return new ArrayList<>();
            }
            try (Stream<Path> files = Files.list(mailboxDir)) {
                List<Path> sortedPaths = files.sorted().collect(java.util.stream.Collectors.toList());
                List<String> result = new ArrayList<>();
                for (Path p : sortedPaths) {
                    String key = username + "/" + p.getFileName();
                    if (!pendingDeletion.contains(key)) {
                        result.add(p.getFileName().toString());
                    }
                }
                return result;
            }
        } catch (IOException e) {
            System.err.println("[MailStorageEngine] Lỗi khi liệt kê mail: " + e.getMessage());
            return new ArrayList<>();
        } finally {
            lock.readLock().unlock();
        }
    }

    public String readMail(String username, String fileName) {
        ReentrantReadWriteLock lock = getLockFor(username);
        lock.readLock().lock();
        try {
            Path filePath = Paths.get(ServerConfig.getDataRoot(), username, fileName);
            byte[] encrypted = Files.readAllBytes(filePath);
            return SecurityUtils.decryptAES(encrypted);
        } catch (IOException e) {
            System.err.println("[MailStorageEngine] Lỗi khi đọc mail: " + e.getMessage());
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public long getMailSize(String username, String fileName) {
        try {
            Path filePath = Paths.get(ServerConfig.getDataRoot(), username, fileName);
            return Files.size(filePath);
        } catch (IOException e) {
            return 0;
        }
    }

    public void markForDeletion(String username, String fileName) {
        pendingDeletion.add(username + "/" + fileName);
    }

    public void unmarkAllDeletions(String username) {
        pendingDeletion.removeIf(key -> key.startsWith(username + "/"));
    }

    public void purgeMarkedDeletions(String username) {
        ReentrantReadWriteLock lock = getLockFor(username);
        lock.writeLock().lock();
        try {
            Iterator<String> it = pendingDeletion.iterator();
            while (it.hasNext()) {
                String key = it.next();
                if (key.startsWith(username + "/")) {
                    String fileName = key.substring(username.length() + 1);
                    Path filePath = Paths.get(ServerConfig.getDataRoot(), username, fileName);
                    try {
                        Files.deleteIfExists(filePath);
                        System.out.println("[MailStorageEngine] Đã xóa: " + filePath);
                    } catch (IOException e) {
                        System.err.println("[MailStorageEngine] Lỗi khi xóa: " + e.getMessage());
                    }
                    it.remove();
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean createUser(String username, String plainPassword) {
        try {
            Path usersDir = Paths.get(ServerConfig.getUsersRoot());
            Files.createDirectories(usersDir);

            Path userFile = usersDir.resolve(username + ".hash");
            if (Files.exists(userFile)) {
                return false;
            }
            String hashed = SecurityUtils.hashPassword(plainPassword);
            Files.writeString(userFile, hashed);
            return true;
        } catch (IOException e) {
            System.err.println("[MailStorageEngine] Lỗi khi tạo user: " + e.getMessage());
            return false;
        }
    }

    public boolean authenticate(String username, String plainPassword) {
        try {
            Path userFile = Paths.get(ServerConfig.getUsersRoot(), username + ".hash");
            if (!Files.exists(userFile)) {
                return false;
            }
            String storedHash = Files.readString(userFile).trim();
            return SecurityUtils.checkPassword(plainPassword, storedHash);
        } catch (IOException e) {
            return false;
        }
    }

    public boolean userExists(String username) {
        Path userFile = Paths.get(ServerConfig.getUsersRoot(), username + ".hash");
        return Files.exists(userFile);
    }
}