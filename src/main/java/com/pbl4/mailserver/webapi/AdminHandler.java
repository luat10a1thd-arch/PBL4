package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.User;
import com.pbl4.mailserver.core.UserStore;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AdminHandler implements HttpHandler {

    private static final String SYSTEM_LOG_PATH = "data/system.log";

    // Broadcast chạy nền trên executor riêng (1 luồng, tối đa 2 job chờ) để không giữ luồng HTTP
    private static final ThreadPoolExecutor BROADCAST_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2),
            r -> {
                Thread t = new Thread(r, "admin-broadcast");
                t.setDaemon(true);
                return t;
            });

    // Cache /stats: việc duyệt toàn bộ hòm thư rất tốn I/O
    private static final Object STATS_LOCK = new Object();
    private static volatile long statsCachedAt = 0;
    private static volatile long cachedTotalMails;
    private static volatile long cachedTotalBytes;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Chưa đăng nhập!\"}");
            return;
        }

        String token = authHeader.substring(7);
        String currentUser = SessionManager.validate(token);

        if (currentUser == null) {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Phiên đăng nhập hết hạn hoặc không hợp lệ!\"}");
            return;
        }

        User u = UserStore.getUser(currentUser);
        if (u == null || !"ADMIN".equalsIgnoreCase(u.getRole())) {
            sendResponse(exchange, 403, "{\"success\": false, \"message\": \"Từ chối truy cập! Quyền Admin bắt buộc.\"}");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if ("GET".equalsIgnoreCase(method) && path.endsWith("/users")) {
            handleGetUsers(exchange);
            return;
        }

        if ("GET".equalsIgnoreCase(method) && path.endsWith("/logs")) {
            handleGetLogs(exchange);
            return;
        }

        if ("GET".equalsIgnoreCase(method) && path.endsWith("/stats")) {
            handleGetStats(exchange);
            return;
        }

        if ("POST".equalsIgnoreCase(method)) {
            String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_ADMIN_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
            if (body == null) return;
            String targetUser = getParamValue(body, "targetUser");

            if (path.endsWith("/user/delete")) {
                handleDeleteUser(exchange, targetUser);
            } else if (path.endsWith("/user/toggle-lock")) {
                String action = getParamValue(body, "action");
                handleToggleLockUser(exchange, targetUser, action);
            } else if (path.endsWith("/broadcast")) {
                String subject = getParamValue(body, "subject");
                String content = getParamValue(body, "body");
                handleBroadcast(exchange, subject, content);
            } else if (path.endsWith("/quota")) {
                String quotaStr = getParamValue(body, "quota");
                handleSaveQuota(exchange, quotaStr);
            } else {
                sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Endpoint không tồn tại!\"}");
            }
        } else {
            sendResponse(exchange, 405, "{\"success\": false, \"message\": \"Phương thức không hỗ trợ!\"}");
        }
    }

    private void handleGetUsers(HttpExchange exchange) throws IOException {
        List<User> users = UserStore.getAllUsers();
        StringBuilder jsonBuilder = new StringBuilder("[");
        for (int i = 0; i < users.size(); i++) {
            User u = users.get(i);
            jsonBuilder.append("{\"username\":\"").append(escapeJson(u.getUsername()))
                       .append("\",\"role\":\"").append(escapeJson(u.getRole() == null ? "USER" : u.getRole()))
                       .append("\",\"status\":\"").append(escapeJson(u.getStatus() == null ? "ACTIVE" : u.getStatus())).append("\"}");
            if (i < users.size() - 1) {
                jsonBuilder.append(",");
            }
        }
        jsonBuilder.append("]");

        sendResponse(exchange, 200, "{\"success\": true, \"users\": " + jsonBuilder.toString() + "}");
    }

    private void handleGetStats(HttpExchange exchange) throws IOException {
        long now = System.currentTimeMillis();
        if (now - statsCachedAt > ServerConfig.ADMIN_STATS_CACHE_MS) {
            synchronized (STATS_LOCK) {
                // Kiểm tra lại trong khóa: chỉ một luồng tính lại, các luồng khác dùng kết quả mới
                if (System.currentTimeMillis() - statsCachedAt > ServerConfig.ADMIN_STATS_CACHE_MS) {
                    cachedTotalMails = MailStorageEngine.getTotalMailCount();
                    cachedTotalBytes = MailStorageEngine.getTotalStorageSizeBytes();
                    statsCachedAt = System.currentTimeMillis();
                }
            }
        }
        long totalMails = cachedTotalMails;
        long totalBytes = cachedTotalBytes;
        String formattedSize = formatBytes(totalBytes);

        // quotaMb luôn lấy giá trị hiện tại (không cache) vì admin có thể vừa đổi
        String json = String.format(
            "{\"success\": true, \"totalMails\": %d, \"totalSizeBytes\": %d, \"formattedSize\": \"%s\", \"quotaMb\": %d}",
            totalMails, totalBytes, formattedSize, ServerConfig.MAX_MAILBOX_QUOTA_MB
        );
        sendResponse(exchange, 200, json);
    }

    private static void invalidateStatsCache() {
        statsCachedAt = 0;
    }

    private void handleSaveQuota(HttpExchange exchange, String quotaStr) throws IOException {
        if (quotaStr == null || quotaStr.trim().isEmpty()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Vui lòng nhập giá trị Quota hợp lệ!\"}");
            return;
        }

        try {
            long newQuota = Long.parseLong(quotaStr.trim());
            if (newQuota < 1 || newQuota > 10240) {
                sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Quota phải nằm trong khoảng từ 1 MB đến 10240 MB!\"}");
                return;
            }

            ServerConfig.saveQuotaConfig(newQuota);
            appendLog("Cập nhật Quota mặc định cho Hòm thư: " + newQuota + " MB");
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã cập nhật cấu hình Quota thành công: " + newQuota + " MB/hòm thư!\", \"quotaMb\": " + newQuota + "}");
        } catch (NumberFormatException e) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Giá trị Quota không đúng định dạng số!\"}");
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void handleGetLogs(HttpExchange exchange) throws IOException {
        int n = ServerConfig.ADMIN_LOGS_DEFAULT_LINES;
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                int eq = param.indexOf('=');
                if (eq <= 0) continue;
                String k = param.substring(0, eq);
                if ("lines".equalsIgnoreCase(k) || "limit".equalsIgnoreCase(k)) {
                    try { n = Integer.parseInt(param.substring(eq + 1).trim()); } catch (NumberFormatException ignored) {}
                }
            }
        }
        if (n < 1) n = ServerConfig.ADMIN_LOGS_DEFAULT_LINES;
        if (n > ServerConfig.ADMIN_LOGS_MAX_LINES) n = ServerConfig.ADMIN_LOGS_MAX_LINES;

        List<String> logs = tailLines(new File(SYSTEM_LOG_PATH), n);

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < logs.size(); i++) {
            sb.append("\"").append(escapeJson(logs.get(i))).append("\"");
            if (i < logs.size() - 1) sb.append(",");
        }
        sb.append("]");

        sendResponse(exchange, 200, "{\"success\": true, \"logs\": " + sb.toString() + "}");
    }

    /** Đọc N dòng cuối của file từ cuối lên (không nạp cả file vào RAM). Giữ nguyên thứ tự cũ -> mới. */
    static List<String> tailLines(File file, int n) {
        List<String> result = new ArrayList<>();
        if (!file.exists() || n <= 0) return result;

        // Trần byte đọc: tránh dòng log dài bất thường làm phình bộ nhớ
        final long maxBytes = Math.max(64 * 1024L, (long) n * 1024L);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long len = raf.length();
            long pos = len;
            long stopAt = Math.max(0, len - maxBytes);
            Deque<byte[]> chunks = new ArrayDeque<>();
            int newlines = 0;
            byte[] buf = new byte[8192];

            while (pos > stopAt && newlines <= n) { // cần n+1 dấu xuống dòng để chắc chắn đủ n dòng nguyên
                int toRead = (int) Math.min(buf.length, pos - stopAt);
                pos -= toRead;
                raf.seek(pos);
                raf.readFully(buf, 0, toRead);
                byte[] chunk = new byte[toRead];
                System.arraycopy(buf, 0, chunk, 0, toRead);
                for (byte b : chunk) if (b == '\n') newlines++;
                chunks.addFirst(chunk);
            }

            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            for (byte[] c : chunks) all.write(c, 0, c.length);
            String text = all.toString(StandardCharsets.UTF_8);
            String[] lines = text.split("\r?\n", -1);

            int first = 0;
            // Nếu chưa đọc tới đầu file, dòng đầu có thể bị cắt dở -> bỏ
            if (pos > 0 && lines.length > 0) first = 1;
            int last = lines.length;
            if (last > first && lines[last - 1].isEmpty()) last--; // phần tử rỗng sau '\n' cuối file

            int from = Math.max(first, last - n);
            for (int i = from; i < last; i++) result.add(lines[i]);
        } catch (IOException ignored) {}
        return result;
    }

    private void handleToggleLockUser(HttpExchange exchange, String targetUser, String action) throws IOException {
        if (targetUser == null || targetUser.isEmpty() || action == null) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu thông tin người dùng hoặc hành động!\"}");
            return;
        }

        if ("admin@pbl4.com".equalsIgnoreCase(targetUser)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Không thể khóa tài khoản Admin mặc định!\"}");
            return;
        }

        if (!"lock".equalsIgnoreCase(action) && !"unlock".equalsIgnoreCase(action)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Hành động phải là lock hoặc unlock!\"}");
            return;
        }
        String newStatus = "lock".equalsIgnoreCase(action) ? "LOCKED" : "ACTIVE";
        
        boolean updated = UserStore.setStatus(targetUser, newStatus);

        if (updated) {
            if ("LOCKED".equals(newStatus)) {
                SessionManager.invalidateUser(targetUser);
            }
            String actName = "LOCKED".equals(newStatus) ? "Khóa tài khoản" : "Mở khóa tài khoản";
            appendLog(actName + ": " + targetUser);
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã " + actName.toLowerCase() + " " + targetUser + " thành công!\"}");
        } else {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy tài khoản!\"}");
        }
    }

    private void handleDeleteUser(HttpExchange exchange, String targetUser) throws IOException {
        if (targetUser == null || targetUser.isEmpty()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu tài khoản cần xóa!\"}");
            return;
        }

        if ("admin@pbl4.com".equalsIgnoreCase(targetUser)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Không thể xóa tài khoản Admin mặc định!\"}");
            return;
        }

        if (!targetUser.matches("[A-Za-z0-9._%+@-]+")) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Tên tài khoản không hợp lệ!\"}");
            return;
        }

        boolean removed = UserStore.deleteUser(targetUser);

        if (removed) {
            SessionManager.invalidateUser(targetUser); // đóng cả SSE của user
            try {
                // Xóa inbox + _sent an toàn dưới khóa của MailStorageEngine
                MailStorageEngine.deleteMailbox(targetUser);
            } catch (Exception e) {
                appendLog("Lỗi khi xóa hòm thư của " + targetUser + ": " + sanitizeLogMessage(e.getMessage()));
            }
            invalidateStatsCache();

            appendLog("Xóa người dùng: " + targetUser);
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã xóa vĩnh viễn tài khoản " + targetUser + "\"}");
        } else {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy tài khoản trong hệ thống!\"}");
        }
    }

    private void handleBroadcast(HttpExchange exchange, String subject, String content) throws IOException {
        if (subject == null || content == null || subject.trim().isEmpty() || content.trim().isEmpty()) {
            sendResponse(exchange, 400,
                    "{\"success\": false, \"message\": \"Tiêu đề và nội dung thông báo không được để trống!\"}");
            return;
        }
        if (subject.length() > 250 || content.length() > 100000) {
            sendResponse(exchange, 400,
                    "{\"success\": false, \"message\": \"Tiêu đề hoặc nội dung quá dài!\"}");
            return;
        }

        final String safeSubject = subject.replace("\r", " ").replace("\n", " ").trim();
        final String finalContent = content;
        final List<String> recipients = new ArrayList<>();
        for (User user : UserStore.getAllUsers()) {
            if (user.getUsername() == null || user.getUsername().trim().isEmpty()
                    || user.getUsername().equalsIgnoreCase("admin@pbl4.com")) {
                continue;
            }
            recipients.add(user.getUsername().trim());
        }

        if (recipients.isEmpty()) {
            sendResponse(exchange, 200,
                    "{\"success\": true, \"sent\": 0, \"failed\": 0, \"total\": 0, "
                    + "\"message\": \"Không có tài khoản người dùng nào để gửi thông báo.\"}");
            return;
        }

        try {
            // Chạy nền: không giữ luồng HTTP khi có hàng nghìn người nhận
            BROADCAST_EXECUTOR.execute(() -> runBroadcast(safeSubject, finalContent, recipients));
        } catch (RejectedExecutionException e) {
            sendResponse(exchange, 429,
                    "{\"success\": false, \"message\": \"Đang có nhiều thông báo chờ xử lý, vui lòng thử lại sau!\"}");
            return;
        }

        int total = recipients.size();
        appendLog("Broadcast: đã nhận yêu cầu gửi tới " + total + " tài khoản. Tiêu đề: " + safeSubject);
        sendResponse(exchange, 202,
                "{\"success\": true, \"accepted\": true, \"total\": " + total
                + ", \"message\": \"Đã nhận yêu cầu, đang gửi thông báo tới " + total
                + " tài khoản (kết quả được ghi vào nhật ký hệ thống).\"}");
    }

    // Thực thi broadcast ở luồng nền, ghi kết quả vào system.log khi xong
    private static void runBroadcast(String safeSubject, String content, List<String> recipients) {
        int sent = 0;
        int failed = 0;
        String nowStr = java.time.ZonedDateTime.now().toString();

        for (String recipient : recipients) {
            try {
                String rawEmail = "From: admin@pbl4.com\n"
                        + "To: " + recipient + "\n"
                        + "Subject: " + safeSubject + "\n"
                        + "Date: " + nowStr + "\n"
                        + "\n"
                        + content;

                if (MailStorageEngine.saveEmail(recipient, rawEmail)) {
                    sent++;
                } else {
                    failed++;
                    appendLog("Broadcast thất bại khi lưu thư cho " + recipient);
                }
            } catch (Exception e) {
                failed++;
                appendLog("Broadcast lỗi với tài khoản " + recipient + ": " + sanitizeLogMessage(e.getMessage()));
            }
        }

        try {
            String adminRecord = "From: admin@pbl4.com\nTo: ALL_USERS\nSubject: " + safeSubject + "\nDate: " + nowStr + "\n\n" + content;
            MailStorageEngine.saveEmail("admin@pbl4.com_sent", adminRecord);
        } catch (Exception ignored) {}

        invalidateStatsCache();
        appendLog("Broadcast hoàn tất: gửi thành công " + sent + "/" + recipients.size()
                + " tài khoản; thất bại " + failed + ". Tiêu đề: " + safeSubject);
    }

    private static final Object LOG_LOCK = new Object();

    private static void appendLog(String message) {
        synchronized (LOG_LOCK) {
            try {
                File logFile = new File(SYSTEM_LOG_PATH);
                File parent = logFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();

                rotateLogIfNeeded(logFile);

                String cleanMsg = sanitizeLogMessage(message);
                String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                try (FileWriter fw = new FileWriter(logFile, StandardCharsets.UTF_8, true)) {
                    fw.write("[LOG " + timeStr + "] " + cleanMsg + "\n");
                }
            } catch (IOException ignored) {}
        }
    }

    // Xoay vòng: system.log -> system.log.1 -> .2 -> ... (giữ ADMIN_LOG_KEEP_FILES file cũ, xóa file cũ nhất)
    private static void rotateLogIfNeeded(File logFile) {
        if (!logFile.exists() || logFile.length() < ServerConfig.ADMIN_LOG_MAX_BYTES) return;
        try {
            int keep = ServerConfig.ADMIN_LOG_KEEP_FILES;
            Files.deleteIfExists(new File(logFile.getPath() + "." + keep).toPath());
            for (int i = keep - 1; i >= 1; i--) {
                File src = new File(logFile.getPath() + "." + i);
                if (src.exists()) {
                    Files.move(src.toPath(), new File(logFile.getPath() + "." + (i + 1)).toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.move(logFile.toPath(), new File(logFile.getPath() + ".1").toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Không xoay được thì vẫn tiếp tục ghi vào file hiện tại
        }
    }

    private static String sanitizeLogMessage(String raw) {
        if (raw == null) return "";
        return raw.replace("\r", " ").replace("\n", " ").trim();
    }

    private String escapeJson(String raw) {
        if (raw == null) return "";
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\': escaped.append("\\\\"); break;
                case '"': escaped.append("\\\""); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (c < 0x20) escaped.append(String.format("\\u%04x", (int)c));
                    else escaped.append(c);
            }
        }
        return escaped.toString();
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
