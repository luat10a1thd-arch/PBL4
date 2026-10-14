package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.User;
import com.pbl4.mailserver.core.UserStore;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AdminHandler implements HttpHandler {

    private static final String SYSTEM_LOG_PATH = "data/system.log";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Chưa đăng nhập!\"}");
            return;
        }

        String token = authHeader.substring(7);
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

        // 1. GET /api/admin/users
        if ("GET".equalsIgnoreCase(method) && path.endsWith("/users")) {
            handleGetUsers(exchange);
            return;
        }

        // 2. GET /api/admin/logs - Lấy nhật ký hệ thống thật
        if ("GET".equalsIgnoreCase(method) && path.endsWith("/logs")) {
            handleGetLogs(exchange);
            return;
        }

        // Các route POST
        if ("POST".equalsIgnoreCase(method)) {
            String body = readRequestBody(exchange);
            String targetUser = getParamValue(body, "targetUser");

            if (path.endsWith("/user/delete")) {
                handleDeleteUser(exchange, targetUser);
            } else if (path.endsWith("/user/toggle-lock")) {
                String action = getParamValue(body, "action");
                handleToggleLockUser(exchange, targetUser, action);
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
        // Đồng bộ Vấn đề 4 & 13: Lấy danh sách trực tiếp từ UserStore
        List<User> users = UserStore.getAllUsers();
        StringBuilder jsonBuilder = new StringBuilder("[");
        for (int i = 0; i < users.size(); i++) {
            User u = users.get(i);
            jsonBuilder.append("{\"username\":\"").append(escapeJson(u.getUsername()))
                       .append("\",\"status\":\"").append(escapeJson(u.getStatus() == null ? "ACTIVE" : u.getStatus())).append("\"}");
            if (i < users.size() - 1) {
                jsonBuilder.append(",");
            }
        }
        jsonBuilder.append("]");

        sendResponse(exchange, 200, "{\"success\": true, \"users\": " + jsonBuilder.toString() + "}");
    }

    private void handleGetLogs(HttpExchange exchange) throws IOException {
        File logFile = new File(SYSTEM_LOG_PATH);
        List<String> logs = new ArrayList<>();
        
        if (logFile.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(logFile, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    logs.add(line);
                }
            } catch (IOException ignored) {}
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < logs.size(); i++) {
            sb.append("\"").append(escapeJson(logs.get(i))).append("\"");
            if (i < logs.size() - 1) sb.append(",");
        }
        sb.append("]");

        sendResponse(exchange, 200, "{\"success\": true, \"logs\": " + sb.toString() + "}");
    }

    private void handleToggleLockUser(HttpExchange exchange, String targetUser, String action) throws IOException {
        if (targetUser == null || targetUser.isEmpty() || action == null) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu thông tin người dùng hoặc hành động!\"}");
            return;
        }

        if ("admin@pbl4.com".equalsIgnoreCase(targetUser)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Không thể khóa tài khoản Admin mặc định!\"}");
            return;
        }

        if (!"lock".equalsIgnoreCase(action) && !"unlock".equalsIgnoreCase(action)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Hành động phải là lock hoặc unlock!\"}");
            return;
        }
        String newStatus = "lock".equalsIgnoreCase(action) ? "LOCKED" : "ACTIVE";
        
        // Đồng bộ Vấn đề 13: Đổi trạng thái khóa/mở khóa qua UserStore
        boolean updated = UserStore.setStatus(targetUser, newStatus);

        if (updated) {
            String actName = "LOCKED".equals(newStatus) ? "Khóa tài khoản" : "Mở khóa tài khoản";
            appendLog(actName + ": " + targetUser);
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã " + actName.toLowerCase() + " " + targetUser + " thành công!\"}");
        } else {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy tài khoản!\"}");
        }
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

        if (!targetUser.matches("[A-Za-z0-9._%+@-]+")) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Tên tài khoản không hợp lệ!\"}");
            return;
        }

        // FIX VẤN ĐỀ 13 BÁO CÁO: Gọi trực tiếp UserStore.deleteUser để xóa vĩnh viễn user khỏi RAM và file users.json
        boolean removed = UserStore.deleteUser(targetUser);

        if (removed) {
            // Xóa toàn bộ thư mục hòm thư đĩa (inbox và _sent)
            deleteUserMailbox("data/mailboxes/" + targetUser);
            deleteUserMailbox("data/mailboxes/" + targetUser + "_sent");

            appendLog("Xóa người dùng: " + targetUser);
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã xóa vĩnh viễn tài khoản " + targetUser + "\"}");
        } else {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy tài khoản trong hệ thống!\"}");
        }
    }

    private void handleBroadcast(HttpExchange exchange, String subject, String content) throws IOException {
        if (subject == null || content == null || subject.trim().isEmpty() || content.trim().isEmpty()) {
            sendResponse(exchange, 400,
                    "{\"success\": false, \"message\": \"Tiêu đề và nội dung thông báo không được để trống!\"}");
            return;
        }
        if (subject.length() > 250 || content.length() > 100000) {
            sendResponse(exchange, 400,
                    "{\"success\": false, \"message\": \"Tiêu đề hoặc nội dung quá dài!\"}");
            return;
        }

        String safeSubject = subject.replace("\r", " ").replace("\n", " ").trim();
        List<User> users = UserStore.getAllUsers();
        int sent = 0;
        int failed = 0;
        int eligible = 0;
        String nowStr = java.time.ZonedDateTime.now().toString();

        for (User user : users) {
            if (user.getUsername() == null || user.getUsername().trim().isEmpty()
                    || user.getUsername().equalsIgnoreCase("admin@pbl4.com")) {
                continue;
            }

            eligible++;
            String recipient = user.getUsername().trim();
            try {
                String rawEmail = "From: admin@pbl4.com\n"
                        + "To: " + recipient + "\n"
                        + "Subject: " + safeSubject + "\n"
                        + "Date: " + nowStr + "\n"
                        + "\n"
                        + content;

                boolean saved = MailStorageEngine.saveEmail(recipient, rawEmail);
                if (saved) {
                    sent++;
                    if (!MailStorageEngine.saveEmail("admin@pbl4.com_sent", rawEmail)) {
                        appendLog("Broadcast: đã gửi tới " + recipient
                                + " nhưng không lưu được bản sao trong Đã gửi của Admin.");
                    }
                } else {
                    failed++;
                    appendLog("Broadcast thất bại khi lưu thư cho " + recipient);
                }
            } catch (Exception e) {
                failed++;
                appendLog("Broadcast lỗi với tài khoản " + recipient + ": " + e.getMessage());
            }
        }

        appendLog("Broadcast: gửi thành công " + sent + "/" + eligible
                + " tài khoản; thất bại " + failed + ". Tiêu đề: " + safeSubject);

        boolean success = sent > 0;
        int statusCode = success ? 200 : 500;
        String message = eligible == 0
                ? "Không có tài khoản người dùng nào để gửi thông báo."
                : "Đã gửi thành công " + sent + "/" + eligible
                    + " tài khoản; thất bại " + failed + " tài khoản.";

        sendResponse(exchange, statusCode,
                "{\"success\": " + success
                + ", \"sent\": " + sent
                + ", \"failed\": " + failed
                + ", \"total\": " + eligible
                + ", \"message\": \"" + escapeJson(message) + "\"}");
    }

    private synchronized void appendLog(String message) {
        try {
            File logFile = new File(SYSTEM_LOG_PATH);
            File parent = logFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            try (FileWriter fw = new FileWriter(logFile, StandardCharsets.UTF_8, true)) {
                fw.write("[LOG " + timeStr + "] " + message + "\n");
            }
        } catch (IOException ignored) {}
    }

    private void deleteUserMailbox(String pathStr) {
        File dir = new File(pathStr);
        if (!dir.exists()) return;

        try {
            Files.walk(dir.toPath())
                 .sorted(Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(f -> {
                     if (!f.delete()) {
                         appendLog("Không thể xóa file/folder: " + f.getAbsolutePath());
                     }
                 });
        } catch (IOException e) {
            appendLog("Lỗi khi xóa thư mục " + pathStr + ": " + e.getMessage());
        }
    }

    private String escapeJson(String raw) {
        if (raw == null) return "";
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\': escaped.append("\\\\"); break;
                case '"': escaped.append("\\\""); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (c < 0x20) escaped.append(String.format("\\u%04x", (int)c));
                    else escaped.append(c);
            }
        }
        return escaped.toString();
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
        for (String pair : body.split("&", -1)) {
            int separator = pair.indexOf('=');
            if (separator < 0) continue;
            String key = pair.substring(0, separator);
            if (key.equalsIgnoreCase(paramName)) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return null;
                }
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