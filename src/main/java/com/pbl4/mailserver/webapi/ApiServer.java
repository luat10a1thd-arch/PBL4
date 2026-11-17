// FILE: src/main/java/com/pbl4/mailserver/webapi/ApiServer.java
package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.config.ServerConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

public class ApiServer {

    private static final int PORT = ServerConfig.HTTP_PORT;

    public static void startServer() {
        try {
            // Đặt cấu hình Timeout cho Server HTTP
            System.setProperty("sun.net.httpserver.maxReqTime", String.valueOf(ServerConfig.HTTP_MAX_REQ_TIME_SECONDS));
            // LƯU Ý: KHÔNG đặt "sun.net.httpserver.maxRspTime" (tên đúng; trước đây ghi sai thành maxRespTime nên không có tác dụng).
            // Nếu đặt, JDK sẽ cắt mọi response chạy quá thời gian đó - kể cả luồng SSE sống lâu. Mặc định (-1) = không giới hạn.
            System.setProperty("sun.net.httpserver.idleInterval", "30");

            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 256);

            server.createContext("/", new StaticFileHandler());
            server.createContext("/api/auth", new CorsHandlerWrapper(new AuthHandler()));
            server.createContext("/api/mails", new CorsHandlerWrapper(new MailHandler()));
            server.createContext("/api/admin", new CorsHandlerWrapper(new AdminHandler()));
            server.createContext("/api/settings", new CorsHandlerWrapper(new SettingsHandler()));

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

    private static final String CSP = "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com https://fonts.googleapis.com; font-src 'self' https://cdnjs.cloudflare.com https://fonts.gstatic.com; img-src 'self' data:;";

    // Header bảo mật dùng chung cho cả /api/* và trang tĩnh (HTML/JS/CSS)
    static void applySecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Content-Security-Policy", CSP);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("X-XSS-Protection", "1; mode=block");
    }

