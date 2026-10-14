package com.pbl4.mailserver.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class UserStore {

    private static final String USERS_FILE_PATH = "data/users.json";
    
    // Cache lưu trữ danh sách User trong RAM để thao tác an toàn và nhanh
    private static final ConcurrentHashMap<String, User> userMap = new ConcurrentHashMap<>();
    private static boolean isLoaded = false;

    private static synchronized void ensureLoaded() {
        if (!isLoaded) {
            loadUsersFromFile();
            isLoaded = true;
        }
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
            if (json.isEmpty() || !json.startsWith("[")) return;

            // Bỏ ngoặc vuông [ ]
            json = json.substring(1, json.length() - 1).trim();
            if (json.isEmpty()) return;

            String[] objects = json.split("(?<=\\}),\\s*(?=\\{)");
            for (String obj : objects) {
                User user = parseUserObject(obj);
                if (user != null && user.getUsername() != null) {
                    userMap.put(user.getUsername().toLowerCase(), user);
                }
            }
        } catch (Exception e) {
            System.err.println("[USERSTORE] Lỗi khi đọc file users.json: " + e.getMessage());
        }
    }

    private static synchronized void saveUsersToFile() {
        try {
            File dataDir = new File("data");
            if (!dataDir.exists()) {
                dataDir.mkdirs();
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

            Files.write(Paths.get(USERS_FILE_PATH), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[USERSTORE] Lỗi khi lưu file users.json: " + e.getMessage());
        }
    }

    // ==========================================
    // CÁC HÀM XỬ LÝ DỮ LIỆU TẬP TRUNG (MỤC 4 & 13 BÁO CÁO)
    // ==========================================

    public static boolean exists(String username) {
        if (username == null) return false;
        ensureLoaded();
        return userMap.containsKey(username.toLowerCase().trim());
    }

    public static User getUser(String username) {
        if (username == null) return null;
        ensureLoaded();
        return userMap.get(username.toLowerCase().trim());
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

    public static synchronized void addUser(String username, String passwordHash) {
        byte[] saltBytes = SecurityUtils.generateSalt();
        String saltBase64 = Base64.getEncoder().encodeToString(saltBytes);
        String role = "admin@pbl4.com".equalsIgnoreCase(username) ? "ADMIN" : "USER";
        
        addUser(username, passwordHash, saltBase64, "ACTIVE", role);
    }

    public static synchronized void addUser(String username, String passwordHash, String saltBase64, String status, String role) {
        ensureLoaded();
        User user = new User(username.trim(), passwordHash, saltBase64, status, role);
        userMap.put(username.toLowerCase().trim(), user);
        saveUsersToFile();
    }

    public static synchronized boolean setStatus(String username, String newStatus) {
        ensureLoaded();
        User u = userMap.get(username.toLowerCase().trim());
        if (u != null) {
            u.setStatus(newStatus);
            saveUsersToFile();
            return true;
        }
        return false;
    }

    // FIX VẤN ĐỀ 13: Bổ sung phương thức xóa User tập trung
    public static synchronized boolean deleteUser(String username) {
        if (username == null) return false;
        ensureLoaded();
        String key = username.toLowerCase().trim();
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

    // --- CÁC HÀM PARSER HỖ TRỢ ---

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