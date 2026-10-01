package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.SecurityUtils;
import com.pbl4.mailserver.core.UserStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class AuthHandler implements HttpHandler {

    private static final String ADMIN_EMAIL = "admin@pbl4.com";
    private static final String ADMIN_DEFAULT_PASS = "@Luat0105";

    public AuthHandler() {
        ensureAdminAccountExists();
    }

    private synchronized void ensureAdminAccountExists() {
        try {
            if (!UserStore.exists(ADMIN_EMAIL)) {
                String passwordHash = SecurityUtils.hashPassword(ADMIN_DEFAULT_PASS);
                UserStore.addUser(ADMIN_EMAIL, passwordHash);
                MailStorageEngine.initMailbox(ADMIN_EMAIL);
                System.out.println("[AUTH] Đã tự động khởi tạo tài khoản Admin: " + ADMIN_EMAIL);
            }
        } catch (Exception e) {
            System.err.println("[AUTH] Lỗi khi tạo tài khoản Admin mặc định: " + e.getMessage());
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && path.endsWith("/logout")) {
            handleLogout(exchange);
            return;
        }

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
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

            if (username == null || password == null || username.trim().isEmpty() || password.isEmpty()) {
                sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu thông tin tài khoản hoặc mật khẩu!\"}");
                return;
            }

            username = username.trim();

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

    private void handleLogout(HttpExchange exchange) throws IOException {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            SessionManager.invalidate(token);
        }
        sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã đăng xuất thành công!\"}");
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

        // Kiểm tra nếu tài khoản đang trong trạng thái LOCKED
        if (checkIsLocked(username)) {
            sendResponse(exchange, 403, "{\"success\": false, \"message\": \"Tài khoản của bạn đã bị Admin khóa!\"}");
            return;
        }

        boolean isValid = SecurityUtils.checkPassword(password, storedHash);
        if (isValid) {
            String token = SessionManager.createSession(username, password);
            String role = ADMIN_EMAIL.equalsIgnoreCase(username) ? "ADMIN" : "USER";

            sendResponse(exchange, 200,
                "{\"success\": true, \"message\": \"Đăng nhập thành công!\", " +
                "\"username\": \"" + username + "\", " +
                "\"token\": \"" + token + "\", " +
                "\"role\": \"" + role + "\"}");
        } else {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Mật khẩu không chính xác!\"}");
        }
    }

    private boolean checkIsLocked(String username) {
        File file = new File("data/users.json");
        if (!file.exists()) return false;

        try (BufferedReader br = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("\"username\":\"" + username + "\"") || line.contains("\"" + username + "\"")) {
                    return line.contains("\"status\":\"LOCKED\"");
                }
            }
        } catch (IOException ignored) {}
        return false;
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