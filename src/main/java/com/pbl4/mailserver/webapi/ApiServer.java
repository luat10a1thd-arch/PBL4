package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.config.ServerConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

public class ApiServer {

    private static final int PORT = ServerConfig.HTTP_PORT;

    public static void startServer() {
        try {
            // Đặt cấu hình Timeout cho Server HTTP
            System.setProperty("sun.net.httpserver.maxReqTime", "30");
            System.setProperty("sun.net.httpserver.maxRespTime", "120");
            System.setProperty("sun.net.httpserver.idleInterval", "30");

            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 256);

            server.createContext("/", new StaticFileHandler());
            server.createContext("/api/auth", new CorsHandlerWrapper(new AuthHandler()));
            server.createContext("/api/mails", new CorsHandlerWrapper(new MailHandler()));
            server.createContext("/api/admin", new CorsHandlerWrapper(new AdminHandler()));

            // Tạo ThreadPool riêng cho HTTP với tên http-worker-N (Daemon Threads)
            AtomicInteger threadNumber = new AtomicInteger(1);
            ThreadFactory daemonThreadFactory = runnable -> {
                Thread t = new Thread(runnable, "http-worker-" + threadNumber.getAndIncrement());
                t.setDaemon(true);
                return t;
            };

            server.setExecutor(Executors.newFixedThreadPool(ServerConfig.HTTP_WORKER_THREADS, daemonThreadFactory));
            server.start();
            System.out.println("=== Web API Server running at: http://localhost:" + PORT + " ===");

        } catch (IOException e) {
            System.err.println("Lỗi khi khởi động Web Server: " + e.getMessage());
        }
    }

    static class CorsHandlerWrapper implements HttpHandler {
        private final HttpHandler handler;
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

            exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com https://fonts.googleapis.com; font-src 'self' https://cdnjs.cloudflare.com https://fonts.gstatic.com; img-src 'self' data:;");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
            exchange.getResponseHeaders().set("X-XSS-Protection", "1; mode=block");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            handler.handle(exchange);
        }
    }

    static class StaticFileHandler implements HttpHandler {

        private static class CachedFile {
            final byte[] rawData;
            final byte[] gzipData;
            final String contentType;
            final String etag;

            CachedFile(byte[] rawData, byte[] gzipData, String contentType, String etag) {
                this.rawData = rawData;
                this.gzipData = gzipData;
                this.contentType = contentType;
                this.etag = etag;
            }
        }

        private final ConcurrentHashMap<String, CachedFile> fileCache = new ConcurrentHashMap<>();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            String path = exchange.getRequestURI().getPath();

            // Chống truy cập trái phép (Path Traversal Protection)
            if (path.contains("..") || path.contains("\\") || path.contains("//")) {
                sendError(exchange, 400, "Bad Request");
                return;
            }

            if (path.equals("/") || path.equals("/index") || path.equals("/index.html")) {
                path = "/index.html";
            } else if (path.equals("/main") || path.equals("/main.html")) {
                path = "/main.html";
            } else if (path.equals("/admin") || path.equals("/admin.html")) {
                path = "/admin.html";
            }

            // Chỉ phục vụ tệp có phần mở rộng
            int lastDot = path.lastIndexOf('.');
            if (lastDot == -1 || lastDot == path.length() - 1) {
                sendError(exchange, 404, "Not Found");
                return;
            }

            CachedFile cached = fileCache.get(path);
            if (cached == null) {
                String resourcePath = "/static" + path;
                InputStream is = getClass().getResourceAsStream(resourcePath);
                if (is == null) {
                    sendError(exchange, 404, "404 Not Found");
                    return;
                }

                byte[] raw = is.readAllBytes();
                is.close();

                String contentType = getContentType(path);
                String etag = "\"" + computeETag(raw) + "\"";

                byte[] gzip = null;
                if (raw.length > 512 && isCompressible(contentType)) {
                    gzip = compressGzip(raw);
                }

                cached = new CachedFile(raw, gzip, contentType, etag);
                fileCache.put(path, cached);
            }

            exchange.getResponseHeaders().set("Content-Type", cached.contentType);
            exchange.getResponseHeaders().set("ETag", cached.etag);
            exchange.getResponseHeaders().set("Vary", "Accept-Encoding");

            if (path.endsWith(".html")) {
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            } else {
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=300");
            }

            // Kiểm tra Cache ETag người dùng
            String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (ifNoneMatch != null && ifNoneMatch.trim().equals(cached.etag)) {
                exchange.sendResponseHeaders(304, -1);
                return;
            }

            String acceptEncoding = exchange.getRequestHeaders().getFirst("Accept-Encoding");
            boolean supportsGzip = acceptEncoding != null && acceptEncoding.contains("gzip");

            byte[] bodyToSend;
            if (supportsGzip && cached.gzipData != null) {
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                bodyToSend = cached.gzipData;
            } else {
                bodyToSend = cached.rawData;
            }

            if ("HEAD".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            exchange.sendResponseHeaders(200, bodyToSend.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bodyToSend);
            }
        }

        private boolean isCompressible(String contentType) {
            return contentType.contains("text") || contentType.contains("javascript") || contentType.contains("json") || contentType.contains("svg");
        }

        private byte[] compressGzip(byte[] data) {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
                gzos.write(data);
                gzos.finish();
                return baos.toByteArray();
            } catch (Exception e) {
                return null;
            }
        }

        private String computeETag(byte[] data) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(data);
                return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 16);
            } catch (Exception e) {
                return String.valueOf(data.length);
            }
        }

        private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
            byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
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