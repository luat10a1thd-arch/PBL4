package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.LoginThrottle;
import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.SecurityUtils;
import com.pbl4.mailserver.core.UserStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

public class AuthHandler implements HttpHandler {

    private static final String ADMIN_EMAIL = ServerConfig.ADMIN_EMAIL;
    private static final Pattern SAFE_EMAIL_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,31}@" + Pattern.quote(ServerConfig.MAIL_DOMAIN.toLowerCase(Locale.ROOT)) + "$");
    private static final String DUMMY_HASH = SecurityUtils.hashPassword("DummyPassword123!");

    public AuthHandler() {
        ensureAdminAccountExists();
    }

    private synchronized void ensureAdminAccountExists() {
        try {
            File dataDir = new File("data");
            if (!dataDir.exists()) {
                dataDir.mkdirs();
            }

            if (!UserStore.exists(ADMIN_EMAIL)) {
                String passwordHash = SecurityUtils.hashPassword(ServerConfig.ADMIN_DEFAULT_PASS);
                UserStore.addUserIfAbsent(ADMIN_EMAIL, passwordHash);
                MailStorageEngine.initMailbox(ADMIN_EMAIL);
                System.out.println("[AUTH] Admin account initialized: " + ADMIN_EMAIL);
            }
        } catch (Exception e) {
            System.err.println("[AUTH] Error initializing Admin account: " + e.getMessage());
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // CORS do CorsHandlerWrapper đặt một lần duy nhất, không thêm header CORS ở đây

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
            boolean isRegister = path.endsWith("/register");
            boolean isLogin = path.endsWith("/login");
            if (!isRegister && !isLogin) {
                sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Endpoint không tồn tại!\"}");
                return;
            }

            // Giới hạn body 4KB + timeout đọc (vượt => 413/408)
            String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
            if (body == null) return;

            String username = getParamValue(body, "username");
            String password = getParamValue(body, "password");

            if (username == null || password == null || username.trim().isEmpty() || password.isEmpty()) {
                sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu thông tin tài khoản hoặc mật khẩu!\"}");
                return;
            }

            username = username.trim().toLowerCase(Locale.ROOT);

            if (isRegister) {
                handleRegister(exchange, username, password);
            } else {
                handleLogin(exchange, username, password);
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

    private void handleRegister(HttpExchange exchange, String username, String password) throws IOException {
        // Giới hạn số lần đăng ký theo IP (chống spam tạo tài khoản; băm mật khẩu rất tốn CPU)
        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (LoginThrottle.isRegisterThrottled(clientIp)) {
            sendResponse(exchange, 429, "{\"success\": false, \"message\": \"Bạn đăng ký quá nhiều lần. Vui lòng thử lại sau 15 phút!\"}");
            return;
        }
        LoginThrottle.recordRegistration(clientIp);

        if (!SAFE_EMAIL_PATTERN.matcher(username).matches()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Tên tài khoản chứa ký tự không hợp lệ hoặc sai định dạng @" + ServerConfig.MAIL_DOMAIN.toLowerCase(Locale.ROOT) + "!\"}");
            return;
        }

        String emailPrefix = username.substring(0, username.indexOf('@'));
        if (ADMIN_EMAIL.equals(username) || emailPrefix.startsWith("admin")) {
            sendResponse(exchange, 403, "{\"success\": false, \"message\": \"Không được phép đăng ký tài khoản Admin!\"}");
            return;
        }

        String passwordValidationResult = validatePasswordStrength(password);
        if (passwordValidationResult != null) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"" + passwordValidationResult + "\"}");
            return;
        }

        String passwordHash = SecurityUtils.hashPassword(password);
        boolean added = UserStore.addUserIfAbsent(username, passwordHash);

        if (added) {
            MailStorageEngine.initMailbox(username);
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đăng ký tài khoản thành công!\"}");
        } else {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Tài khoản đã tồn tại!\"}");
        }
    }

    private String validatePasswordStrength(String password) {
        if (password == null || password.length() < 8 || password.length() > 32) {
            return "Mật khẩu phải có độ dài từ 8 đến 32 ký tự!";
        }
        
        boolean hasLetter = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;

        for (char c : password.toCharArray()) {
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            } else {
                hasSpecial = true;
            }
        }

        if (!hasLetter) return "Mật khẩu phải chứa ít nhất một chữ cái!";
        if (!hasDigit) return "Mật khẩu phải chứa ít nhất một chữ số!";
        if (!hasSpecial) return "Mật khẩu phải chứa ít nhất một ký tự đặc biệt (!@#$%^&*...)!";

        return null;
    }

    private void handleLogin(HttpExchange exchange, String username, String password) throws IOException {
        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();

        if (LoginThrottle.isThrottled(clientIp, username)) {
            sendResponse(exchange, 429, "{\"success\": false, \"message\": \"Tài khoản tạm thời bị khóa do thử sai mật khẩu quá nhiều lần. Vui lòng thử lại sau 15 phút!\"}");
            return;
        }

        String storedHash = UserStore.findHash(username);
        boolean userExists = (storedHash != null);

        if (!userExists) {
            SecurityUtils.checkPassword(password, DUMMY_HASH);
            LoginThrottle.recordFailedAttempt(clientIp, username);
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Sai tài khoản hoặc mật khẩu!\"}");
            return;
        }

        // Kiểm tra mật khẩu TRƯỚC: chỉ người biết đúng mật khẩu mới được biết tài khoản đang bị khóa
        boolean isValid = SecurityUtils.checkPassword(password, storedHash);
        if (!isValid) {
            LoginThrottle.recordFailedAttempt(clientIp, username);
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Sai tài khoản hoặc mật khẩu!\"}");
            return;
        }

        LoginThrottle.resetAttempts(clientIp, username);

        if (checkIsLocked(username)) {
            sendResponse(exchange, 403, "{\"success\": false, \"message\": \"Tài khoản của bạn đã bị Admin khóa!\"}");
            return;
        }

        String token = SessionManager.createSession(username);
        String role = ADMIN_EMAIL.equalsIgnoreCase(username) ? "ADMIN" : "USER";

        sendResponse(exchange, 200,
            "{\"success\": true, \"message\": \"Đăng nhập thành công!\", " +
            "\"username\": \"" + username + "\", " +
            "\"token\": \"" + token + "\", " +
            "\"role\": \"" + role + "\"}");
    }

    private boolean checkIsLocked(String username) {
        return "LOCKED".equalsIgnoreCase(UserStore.getStatus(username));
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