    // ===================== Đọc body HTTP có giới hạn =====================

    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException(String m) { super(m); }
    }

    static final class BodyTimeoutException extends IOException {
        BodyTimeoutException(String m) { super(m); }
    }

    // Watchdog dùng chung: đóng kết nối của client gửi body quá chậm để không giữ luồng worker
    private static final ScheduledThreadPoolExecutor BODY_WATCHDOG = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "http-body-watchdog");
        t.setDaemon(true);
        return t;
    });

    static {
        BODY_WATCHDOG.setRemoveOnCancelPolicy(true);
    }

    private static final class BodyWatch implements Runnable {
        final HttpExchange exchange;
        final long start = System.currentTimeMillis();
        final long idleMs;
        final long totalMs;
        volatile long lastProgress = start;
        volatile boolean done;
        volatile boolean timedOut;
        volatile ScheduledFuture<?> future;

        BodyWatch(HttpExchange exchange, long idleMs, long totalMs) {
            this.exchange = exchange;
            this.idleMs = idleMs;
            this.totalMs = totalMs;
        }

        void schedule(long delayMs) {
            future = BODY_WATCHDOG.schedule(this, Math.max(delayMs, 50), TimeUnit.MILLISECONDS);
        }

        @Override
        public void run() {
            if (done) return;
            long now = System.currentTimeMillis();
            long idleLeft = idleMs - (now - lastProgress);
            long totalLeft = totalMs - (now - start);
            if (idleLeft <= 0 || totalLeft <= 0) {
                timedOut = true;
                try { exchange.close(); } catch (Exception ignored) {} // đóng socket => read() đang chờ sẽ ném IOException
            } else {
                schedule(Math.min(idleLeft, totalLeft));
            }
        }
    }

    /**
     * Đọc body với giới hạn byte (kiểm tra Content-Length trước, rồi đếm khi đọc - áp dụng cả chunked)
     * và timeout đọc. Ném BodyTooLargeException / BodyTimeoutException.
     */
    static String readBody(HttpExchange exchange, int maxBytes, long totalTimeoutMs) throws IOException {
        String cl = exchange.getRequestHeaders().getFirst("Content-Length");
        if (cl != null) {
            try {
                if (Long.parseLong(cl.trim()) > maxBytes) {
                    throw new BodyTooLargeException("Content-Length vượt giới hạn " + maxBytes);
                }
            } catch (NumberFormatException e) {
                throw new IOException("Content-Length không hợp lệ");
            }
        }

        BodyWatch watch = new BodyWatch(exchange, ServerConfig.BODY_READ_IDLE_TIMEOUT_MS, totalTimeoutMs);
        watch.schedule(Math.min(ServerConfig.BODY_READ_IDLE_TIMEOUT_MS, totalTimeoutMs));
        // Không đóng InputStream ở đây: đóng sẽ cố "xả" phần body còn lại (có thể chặn với client chậm / body quá lớn)
        try {
            InputStream in = exchange.getRequestBody();
            ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                watch.lastProgress = System.currentTimeMillis();
                total += n;
                if (total > maxBytes) {
                    throw new BodyTooLargeException("Body vượt giới hạn " + maxBytes);
                }
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (watch.timedOut) throw new BodyTimeoutException("Hết thời gian đọc body");
            throw e;
        } finally {
            watch.done = true;
            ScheduledFuture<?> f = watch.future;
            if (f != null) f.cancel(false);
        }
    }

    /**
     * Đọc body; nếu quá lớn trả 413, quá chậm trả 408 (đã phản hồi) và trả về null -
     * handler gọi xong chỉ cần `if (body == null) return;`.
     */
    static String readBodyOrReply(HttpExchange exchange, int maxBytes, long totalTimeoutMs) throws IOException {
        try {
            return readBody(exchange, maxBytes, totalTimeoutMs);
        } catch (BodyTooLargeException e) {
            sendJson(exchange, 413, "{\"success\": false, \"message\": \"Dữ liệu gửi lên quá lớn!\"}", true);
            return null;
        } catch (BodyTimeoutException e) {
            // Kết nối đã bị đóng; thử trả 408 cho đủ lễ nghi, lỗi thì bỏ qua
            try { sendJson(exchange, 408, "{\"success\": false, \"message\": \"Hết thời gian chờ dữ liệu!\"}", true); } catch (Exception ignored) {}
            return null;
        }
    }

    // Gửi JSON; closeConn=true thì yêu cầu đóng kết nối (khi body chưa đọc hết, không dùng lại keep-alive)
    static void sendJson(HttpExchange exchange, int statusCode, String json, boolean closeConn) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        if (closeConn) exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ===================== Bảo vệ phiên bằng cookie: kiểm tra CSRF + Origin =====================

    private static boolean isStateChanging(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
    }

    /** Điểm vào không cần phiên: đăng nhập/đăng ký/quên-đặt lại mật khẩu/tùy chọn khôi phục, và SSE (xác thực bằng ticket). */
    private static boolean isPublicEndpoint(String path) {
        if (path.startsWith("/api/auth/")) {
            return path.endsWith("/login") || path.endsWith("/register")
                    || path.endsWith("/forgot-password") || path.endsWith("/reset-password")
                    || path.endsWith("/recovery-options");
        }
        return path.startsWith("/api/mails/") && path.endsWith("/stream");
    }

    private static String stripSlash(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    /** Không có Origin: cho qua. Có Origin: phải bằng PBL4_ALLOWED_ORIGIN hoặc cùng nguồn (scheme+host+cổng) với yêu cầu. */
    static boolean originAllowed(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true;
        String o = stripSlash(origin);
        if (o.isEmpty() || o.equals("null")) return false;
        if (o.equals(stripSlash(CorsHandlerWrapper.allowedOrigin()))) return true;
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || host.trim().isEmpty()) return false;
        String scheme = (ex instanceof HttpsExchange) ? "https" : "http";
        return o.equals(scheme + "://" + host.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean constantTimeEq(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static void denyForbidden(HttpExchange ex) throws IOException {
        sendJson(ex, 403, "{\"success\": false, \"message\": \"Yêu cầu không hợp lệ!\"}", true);
    }

    /**
     * Trả true nếu được đi tiếp. Trả false khi đã tự phản hồi (401/403).
     * - Điểm vào công khai: chỉ kiểm tra Origin (khi là yêu cầu ghi).
     * - Authorization: Bearer hoặc không có thông tin xác thực: miễn CSRF (hành vi cũ).
     * - Xác thực bằng COOKIE: phiên phải còn (không thì 401 + xóa cookie); yêu cầu ghi phải đúng Origin
     *   và header X-CSRF-Token trùng mã CSRF của phiên (so sánh hằng thời gian), sai thì 403.
     */
    static boolean enforceSessionGuard(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        boolean writes = isStateChanging(ex.getRequestMethod());

        if (isPublicEndpoint(path)) {
            if (writes && !originAllowed(ex)) {
                denyForbidden(ex);
                return false;
            }
            return true;
        }

        if (!SessionManager.isCookieRequest(ex)) return true;

        String token = SessionManager.tokenFromRequest(ex);
        if (SessionManager.validate(token) == null) {
            SessionCookies.clear(ex);
            sendJson(ex, 401, "{\"success\": false, \"message\": \"Chưa đăng nhập hoặc phiên hết hạn!\"}", writes);
            return false;
        }
        if (!writes) return true;

        if (!originAllowed(ex)) {
            denyForbidden(ex);
            return false;
        }
        String expected = SessionManager.csrfTokenOf(token);
        String given = ex.getRequestHeaders().getFirst("X-CSRF-Token");
        if (expected == null || given == null || !constantTimeEq(expected, given.trim())) {
            denyForbidden(ex);
            return false;
        }
        return true;
    }

    static class CorsHandlerWrapper implements HttpHandler {
        private final HttpHandler handler;
        private static final String ALLOWED_ORIGIN = System.getenv().getOrDefault("PBL4_ALLOWED_ORIGIN", "http://localhost:8080");

        public CorsHandlerWrapper(HttpHandler handler) {
            this.handler = handler;
        }

        static String allowedOrigin() {
            return ALLOWED_ORIGIN;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, PATCH, DELETE");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-CSRF-Token");
            exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");

            applySecurityHeaders(exchange);

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!enforceSessionGuard(exchange)) return;

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
            applySecurityHeaders(exchange); // CSP / nosniff / X-Frame-Options cho cả trang HTML tĩnh
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

                // Thay domain mặc định bằng domain cấu hình (PBL4_MAIL_DOMAIN) trong tệp văn bản tĩnh
                // (placeholder, nhãn, kiểm tra endsWith('@...'), so sánh admin). Kết quả được cache.
                if (path.endsWith(".html") || path.endsWith(".js")) {
                    String domain = ServerConfig.MAIL_DOMAIN.toLowerCase(Locale.ROOT);
                    if (!"pbl4.com".equals(domain)) {
                        raw = new String(raw, StandardCharsets.UTF_8).replace("pbl4.com", domain).getBytes(StandardCharsets.UTF_8);
                    }
                }

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