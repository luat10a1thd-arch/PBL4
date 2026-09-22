package com.pbl4.mailserver.webapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;

/**
 * Web Server HTTP lắng nghe cổng 8080.
 * Phục vụ giao diện Web (HTML, CSS, JS) và tiếp nhận các yêu cầu API.
 */
public class ApiServer {

    private static final int PORT = 8080;

    public static void startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

            // 1. Phục vụ các file giao diện tĩnh (Static Files: HTML, CSS, JS)
            server.createContext("/", new StaticFileHandler());

            // 2. Đăng ký các cổng API xử lý Đăng nhập, Đăng ký, Gửi/Nhận mail
            server.createContext("/api/auth", new AuthHandler());
            server.createContext("/api/mails", new MailHandler());

            server.setExecutor(null); // Sử dụng default executor
            server.start();
            System.out.println("=== Web API Server đang chạy tại cổng: http://localhost:" + PORT + " ===");

        } catch (IOException e) {
            System.err.println("Lỗi khi khởi chạy Web Server: " + e.getMessage());
        }
    }

    /**
     * Handler đọc và trả về các file giao diện tĩnh từ thư mục src/main/resources/static/
     */
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            
            // Xử lý Clean URL: Tự động ánh xạ route chuẩn
            if (path.equals("/")) {
                path = "/index.html";
            } else if (path.equals("/main")) {
                path = "/main.html";
            } else if (path.equals("/index")) {
                path = "/index.html";
            }

            File file = new File("src/main/resources/static" + path);

            if (file.exists() && !file.isDirectory()) {
                byte[] bytes = Files.readAllBytes(file.toPath());
                String contentType = getContentType(path);
                
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, bytes.length);
                
                OutputStream os = exchange.getResponseBody();
                os.write(bytes);
                os.close();
            } else {
                // Trả về lỗi 404 nếu không tìm thấy file
                String response = "404 Not Found";
                exchange.sendResponseHeaders(404, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        }

        private String getContentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=UTF-8";
            if (path.endsWith(".css")) return "text/css; charset=UTF-8";
            if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
            if (path.endsWith(".png")) return "image/png";
            if (path.endsWith(".jpg")) return "image/jpeg";
            return "text/plain";
        }
    }
}