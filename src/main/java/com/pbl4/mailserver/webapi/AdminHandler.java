package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.core.MailStorageEngine;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class AdminHandler implements HttpHandler {

    private static final String USERS_FILE_PATH = "data/users.json";
    private static final String SYSTEM_LOG_PATH = "data/system.log";

    private static class UserStatus {
        String username;
        String status;

        UserStatus(String username, String status) {
            this.username = username;
            this.status = status != null ? status : "ACTIVE";
        }
    }

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
        List<UserStatus> users = getAllUsersFromFile();
        StringBuilder jsonBuilder = new StringBuilder("[");
        for (int i = 0; i < users.size(); i++) {
            UserStatus u = users.get(i);
            jsonBuilder.append("{\"username\":\"").append(escapeJson(u.username))
                       .append("\",\"status\":\"").append(escapeJson(u.status)).append("\"}");
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
        boolean updated = updateUserStatusInFile(targetUser, newStatus);

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
        boolean removed = removeUserFromFile(targetUser);
        if (removed) {
            File mailboxDir = new File("data/mailboxes", targetUser);
            if (mailboxDir.exists()) {
                deleteDir(mailboxDir);
            }
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

        // Tránh tiêu đề tạo thêm header giả.
        String safeSubject = subject.replace("\r", " ").replace("\n", " ").trim();
        List<UserStatus> users = getAllUsersFromFile();
        int sent = 0;
        int failed = 0;
        int eligible = 0;
        String nowStr = java.time.ZonedDateTime.now().toString();

        for (UserStatus user : users) {
            if (user.username == null || user.username.trim().isEmpty()
                    || user.username.equalsIgnoreCase("admin@pbl4.com")) {
                continue;
            }

            eligible++;
            String recipient = user.username.trim();
            try {
                // Dùng đúng cơ chế lưu email đang được POP3Client/MailHandler đọc.
                String rawEmail = "From: admin@pbl4.com\n"
                        + "To: " + recipient + "\n"
                        + "Subject: " + safeSubject + "\n"
                        + "Date: " + nowStr + "\n"
                        + "\n"
                        + content;

                boolean saved = MailStorageEngine.saveEmail(recipient, rawEmail);
                if (saved) {
                    sent++;
                    // Lưu bản sao trong Đã gửi của Admin, nhưng không tính lỗi bản sao
                    // thành lỗi gửi tới người nhận.
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

    private synchronized List<UserStatus> getAllUsersFromFile() {
        List<UserStatus> users = new ArrayList<>();
        File file = new File(USERS_FILE_PATH);
        if (!file.isFile()) return users;
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            // Mỗi user là một object JSON; regex chạy trên toàn file nên không phụ thuộc xuống dòng.
            Matcher objects = Pattern.compile("\\{[^{}]*\\}").matcher(json);
            while (objects.find()) {
                String object = objects.group();
                String username = extractJsonValue(object, "username");
                if (username == null) continue;
                String status = extractJsonValue(object, "status");
                users.add(new UserStatus(username, status == null ? "ACTIVE" : status));
            }
        } catch (IOException e) {
            appendLog("Không đọc được users.json: " + e.getMessage());
        }
        return users;
    }

    private synchronized boolean updateUserStatusInFile(String targetUser, String newStatus) {
        return rewriteUserObject(targetUser, newStatus, false);
    }

    private synchronized boolean removeUserFromFile(String targetUser) {
        return rewriteUserObject(targetUser, null, true);
    }

    private boolean rewriteUserObject(String targetUser, String newStatus, boolean remove) {
        File file = new File(USERS_FILE_PATH);
        if (!file.isFile() || targetUser == null) return false;
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("\\{[^{}]*\\}").matcher(json);
            StringBuffer output = new StringBuffer();
            boolean found = false;
            while (matcher.find()) {
                String object = matcher.group();
                String username = extractJsonValue(object, "username");
                if (username == null || !username.equalsIgnoreCase(targetUser)) {
                    matcher.appendReplacement(output, Matcher.quoteReplacement(object));
                    continue;
                }
                found = true;
                if (remove) {
                    // Bỏ object cùng dấu phẩy sát cạnh để giữ mảng JSON hợp lệ.
                    int start = matcher.start();
                    int end = matcher.end();
                    String before = json.substring(0, start);
                    String after = json.substring(end);
                    if (after.trim().startsWith(",")) {
                        int comma = json.indexOf(',', end);
                        matcher.appendReplacement(output, "");
                        // xử lý dấu phẩy bằng bước làm sạch phía dưới
                    } else if (before.trim().endsWith(",")) {
                        matcher.appendReplacement(output, "");
                    } else {
                        matcher.appendReplacement(output, "");
                    }
                } else {
                    String updated = object;
                    if (Pattern.compile("\"status\"\\s*:").matcher(object).find()) {
                        updated = object.replaceAll("\"status\"\\s*:\\s*\"[^\"]*\"", Matcher.quoteReplacement("\"status\":\"" + newStatus + "\""));
                    } else {
                        int close = updated.lastIndexOf('}');
                        updated = updated.substring(0, close) + ",\"status\":\"" + newStatus + "\"}";
                    }
                    matcher.appendReplacement(output, Matcher.quoteReplacement(updated));
                }
            }
            matcher.appendTail(output);
            if (!found) return false;
            String rewritten = output.toString();
            if (remove) {
                rewritten = rewritten.replaceAll(",\\s*,", ",");
                rewritten = rewritten.replaceAll("\\[\\s*,", "[");
                rewritten = rewritten.replaceAll(",\\s*]", "]");
            }
            File temp = new File(file.getPath() + ".tmp");
            Files.write(temp.toPath(), rewritten.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnavailable) {
                Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            appendLog("Không cập nhật được users.json: " + e.getMessage());
            return false;
        }
    }

    private String extractJsonValue(String jsonLine, String key) {
        try {
            String pattern = "\"" + key + "\"\\s*:\\s*\"";
            String[] parts = jsonLine.split(pattern);
            if (parts.length > 1) {
                return parts[1].split("\"")[0];
            }
        } catch (Exception ignored) {}
        return null;
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