package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.SecurityUtils;
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

/**
 * Xử lý yêu cầu API đăng ký và đăng nhập người dùng thông qua file users.json
 */
public class AuthHandler implements HttpHandler {

    private static final String USERS_FILE = "data/users.json";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Cho phép CORS để giao diện Web gửi request không bị chặn
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            String path = exchange.getRequestURI().getPath();
            
            // Đọc dữ liệu gửi lên từ JS (Body format: username=...&password=...)
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
        // Kiểm tra xem user đã tồn tại chưa
        if (findUserHash(username) != null) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Tài khoản đã tồn tại!\"}");
            return;
        }

        // Băm mật khẩu bằng BCrypt
        String passwordHash = SecurityUtils.hashPassword(password);

        // Lưu tài khoản mới vào file users.json
        saveUserToFile(username, passwordHash);

        // Tạo sẵn hòm thư đĩa cho user
        MailStorageEngine.initMailbox(username);

        sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đăng ký tài khoản thành công!\"}");
    }

    private void handleLogin(HttpExchange exchange, String username, String password) throws IOException {
        String storedHash = findUserHash(username);

        if (storedHash == null) {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Tài khoản không tồn tại!\"}");
            return;
        }

        // Kiểm tra mật khẩu băm BCrypt
        boolean isValid = SecurityUtils.checkPassword(password, storedHash);
        if (isValid) {
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đăng nhập thành công!\", \"username\": \"" + username + "\"}");
        } else {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Mật khẩu không chính xác!\"}");
        }
    }

    private String findUserHash(String username) {
        File file = new File(USERS_FILE);
        if (!file.exists()) return null;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("\"username\": \"" + username + "\"")) {
                    String nextLine = reader.readLine();
                    if (nextLine != null && nextLine.contains("passwordHash")) {
                        return nextLine.split(":")[1].replace("\"", "").replace("}", "").trim();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void saveUserToFile(String username, String passwordHash) {
        File dataDir = new File("data");
        if (!dataDir.exists()) dataDir.mkdirs();

        File file = new File(USERS_FILE);
        boolean isNew = !file.exists() || file.length() == 0;

        try (FileWriter writer = new FileWriter(file, true)) {
            if (isNew) {
                writer.write("[\n");
            } else {
                // Xóa dấu đóng mảng cũ để nối thêm
            }
            String userJson = "  {\n    \"username\": \"" + username + "\",\n    \"passwordHash\": \"" + passwordHash + "\"\n  },\n";
            writer.write(userJson);
        } catch (IOException e) {
            e.printStackTrace();
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