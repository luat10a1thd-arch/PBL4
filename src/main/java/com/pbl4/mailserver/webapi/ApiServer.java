package com.pbl4.mailserver.webapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;

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

            // 2. Đăng ký các cổng API xử lý Đăng nhập, Đăng ký, Gửi/Nhận mail & Quản trị (Admin)
            server.createContext("/api/auth", new CorsHandlerWrapper(new AuthHandler()));
            server.createContext("/api/mails", new CorsHandlerWrapper(new MailHandler()));
            server.createContext("/api/admin", new CorsHandlerWrapper(new AdminHandler()));

            server.setExecutor(null); // Sử dụng default executor
            server.start();
            System.out.println("=== Web API Server đang chạy tại cổng: http://localhost:" + PORT + " ===");

        } catch (IOException e) {
            System.err.println("Lỗi khi khởi chạy Web Server: " + e.getMessage());
        }
    }

    /**
     * FIX VẤN ĐỀ 12 BÁO CÁO: Quản lý CORS tập trung và đọc linh hoạt từ biến môi trường.
     * Mặc định fallback về "http://localhost:8080" hoặc "*" khi chạy Dev local để thuận tiện demo.
     */
    static class CorsHandlerWrapper implements HttpHandler {
        private final HttpHandler handler;
        
        // Đọc origin được phép từ biến môi trường PBL4_ALLOWED_ORIGIN, fallback an toàn cho Dev local
        private static final String ALLOWED_ORIGIN = System.getenv().getOrDefault("PBL4_ALLOWED_ORIGIN", "http://localhost:8080");

        public CorsHandlerWrapper(HttpHandler handler) {
            this.handler = handler;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");

            // Xử lý yêu cầu Pre-flight (OPTIONS) của trình duyệt
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            handler.handle(exchange);
        }
    }

    /**
     * Handler đọc và trả về các file giao diện tĩnh từ classpath (bên trong file JAR/resources)
     */
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            
            // Xử lý Clean URL: Tự động ánh xạ route chuẩn
            if (path.equals("/") || path.equals("/index") || path.equals("/index.html")) {
                path = "/index.html";
            } else if (path.equals("/main") || path.equals("/main.html")) {
                path = "/main.html";
            } else if (path.equals("/admin") || path.equals("/admin.html")) {
                path = "/admin.html";
            }

            // Đọc file từ classpath bên trong file JAR (src/main/resources/static)
            String resourcePath = "/static" + path;
            InputStream is = getClass().getResourceAsStream(resourcePath);

            if (is != null) {
                byte[] bytes = is.readAllBytes();
                String contentType = getContentType(path);
                
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, bytes.length);
                
                OutputStream os = exchange.getResponseBody();
                os.write(bytes);
                os.close();
                is.close();
            } else {
                // Trả về lỗi 404 nếu không tìm thấy file HTML/CSS/JS
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
            if (path.endsWith(".svg")) return "image/svg+xml";
            return "text/plain";
        }
    }
}