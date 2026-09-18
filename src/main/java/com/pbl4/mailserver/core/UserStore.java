package com.pbl4.mailserver.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** Gom toàn bộ logic đọc/ghi users.json về một nơi duy nhất, thay cho 3 bản copy-paste cũ. */
public class UserStore {
    private static final String USERS_FILE = "data/users.json";

    public static synchronized void addUser(String username, String passwordHash) throws IOException {
        byte[] saltBytes = new byte[16];
        new SecureRandom().nextBytes(saltBytes);
        String salt = Base64.getEncoder().encodeToString(saltBytes);

        String line = "{\"username\":\"" + username + "\",\"passwordHash\":\"" + passwordHash
                + "\",\"salt\":\"" + salt + "\"}";
        try (FileWriter fw = new FileWriter(USERS_FILE, true)) {
            fw.write(line + "\n");
        }
    }

    public static String findHash(String username) {
        String[] parts = findUserLine(username);
        return parts != null ? parts[0] : null;
    }

    public static String findSalt(String username) {
        String[] parts = findUserLine(username);
        return parts != null ? parts[1] : null;
    }

    private static String[] findUserLine(String username) {
        File file = new File(USERS_FILE);
        if (!file.exists()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("\"username\":\"" + username + "\"")) {
                    return new String[]{ extract(line, "passwordHash"), extract(line, "salt") };
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return null;
    }

    private static String extract(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start == -1) return null;
        start += marker.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    public static boolean exists(String username) {
        return findUserLine(username) != null;
    }
}