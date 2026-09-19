// ============================================================
// File: MailHandler.java
// Package: com.pbl4.mailserver.webapi
// ------------------------------------------------------------
// Chức năng: Xử lý 3 route:
//   GET    /api/inbox?username=xxx        -> danh sách mail
//   POST   /api/send                       -> gửi mail mới
//   DELETE /api/mail?username=xxx&id=1     -> xóa 1 mail
// ============================================================

package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.core.MailStorageEngine;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MailHandler {

    private final MailStorageEngine storageEngine;

    public MailHandler(MailStorageEngine storageEngine) {
        this.storageEngine = storageEngine;
    }

    // ------------------------------------------------------------
    // GET /api/inbox?username=xxx
    // ------------------------------------------------------------
    public void handleInbox(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
        String username = query.get("username");
        if (username == null || username.isBlank()) {
            AuthHandler.sendJson(exchange, 400, "{\"error\":\"Thiếu username\"}");
            return;
        }

        List<String> files = storageEngine.listMailFiles(username);
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < files.size(); i++) {
            String fileName = files.get(i);
            String content = storageEngine.readMail(username, fileName);
            String sender = extractSender(content);
            String preview = extractPreview(content);
            long size = storageEngine.getMailSize(username, fileName);

            if (i > 0) json.append(",");
            json.append("{")
                .append("\"id\":").append(i + 1).append(",")
                .append("\"sender\":\"").append(AuthHandler.escape(sender)).append("\",")
                .append("\"preview\":\"").append(AuthHandler.escape(preview)).append("\",")
                .append("\"size\":").append(size)
                .append("}");
        }
        json.append("]");

        AuthHandler.sendJson(exchange, 200, json.toString());
    }

    // ------------------------------------------------------------
    // POST /api/send  { "from": "...", "to": "...", "subject": "...", "body": "..." }
    // ------------------------------------------------------------
    public void handleSend(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            AuthHandler.sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        Map<String, String> body = AuthHandler.parseJsonBody(exchange);
        String from = body.get("from");
        String to = body.get("to");
        String subject = body.getOrDefault("subject", "(no subject)");
        String content = body.getOrDefault("body", "");

        if (from == null || to == null) {
            AuthHandler.sendJson(exchange, 400, "{\"error\":\"Thiếu from hoặc to\"}");
            return;
        }

        // Gộp subject vào đầu nội dung, giống cách SmtpHandler đang làm với sender,
        // vì MailStorageEngine.writeMail() không có tham số subject riêng.
        String fullBody = "Subject: " + subject + "\n" + content;
        storageEngine.writeMail(from, to, fullBody);

        AuthHandler.sendJson(exchange, 200, "{\"success\":true}");
    }

    // ------------------------------------------------------------
    // DELETE /api/mail?username=xxx&id=1
    // Vì WebAPI không có khái niệm "session POP3" (không có QUIT),
    // ở đây xóa LUÔN ngay lập tức, không cần đợi purge riêng.
    // ------------------------------------------------------------
    public void handleDelete(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("DELETE")) {
            AuthHandler.sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
        String username = query.get("username");
        String idStr = query.get("id");

        if (username == null || idStr == null) {
            AuthHandler.sendJson(exchange, 400, "{\"error\":\"Thiếu username hoặc id\"}");
            return;
        }

        int index = Integer.parseInt(idStr) - 1; // id phía client là 1-based
        List<String> files = storageEngine.listMailFiles(username);
        if (index < 0 || index >= files.size()) {
            AuthHandler.sendJson(exchange, 404, "{\"error\":\"Không tìm thấy mail\"}");
            return;
        }

        storageEngine.markForDeletion(username, files.get(index));
        storageEngine.purgeMarkedDeletions(username); // xóa thật ngay

        AuthHandler.sendJson(exchange, 200, "{\"success\":true}");
    }

    // ------------------------------------------------------------
    // Hàm phụ trợ: trích dòng "From: ..." và preview từ nội dung đã giải mã
    // ------------------------------------------------------------
    private String extractSender(String content) {
        if (content == null) return "(unknown)";
        for (String line : content.split("\n")) {
            if (line.startsWith("From: ")) return line.substring(6).trim();
        }
        return "(unknown)";
    }

    private String extractPreview(String content) {
        if (content == null) return "";
        String[] lines = content.split("\n");
        // Bỏ qua dòng "From:" đầu tiên, lấy dòng tiếp theo làm preview
        for (String line : lines) {
            if (!line.startsWith("From: ") && !line.isBlank()) {
                return line.length() > 80 ? line.substring(0, 80) + "..." : line;
            }
        }
        return "";
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null) return result;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            try {
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                result.put(key, value);
            } catch (Exception ignored) {}
        }
        return result;
    }
}