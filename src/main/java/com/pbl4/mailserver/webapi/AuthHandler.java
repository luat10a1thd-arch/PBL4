// ============================================================
// File: AuthHandler.java
// Package: com.pbl4.mailserver.webapi
// ------------------------------------------------------------
// Chức năng: Xử lý 2 route:
//   POST /api/register -> tạo tài khoản mới
//   POST /api/login    -> kiểm tra đăng nhập
// Không dùng thư viện JSON ngoài - tự viết parser/writer đơn
// giản vì dữ liệu vào/ra chỉ có vài field cố định.
// ============================================================

package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.core.MailStorageEngine;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class AuthHandler {

    private final MailStorageEngine storageEngine;

    public AuthHandler(MailStorageEngine storageEngine) {
        this.storageEngine = storageEngine;
    }

    // ------------------------------------------------------------
    // POST /api/register  { "username": "...", "password": "..." }
    // ------------------------------------------------------------
    public void handleRegister(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        Map<String, String> body = parseJsonBody(exchange);
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || password == null || username.isBlank() || password.isBlank()) {
            sendJson(exchange, 400, "{\"error\":\"Thiếu username hoặc password\"}");
            return;
        }

        boolean success = storageEngine.createUser(username, password);
        if (success) {
            sendJson(exchange, 200, "{\"success\":true}");
        } else {
            sendJson(exchange, 409, "{\"error\":\"Username đã tồn tại\"}");
        }
    }

    // ------------------------------------------------------------
    // POST /api/login  { "username": "...", "password": "..." }
    // ------------------------------------------------------------
    public void handleLogin(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        Map<String, String> body = parseJsonBody(exchange);
        String username = body.get("username");
        String password = body.get("password");

        boolean valid = username != null && password != null
                && storageEngine.authenticate(username, password);

        if (valid) {
            sendJson(exchange, 200, "{\"success\":true,\"username\":\"" + escape(username) + "\"}");
        } else {
            sendJson(exchange, 401, "{\"error\":\"Sai username hoặc password\"}");
        }
    }

    // ------------------------------------------------------------
    // Hàm phụ trợ dùng chung - CÁC HANDLER KHÁC (MailHandler) CŨNG
    // NÊN DÙNG LẠI 2 hàm parseJsonBody() và sendJson() này để đồng
    // nhất cách xử lý (có thể copy nguyên hoặc tách ra file JsonUtil
    // riêng nếu muốn tái sử dụng gọn hơn).
    // ------------------------------------------------------------

    static Map<String, String> parseJsonBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        return parseFlatJson(raw);
    }

    // Parser JSON RẤT đơn giản - chỉ hỗ trợ object 1 cấp dạng
    // {"key1":"value1","key2":"value2"} - đủ dùng cho form login/register/send,
    // KHÔNG hỗ trợ object lồng nhau hay mảng.
    static Map<String, String> parseFlatJson(String raw) {
        Map<String, String> result = new HashMap<>();
        raw = raw.trim();
        if (raw.startsWith("{")) raw = raw.substring(1);
        if (raw.endsWith("}")) raw = raw.substring(0, raw.length() - 1);

        for (String pair : raw.split(",")) {
            int colonIndex = pair.indexOf(':');
            if (colonIndex < 0) continue;
            String key = unquote(pair.substring(0, colonIndex).trim());
            String value = unquote(pair.substring(colonIndex + 1).trim());
            result.put(key, value);
        }
        return result;
    }

    private static String unquote(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }
}