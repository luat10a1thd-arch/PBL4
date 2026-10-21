package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UserStore {

    private static final String USERS_FILE_PATH = "data/users.json";
    private static final ConcurrentHashMap<String, User> userMap = new ConcurrentHashMap<>();
    private static boolean isLoaded = false;

    private static synchronized void ensureLoaded() {
        if (!isLoaded) {
            loadUsersFromFile();
            isLoaded = true;
        }
    }

    public static synchronized void initDefaultUsers() {
        ensureLoaded();
        if (!exists("admin@pbl4.com")) {
            byte[] salt = SecurityUtils.generateSalt();
            String saltBase64 = Base64.getEncoder().encodeToString(salt);
            String hash = SecurityUtils.hashPassword(ServerConfig.ADMIN_DEFAULT_PASS);
            addUser("admin@pbl4.com", hash, saltBase64, "ACTIVE", "ADMIN");
        }
    }

    public static String canonical(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        ensureLoaded();
        
        String cleanName = name.trim();
        boolean isSent = cleanName.endsWith("_sent");
        if (isSent) {
            cleanName = cleanName.substring(0, cleanName.length() - 5);
        }

        User u = userMap.get(cleanName.toLowerCase(Locale.ROOT));
        if (u == null) return null;

        String canonicalUsername = u.getUsername().toLowerCase(Locale.ROOT);
        return isSent ? canonicalUsername + "_sent" : canonicalUsername;
    }

    private static synchronized void loadUsersFromFile() {
        userMap.clear();
        File file = new File(USERS_FILE_PATH);
        if (!file.exists()) {
            return;
        }

        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String json = new String(bytes, StandardCharsets.UTF_8).trim();
            if (json.isEmpty()) return;

            Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(json);
            int parsedCount = 0;
            boolean dirty = false;

            while (m.find()) {
                User user = parseUserObject(m.group());
                if (user != null && user.getUsername() != null && !user.getUsername().trim().isEmpty()) {
                    parsedCount++;
                    if (user.getSalt() == null || user.getSalt().trim().isEmpty()) {
                        byte[] newSalt = SecurityUtils.generateSalt();
                        user.setSalt(Base64.getEncoder().encodeToString(newSalt));
                        dirty = true;
                    }
                    userMap.put(user.getUsername().toLowerCase(Locale.ROOT).trim(), user);
                }
            }

            if (parsedCount == 0 && file.length() > 0) {
                throw new IllegalStateException("Error parsing users.json data");
            }

            if (dirty) {
                saveUsersToFile();
            }
        } catch (Exception e) {
            System.err.println("[USERSTORE] Error loading users.json: " + e.getMessage());
        }
    }

    private static synchronized void saveUsersToFile() {
        try {
            File dataDir = new File("data");
            if (!dataDir.exists()) {
                dataDir.mkdirs();
                setFilePermissions(dataDir.toPath(), true);
            }

            StringBuilder sb = new StringBuilder("[\n");
            int count = 0;
            int total = userMap.size();

            for (User u : userMap.values()) {
                count++;
                sb.append("  {\n");
                sb.append("    \"username\": \"").append(escapeJson(u.getUsername())).append("\",\n");
                sb.append("    \"passwordHash\": \"").append(escapeJson(u.getPasswordHash())).append("\",\n");
                sb.append("    \"salt\": \"").append(escapeJson(u.getSalt() == null ? "" : u.getSalt())).append("\",\n");
                sb.append("    \"status\": \"").append(escapeJson(u.getStatus() == null ? "ACTIVE" : u.getStatus())).append("\",\n");
                sb.append("    \"role\": \"").append(escapeJson(u.getRole() == null ? "USER" : u.getRole())).append("\"\n");
                sb.append("  }").append(count < total ? ",\n" : "\n");
            }
            sb.append("]");

            Path target = Paths.get(USERS_FILE_PATH);
            Path backup = Paths.get(USERS_FILE_PATH + ".bak");
            Path tmp = Paths.get(USERS_FILE_PATH + ".tmp");

            if (Files.exists(target)) {
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                setFilePermissions(backup, false);
            }

            Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
            setFilePermissions(tmp, false);
            
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            setFilePermissions(target, false);
        } catch (Exception e) {
            System.err.println("[USERSTORE] Error saving users.json: " + e.getMessage());
        }
    }

    private static void setFilePermissions(Path path, boolean isDir) {
        try {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Set<PosixFilePermission> perms = isDir 
                    ? PosixFilePermissions.fromString("rwx------")
                    : PosixFilePermissions.fromString("rw-------");
                Files.setPosixFilePermissions(path, perms);
            }
        } catch (Exception ignored) {}
    }

    public static boolean exists(String username) {
        if (username == null) return false;
        ensureLoaded();
        return userMap.containsKey(username.toLowerCase(Locale.ROOT).trim());
    }

    public static User getUser(String username) {
        if (username == null) return null;
        ensureLoaded();
        return userMap.get(username.toLowerCase(Locale.ROOT).trim());
    }

    public static String findHash(String username) {
        User u = getUser(username);
        return u != null ? u.getPasswordHash() : null;
    }

    public static String findSalt(String username) {
        User u = getUser(username);
        return u != null ? u.getSalt() : null;
    }

    public static String getStatus(String username) {
        User u = getUser(username);
        return u != null ? u.getStatus() : "ACTIVE";
    }

    public static synchronized boolean addUserIfAbsent(String username, String passwordHash) {
        ensureLoaded();
        String key = username.toLowerCase(Locale.ROOT).trim();
        if (userMap.containsKey(key)) {
            return false;
        }
        byte[] saltBytes = SecurityUtils.generateSalt();
        String saltBase64 = Base64.getEncoder().encodeToString(saltBytes);
        String role = "admin@pbl4.com".equalsIgnoreCase(username) ? "ADMIN" : "USER";
        
        User user = new User(username.trim(), passwordHash, saltBase64, "ACTIVE", role);
        userMap.put(key, user);
        saveUsersToFile();
        return true;
    }

    public static synchronized void addUser(String username, String passwordHash) {
        byte[] saltBytes = SecurityUtils.generateSalt();
        String saltBase64 = Base64.getEncoder().encodeToString(saltBytes);
        String role = "admin@pbl4.com".equalsIgnoreCase(username) ? "ADMIN" : "USER";
        
        addUser(username, passwordHash, saltBase64, "ACTIVE", role);
    }

    public static synchronized void addUser(String username, String passwordHash, String saltBase64, String status, String role) {
        ensureLoaded();
        User user = new User(username.trim(), passwordHash, saltBase64, status, role);
        userMap.put(username.toLowerCase(Locale.ROOT).trim(), user);
        saveUsersToFile();
    }

    public static synchronized boolean setStatus(String username, String newStatus) {
        ensureLoaded();
        User u = userMap.get(username.toLowerCase(Locale.ROOT).trim());
        if (u != null) {
            u.setStatus(newStatus);
            saveUsersToFile();
            return true;
        }
        return false;
    }

    public static synchronized boolean deleteUser(String username) {
        if (username == null) return false;
        ensureLoaded();
        String key = username.toLowerCase(Locale.ROOT).trim();
        if (userMap.containsKey(key)) {
            userMap.remove(key);
            saveUsersToFile();
            return true;
        }
        return false;
    }

    public static synchronized List<User> getAllUsers() {
        ensureLoaded();
        return new ArrayList<>(userMap.values());
    }

    private static User parseUserObject(String objStr) {
        try {
            User u = new User();
            u.setUsername(extractField(objStr, "username"));
            u.setPasswordHash(extractField(objStr, "passwordHash"));
            u.setSalt(extractField(objStr, "salt"));
            
            String status = extractField(objStr, "status");
            u.setStatus(status != null ? status : "ACTIVE");

            String role = extractField(objStr, "role");
            u.setRole(role != null ? role : "USER");

            return u;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractField(String json, String field) {
        String key = "\"" + field + "\":";
        int start = json.indexOf(key);
        if (start == -1) return null;

        start += key.length();
        int quoteStart = json.indexOf("\"", start);
        if (quoteStart == -1) return null;

        int quoteEnd = json.indexOf("\"", quoteStart + 1);
        if (quoteEnd == -1) return null;

        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}