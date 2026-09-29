package com.pbl4.mailserver.webapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class AdminHandler implements HttpHandler {

    private static final String USERS_FILE_PATH = "data/users.json";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Chưa đăng nhập!\"}");
            return;
        }

        String token = authHeader.substring(7);
        
        // Gọi đúng hàm SessionManager.validate(token) để lấy tên người dùng
        String currentUser = SessionManager.validate(token);

        if (currentUser == null) {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Phiên đăng nhập hết hạn hoặc không hợp lệ!\"}");
            return;
        }

        if (!"admin@pbl4.com".equalsIgnoreCase(currentUser)) {
            sendResponse(exchange, 403, "{\"success\": false, \"message\": \"Từ chối truy cập! Quyền Admin bắt buộc.\"}");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        // 1. GET /api/admin/users - Lấy danh sách toàn bộ người dùng
        if ("GET".equalsIgnoreCase(method) && path.endsWith("/users")) {
            handleGetUsers(exchange);
            return;
        }

        // Các route POST
        if ("POST".equalsIgnoreCase(method)) {
            String body = readRequestBody(exchange);
            String targetUser = getParamValue(body, "targetUser");

            if (path.endsWith("/user/delete")) {
                handleDeleteUser(exchange, targetUser);
            } else if (path.endsWith("/broadcast")) {
                String subject = getParamValue(body, "subject");
                String content = getParamValue(body, "body");
                handleBroadcast(exchange, subject, content);
            } else {
                sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Endpoint không tồn tại!\"}");
            }
        } else {
            sendResponse(exchange, 405, "{\"success\": false, \"message\": \"Phương thức không hỗ trợ!\"}");
        }
    }

    private void handleGetUsers(HttpExchange exchange) throws IOException {
        List<String> usernames = getAllUsernamesFromFile();
        StringBuilder jsonBuilder = new StringBuilder("[");
        for (int i = 0; i < usernames.size(); i++) {
            jsonBuilder.append("{\"username\":\"").append(usernames.get(i)).append("\"}");
            if (i < usernames.size() - 1) {
                jsonBuilder.append(",");
            }
        }
        jsonBuilder.append("]");

        sendResponse(exchange, 200, "{\"success\": true, \"users\": " + jsonBuilder.toString() + "}");
    }

    private void handleDeleteUser(HttpExchange exchange, String targetUser) throws IOException {
        if (targetUser == null || targetUser.isEmpty()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu tài khoản cần xóa!\"}");
            return;
        }

        if ("admin@pbl4.com".equalsIgnoreCase(targetUser)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Không thể xóa tài khoản Admin mặc định!\"}");
            return;
        }

        boolean removed = removeUserFromFile(targetUser);
        if (removed) {
            File mailboxDir = new File("data/mailboxes/" + targetUser);
            if (mailboxDir.exists()) {
                deleteDir(mailboxDir);
            }
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã xóa thành công tài khoản " + targetUser + "\"}");
        } else {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy tài khoản trong hệ thống!\"}");
        }
    }

    private void handleBroadcast(HttpExchange exchange, String subject, String content) throws IOException {
        if (subject == null || content == null || subject.isEmpty() || content.isEmpty()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Nội dung thông báo không được để trống!\"}");
            return;
        }

        List<String> usernames = getAllUsernamesFromFile();
        int count = 0;
        for (String user : usernames) {
            try {
                File userMailbox = new File("data/mailboxes/" + user + "/inbox");
                if (!userMailbox.exists()) {
                    userMailbox.mkdirs();
                }
                File mailFile = new File(userMailbox, "broadcast_" + System.currentTimeMillis() + ".json");
                try (FileWriter fw = new FileWriter(mailFile, StandardCharsets.UTF_8)) {
                    fw.write("{\"sender\":\"admin@pbl4.com\",\"subject\":\"" + subject + "\",\"body\":\"" + content + "\",\"time\":\"" + java.time.LocalDateTime.now() + "\"}");
                }
                count++;
            } catch (Exception ignored) {}
        }

        sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã phát thông báo thành công tới " + count + " tài khoản!\"}");
    }

    private synchronized List<String> getAllUsernamesFromFile() {
        List<String> users = new ArrayList<>();
        File file = new File(USERS_FILE_PATH);
        if (!file.exists()) return users;

        try (BufferedReader br = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("username")) {
                    String[] parts = line.split("\"username\"\\s*:\\s*\"");
                    if (parts.length > 1) {
                        String uname = parts[1].split("\"")[0];
                        if (!users.contains(uname)) {
                            users.add(uname);
                        }
                    }
                }
            }
        } catch (IOException ignored) {}
        return users;
    }

    private synchronized boolean removeUserFromFile(String targetUser) {
        File file = new File(USERS_FILE_PATH);
        if (!file.exists()) return false;

        List<String> lines = new ArrayList<>();
        boolean found = false;
        try (BufferedReader br = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("\"username\"\\s*:\\s*\"" + targetUser + "\"") || line.contains("\"" + targetUser + "\"")) {
                    found = true;
                    continue;
                }
                lines.add(line);
            }
        } catch (IOException e) {
            return false;
        }

        if (found) {
            try (FileWriter fw = new FileWriter(file, StandardCharsets.UTF_8)) {
                for (String l : lines) {
                    fw.write(l + "\n");
                }
            } catch (IOException e) {
                return false;
            }
        }
        return found;
    }

    private void deleteDir(File f) {
        File[] allFiles = f.listFiles();
        if (allFiles != null) {
            for (File sub : allFiles) {
                deleteDir(sub);
            }
        }
        f.delete();
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
        BufferedReader br = new BufferedReader(isr);
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }

    private String getParamValue(String body, String paramName) {
        if (body == null || body.isEmpty()) return null;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=");
            if (kv.length == 2 && kv[0].equalsIgnoreCase(paramName)) {
                return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String jsonResponse) throws IOException {
        byte[] bytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }
}