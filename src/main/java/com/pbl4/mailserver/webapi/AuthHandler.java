package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.SecurityUtils;
import com.pbl4.mailserver.core.UserStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public class AuthHandler implements HttpHandler {

    // ------------------------------------------------------------
    // Domain cố định của hệ thống - PHẢI khớp với MAIL_DOMAIN bên
    // phía frontend (js). Regex chỉ chấp nhận: chữ thường/hoa, số,
    // dấu chấm, gạch dưới, gạch ngang ở phần tên, theo sau đúng
    // "@pbl4.com". Kiểm tra ở đây để không phụ thuộc vào validate
    // phía client (client có thể bị bỏ qua nếu gọi thẳng API).
    // ------------------------------------------------------------
    private static final Pattern VALID_EMAIL_PATTERN =
            Pattern.compile("^[a-zA-Z0-9._-]{3,32}@pbl4\\.com$");

    private static boolean isValidEmail(String email) {
        return email != null && VALID_EMAIL_PATTERN.matcher(email).matches();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            String path = exchange.getRequestURI().getPath();

            InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
            BufferedReader br = new BufferedReader(isr);
            StringBuilder formData = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                formData.append(line);
            }

            String body = formData.toString();
            String username = getParamValue(body, "username");
            String password = getParamValue(body, "password");

            if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
                sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu thông tin tài khoản hoặc mật khẩu!\"}");
                return;
            }

            // ------------------------------------------------------------
            // Validate định dạng email NGAY TẠI SERVER - không tin dữ liệu
            // từ client, kể cả khi frontend đã kiểm tra rồi.
            // ------------------------------------------------------------
            if (!isValidEmail(username)) {
                sendResponse(exchange, 400,
                    "{\"success\": false, \"message\": \"Tên đăng nhập không hợp lệ! Chỉ chấp nhận dạng ten@pbl4.com (3-32 ký tự chữ/số/._- )\"}");
                return;
            }

            if (path.endsWith("/register")) {
                handleRegister(exchange, username, password);
            } else if (path.endsWith("/login")) {
                handleLogin(exchange, username, password);
            } else {
                sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Endpoint không tồn tại!\"}");
            }
        } else {
            sendResponse(exchange, 405, "{\"success\": false, \"message\": \"Phương thức không được hỗ trợ!\"}");
        }
    }

    private synchronized void handleRegister(HttpExchange exchange, String username, String password) throws IOException {
        if (UserStore.exists(username)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Tài khoản đã tồn tại!\"}");
            return;
        }

        String passwordHash = SecurityUtils.hashPassword(password);
        UserStore.addUser(username, passwordHash);

        MailStorageEngine.initMailbox(username);

        sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đăng ký tài khoản thành công!\"}");
    }

    private void handleLogin(HttpExchange exchange, String username, String password) throws IOException {
        String storedHash = UserStore.findHash(username);

        if (storedHash == null) {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Tài khoản không tồn tại!\"}");
            return;
        }

        boolean isValid = SecurityUtils.checkPassword(password, storedHash);
        if (isValid) {
            String token = SessionManager.createSession(username, password);
            sendResponse(exchange, 200,
                "{\"success\": true, \"message\": \"Đăng nhập thành công!\", " +
                "\"username\": \"" + username + "\", \"token\": \"" + token + "\"}");
        } else {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Mật khẩu không chính xác!\"}");
        }
    }

    private String getParamValue(String body, String paramName) {
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