<<<<<<< HEAD
package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.User;
import com.pbl4.mailserver.core.UserStore;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AdminHandler implements HttpHandler {

    private static final String SYSTEM_LOG_PATH = "data/system.log";

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
            String body = readRequestBody(exchange);
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
        long totalMails = MailStorageEngine.getTotalMailCount();
        long totalBytes = MailStorageEngine.getTotalStorageSizeBytes();
        String formattedSize = formatBytes(totalBytes);

        String json = String.format(
            "{\"success\": true, \"totalMails\": %d, \"totalSizeBytes\": %d, \"formattedSize\": \"%s\", \"quotaMb\": %d}",
            totalMails, totalBytes, formattedSize, ServerConfig.MAX_MAILBOX_QUOTA_MB
        );
        sendResponse(exchange, 200, json);
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

            String exceedingUser = MailStorageEngine.findUserExceedingQuota(newQuota);
            if (exceedingUser != null) {
                String errorMsg = String.format("Không thể đặt Quota là %d MB vì tài khoản %s đã vượt quá dung lượng này!", newQuota, exceedingUser);
                sendResponse(exchange, 400, "{\"success\": false, \"message\": \"" + escapeJson(errorMsg) + "\"}");
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
        File logFile = new File(SYSTEM_LOG_PATH);
        List<String> logs = new ArrayList<>();
        
        if (logFile.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(logFile, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    logs.add(line);
                }
            } catch (IOException ignored) {}
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < logs.size(); i++) {
            sb.append("\"").append(escapeJson(logs.get(i))).append("\"");
            if (i < logs.size() - 1) sb.append(",");
        }
        sb.append("]");

        sendResponse(exchange, 200, "{\"success\": true, \"logs\": " + sb.toString() + "}");
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
            SessionManager.invalidateUser(targetUser);
            deleteUserMailbox("data/mailboxes/" + targetUser);
            deleteUserMailbox("data/mailboxes/" + targetUser + "_sent");

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

        String safeSubject = subject.replace("\r", " ").replace("\n", " ").trim();
        List<User> users = UserStore.getAllUsers();
        int sent = 0;
        int failed = 0;
        int eligible = 0;
        String nowStr = java.time.ZonedDateTime.now().toString();

        for (User user : users) {
            if (user.getUsername() == null || user.getUsername().trim().isEmpty()
                    || user.getUsername().equalsIgnoreCase("admin@pbl4.com")) {
                continue;
            }

            eligible++;
            String recipient = user.getUsername().trim();
            try {
                String rawEmail = "From: admin@pbl4.com\n"
                        + "To: " + recipient + "\n"
                        + "Subject: " + safeSubject + "\n"
                        + "Date: " + nowStr + "\n"
                        + "\n"
                        + content;

                boolean saved = MailStorageEngine.saveEmail(recipient, rawEmail);
                if (saved) {
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

        appendLog("Broadcast: gửi thành công " + sent + "/" + eligible
                + " tài khoản; thất bại " + failed + ". Tiêu đề: " + safeSubject);

        boolean success = sent > 0;
        int statusCode = success ? 200 : 500;
        String message = eligible == 0
                ? "Không có tài khoản người dùng nào để gửi thông báo."
                : "Đã gửi thành công " + sent + "/" + eligible
                    + " tài khoản; thất bại " + failed + " tài khoản.";

        sendResponse(exchange, statusCode,
                "{\"success\": " + success
                + ", \"sent\": " + sent
                + ", \"failed\": " + failed
                + ", \"total\": " + eligible
                + ", \"message\": \"" + escapeJson(message) + "\"}");
    }

    private synchronized void appendLog(String message) {
        try {
            File logFile = new File(SYSTEM_LOG_PATH);
            File parent = logFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            String cleanMsg = sanitizeLogMessage(message);
            String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            try (FileWriter fw = new FileWriter(logFile, StandardCharsets.UTF_8, true)) {
                fw.write("[LOG " + timeStr + "] " + cleanMsg + "\n");
            }
        } catch (IOException ignored) {}
    }

    private String sanitizeLogMessage(String raw) {
        if (raw == null) return "";
        return raw.replace("\r", " ").replace("\n", " ").trim();
    }

    private void deleteUserMailbox(String pathStr) {
        File dir = new File(pathStr);
        if (!dir.exists()) return;

        try {
            Files.walk(dir.toPath())
                 .sorted(Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(f -> {
                     if (!f.delete()) {
                         appendLog("Không thể xóa file/folder: " + f.getAbsolutePath());
                     }
                 });
        } catch (IOException e) {
            appendLog("Lỗi khi xóa thư mục " + pathStr + ": " + sanitizeLogMessage(e.getMessage()));
        }
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

    private String readRequestBody(HttpExchange exchange) throws IOException {
        InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
        BufferedReader br = new BufferedReader(isr);
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
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
=======
package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.auth.DnsRecordPrinter;
import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.OutboundQueue;
import com.pbl4.mailserver.core.User;
import com.pbl4.mailserver.core.UserStore;
import com.pbl4.mailserver.outbound.QueueEntry;
import com.pbl4.mailserver.ratelimit.AbuseGuard;
import com.pbl4.mailserver.core.LoginThrottle;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class AdminHandler implements HttpHandler {

    private static final String SYSTEM_LOG_PATH = "data/system.log";

    private static final ThreadPoolExecutor BROADCAST_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2),
            r -> {
                Thread t = new Thread(r, "admin-broadcast");
                t.setDaemon(true);
                return t;
            });

    private static final Object STATS_LOCK = new Object();
    private static volatile long statsCachedAt = 0;
    private static volatile long cachedTotalMails;
    private static volatile long cachedTotalBytes;
    private static volatile long cachedSentMails;
    private static volatile long cachedSpamMails;

    private static final Pattern QUEUE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final int QUEUE_MAX_ROWS = 1000;
    private static final String DNS_PLACEHOLDER_IP = "203.0.113.10";
    private static final Object DNS_LOCK = new Object();

    interface Gate {
        String validate(String token);

        boolean isAdmin(String user);
    }

    interface QueueAccess {
        List<QueueEntry> snapshot();

        boolean retryNow(String id);

        boolean delete(String id);
    }

    interface DnsRenderer {
        String render(String ip, String domain, String hostname, String selector, String keyDir);
    }

    static final class MailStats {
        final long inbox, sent, spam, totalBytes;

        MailStats(long inbox, long sent, long spam, long totalBytes) {
            this.inbox = inbox;
            this.sent = sent;
            this.spam = spam;
            this.totalBytes = totalBytes;
        }
    }

    interface StatsSource {
        MailStats read();
    }

    private static final Gate DEFAULT_GATE = new Gate() {
        @Override
        public String validate(String token) {
            return SessionManager.validate(token);
        }

        @Override
        public boolean isAdmin(String user) {
            User u = UserStore.getUser(user);
            return u != null && "ADMIN".equalsIgnoreCase(u.getRole());
        }
    };

    private static final QueueAccess DEFAULT_QUEUE = new QueueAccess() {
        @Override
        public List<QueueEntry> snapshot() {
            return OutboundQueue.snapshot();
        }

        @Override
        public boolean retryNow(String id) {
            return OutboundQueue.retryNow(id);
        }

        @Override
        public boolean delete(String id) {
            return OutboundQueue.delete(id);
        }
    };

    private static final DnsRenderer DEFAULT_DNS = (ip, domain, hostname, selector, keyDir) ->
            DnsRecordPrinter.render(ip, domain, hostname, selector, Paths.get(keyDir));

    private static final StatsSource DEFAULT_STATS = () -> new MailStats(
            MailStorageEngine.getTotalMailCount(),
            countEncFiles("_sent"),
            countEncFiles("_spam"),
            MailStorageEngine.getTotalStorageSizeBytes());

    private static volatile Gate gate = DEFAULT_GATE;
    private static volatile QueueAccess queueAccess = DEFAULT_QUEUE;
    private static volatile DnsRenderer dnsRenderer = DEFAULT_DNS;
    private static volatile StatsSource statsSource = DEFAULT_STATS;

    static void setHooksForTest(Gate g, QueueAccess q, DnsRenderer d, StatsSource s) {
        gate = g != null ? g : DEFAULT_GATE;
        queueAccess = q != null ? q : DEFAULT_QUEUE;
        dnsRenderer = d != null ? d : DEFAULT_DNS;
        statsSource = s != null ? s : DEFAULT_STATS;
        statsCachedAt = 0;
    }

    static void resetHooksForTest() {
        setHooksForTest(null, null, null, null);
    }

    private static long countEncFiles(String boxSuffix) {
        File base = new File(ServerConfig.BASE_DATA_DIR);
        File[] dirs = base.listFiles(f -> f.isDirectory() && f.getName().endsWith(boxSuffix));
        if (dirs == null) return 0;
        long count = 0;
        for (File d : dirs) {
            File[] enc = d.listFiles((x, name) -> name.endsWith(".enc"));
            if (enc != null) count += enc.length;
        }
        return count;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Chưa đăng nhập!\"}");
            return;
        }

        String token = authHeader.substring(7);
        String currentUser = gate.validate(token);

        if (currentUser == null) {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Phiên đăng nhập hết hạn hoặc không hợp lệ!\"}");
            return;
        }

        if (!gate.isAdmin(currentUser)) {
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

        if ("GET".equalsIgnoreCase(method) && path.endsWith("/queue")) {
            handleGetQueue(exchange);
            return;
        }

        if ("GET".equalsIgnoreCase(method) && path.endsWith("/dns")) {
            handleGetDns(exchange);
            return;
        }

        if ("GET".equalsIgnoreCase(method) && path.endsWith("/security")) {
            handleGetSecurity(exchange);
            return;
        }

        if ("DELETE".equalsIgnoreCase(method) && path.endsWith("/queue")) {
            handleDeleteQueueJob(exchange, queryParam(exchange, "id"));
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
            } else if (path.endsWith("/queue/retry")) {
                handleRetryQueueJob(exchange, getParamValue(body, "id"));
            } else if (path.endsWith("/queue/delete")) {
                handleDeleteQueueJob(exchange, getParamValue(body, "id"));
            } else if (path.endsWith("/security/ban")) {
                handleBanIp(exchange, getParamValue(body, "ip"), getParamValue(body, "minutes"));
            } else if (path.endsWith("/security/unban")) {
                handleUnbanIp(exchange, getParamValue(body, "ip"));
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

                if (System.currentTimeMillis() - statsCachedAt > ServerConfig.ADMIN_STATS_CACHE_MS) {
                    MailStats st = statsSource.read();
                    cachedTotalMails = st.inbox;
                    cachedTotalBytes = st.totalBytes;
                    cachedSentMails = st.sent;
                    cachedSpamMails = st.spam;
                    statsCachedAt = System.currentTimeMillis();
                }
            }
        }
        long totalMails = cachedTotalMails;
        long totalBytes = cachedTotalBytes;
        String formattedSize = formatBytes(totalBytes);

        long queueSize;
        try {
            queueSize = queueAccess.snapshot().size();
        } catch (RuntimeException e) {
            queueSize = -1;
        }

        String json = String.format(
            "{\"success\": true, \"totalMails\": %d, \"totalSizeBytes\": %d, \"formattedSize\": \"%s\", \"quotaMb\": %d"
            + ", \"inboxMails\": %d, \"sentMails\": %d, \"spamMails\": %d, \"queueSize\": %d}",
            totalMails, totalBytes, formattedSize, ServerConfig.MAX_MAILBOX_QUOTA_MB,
            totalMails, cachedSentMails, cachedSpamMails, queueSize
        );
        sendResponse(exchange, 200, json);
    }

    private static void invalidateStatsCache() {
        statsCachedAt = 0;
    }

    private void handleGetQueue(HttpExchange exchange) throws IOException {
        List<QueueEntry> entries;
        try {
            entries = new ArrayList<>(queueAccess.snapshot());
        } catch (RuntimeException e) {
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Không đọc được hàng đợi gửi thư!\"}");
            return;
        }

        entries.sort(Comparator.comparingLong(QueueEntry::nextAttemptEpochMs));

        int total = entries.size();
        boolean truncated = total > QUEUE_MAX_ROWS;
        int shown = Math.min(total, QUEUE_MAX_ROWS);

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < shown; i++) {
            QueueEntry q = entries.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"id\":\"").append(escapeJson(q.id()))
              .append("\",\"envelopeFrom\":\"").append(escapeJson(q.envelopeFrom()))
              .append("\",\"domain\":\"").append(escapeJson(q.domain()))
              .append("\",\"rcpts\":[");
            List<String> rcpts = q.rcpts();
            for (int j = 0; j < rcpts.size(); j++) {
                if (j > 0) sb.append(",");
                sb.append("\"").append(escapeJson(rcpts.get(j))).append("\"");
            }
            sb.append("],\"attempts\":").append(q.attempts())
              .append(",\"nextAttemptEpochMs\":").append(q.nextAttemptEpochMs())
              .append(",\"lastError\":\"").append(escapeJson(q.lastError())).append("\"}");
        }
        sb.append("]");

        sendResponse(exchange, 200, "{\"success\": true, \"total\": " + total
                + ", \"truncated\": " + truncated + ", \"queue\": " + sb + "}");
    }

    private static boolean isValidQueueId(String id) {
        return id != null && QUEUE_ID.matcher(id).matches() && !id.contains("..");
    }

    private void handleRetryQueueJob(HttpExchange exchange, String id) throws IOException {
        if (!isValidQueueId(id)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Mã job không hợp lệ!\"}");
            return;
        }
        boolean ok;
        try {
            ok = queueAccess.retryNow(id);
        } catch (RuntimeException e) {
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Không thể thử lại job này!\"}");
            return;
        }
        if (ok) {
            appendLog("Hàng đợi gửi: yêu cầu thử lại ngay job " + id);
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã xếp job vào lượt thử lại ngay!\"}");
        } else {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy job trong hàng đợi!\"}");
        }
    }

    private void handleDeleteQueueJob(HttpExchange exchange, String id) throws IOException {
        if (!isValidQueueId(id)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Mã job không hợp lệ!\"}");
            return;
        }
        boolean ok;
        try {
            ok = queueAccess.delete(id);
        } catch (RuntimeException e) {
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Không thể xóa job này!\"}");
            return;
        }
        if (ok) {
            appendLog("Hàng đợi gửi: đã xóa job " + id);
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã xóa job khỏi hàng đợi!\"}");
        } else {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy job trong hàng đợi!\"}");
        }
    }

    private void handleGetDns(HttpExchange exchange) throws IOException {
        String ip = queryParam(exchange, "ip");
        boolean ipAssumed = false;
        if (ip == null || ip.trim().isEmpty()) {
            ip = DNS_PLACEHOLDER_IP;
            ipAssumed = true;
        } else {
            ip = ip.trim();
            if (!isIpLiteral(ip)) {
                sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Địa chỉ IP không hợp lệ!\"}");
                return;
            }
        }

        String domain = ServerConfig.MAIL_DOMAIN;
        String hostname = ServerConfig.MAIL_HOSTNAME;
        String selector = ServerConfig.DKIM_SELECTOR;

        String text;
        boolean dkimAvailable = true;
        try {

            synchronized (DNS_LOCK) {
                text = dnsRenderer.render(ip, domain, hostname, selector, ServerConfig.DKIM_KEY_DIR);
            }
        } catch (RuntimeException e) {

            dkimAvailable = false;
            appendLog("DNS: không dựng được bản ghi DKIM: " + sanitizeLogMessage(e.getMessage()));
            text = fallbackDnsText(ip, domain, hostname, selector);
        }

        sendResponse(exchange, 200, "{\"success\": true, \"domain\": \"" + escapeJson(domain)
                + "\", \"hostname\": \"" + escapeJson(hostname)
                + "\", \"selector\": \"" + escapeJson(selector)
                + "\", \"ip\": \"" + escapeJson(ip)
                + "\", \"ipAssumed\": " + ipAssumed
                + ", \"dkimAvailable\": " + dkimAvailable
                + ", \"text\": \"" + escapeJson(text) + "\"}");
    }

    private static String fallbackDnsText(String ip, String domain, String hostname, String selector) {
        boolean v6 = ip.indexOf(':') >= 0;
        return "=== BẢN GHI DNS MẪU CHO " + domain + " ===\n\n"
                + "MX    " + domain + ".  IN  MX  10  " + hostname + ".\n"
                + (v6 ? "AAAA  " : "A     ") + hostname + ".  IN  " + (v6 ? "AAAA" : "A") + "  " + ip + "\n"
                + "SPF   " + domain + ".  IN  TXT  \"v=spf1 mx " + (v6 ? "ip6:" : "ip4:") + ip + " -all\"\n"
                + "DMARC _dmarc." + domain + ".  IN  TXT  \"v=DMARC1; p=none; adkim=r; aspf=r\"\n\n"
                + "DKIM  " + selector + "._domainkey." + domain
                + "  (chưa tạo được khóa DKIM: kiểm tra thư mục PBL4_DKIM_KEY_DIR và quyền ghi)\n";
    }

    private static boolean isIpLiteral(String s) {
        if (s.length() > 45) return false;
        if (s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            for (String part : s.split("\\.")) {
                if (Integer.parseInt(part) > 255) return false;
            }
            return true;
        }
        return s.indexOf(':') >= 0 && s.matches("[0-9A-Fa-f:.]+");
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String param : query.split("&")) {
            int eq = param.indexOf('=');
            if (eq <= 0) continue;
            if (name.equalsIgnoreCase(param.substring(0, eq))) {
                try {
                    return java.net.URLDecoder.decode(param.substring(eq + 1), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
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

    static List<String> tailLines(File file, int n) {
        List<String> result = new ArrayList<>();
        if (!file.exists() || n <= 0) return result;

        final long maxBytes = Math.max(64 * 1024L, (long) n * 1024L);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long len = raf.length();
            long pos = len;
            long stopAt = Math.max(0, len - maxBytes);
            Deque<byte[]> chunks = new ArrayDeque<>();
            int newlines = 0;
            byte[] buf = new byte[8192];

            while (pos > stopAt && newlines <= n) {
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

            if (pos > 0 && lines.length > 0) first = 1;
            int last = lines.length;
            if (last > first && lines[last - 1].isEmpty()) last--;

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

        if (ServerConfig.ADMIN_EMAIL.equalsIgnoreCase(targetUser)) {
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

        if (ServerConfig.ADMIN_EMAIL.equalsIgnoreCase(targetUser)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Không thể xóa tài khoản Admin mặc định!\"}");
            return;
        }

        if (!targetUser.matches("[A-Za-z0-9._%+@-]+")) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Tên tài khoản không hợp lệ!\"}");
            return;
        }

        boolean removed = UserStore.deleteUser(targetUser);

        if (removed) {
            SessionManager.invalidateUser(targetUser);
            try {

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
                    || user.getUsername().equalsIgnoreCase(ServerConfig.ADMIN_EMAIL)) {
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

    private static void runBroadcast(String safeSubject, String content, List<String> recipients) {
        int sent = 0;
        int failed = 0;
        String nowStr = java.time.ZonedDateTime.now().toString();

        for (String recipient : recipients) {
            try {
                String rawEmail = "From: " + ServerConfig.ADMIN_EMAIL + "\n"
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
            String adminRecord = "From: " + ServerConfig.ADMIN_EMAIL + "\nTo: ALL_USERS\nSubject: " + safeSubject + "\nDate: " + nowStr + "\n\n" + content;
            MailStorageEngine.saveEmail(ServerConfig.ADMIN_EMAIL + "_sent", adminRecord);
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

    private void handleGetSecurity(HttpExchange exchange) throws IOException {
        int tracked = AbuseGuard.defaultEngine().trackedIps();
        java.util.Map<String, Long> banned = AbuseGuard.defaultEngine().bannedIps();
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (java.util.Map.Entry<String, Long> e : banned.entrySet()) {
            if (i++ > 0) sb.append(",");
            sb.append("{\"ip\":\"").append(escapeJson(e.getKey()))
              .append("\",\"remainingSeconds\":").append(e.getValue()).append("}");
        }
        sb.append("]");
        sendResponse(exchange, 200, "{\"success\": true, \"trackedIps\": " + tracked + ", \"bannedIps\": " + sb + "}");
    }

    private void handleBanIp(HttpExchange exchange, String ip, String minutesStr) throws IOException {
        if (ip == null || !isIpLiteral(ip.trim())) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Địa chỉ IP không hợp lệ!\"}");
            return;
        }
        long minutes = 30;
        try {
            if (minutesStr != null && !minutesStr.trim().isEmpty()) {
                minutes = Math.max(1, Math.min(10080, Long.parseLong(minutesStr.trim())));
            }
        } catch (NumberFormatException ignored) {}
        String cleanIp = ip.trim();
        AbuseGuard.defaultEngine().ban(cleanIp, minutes * 60_000L);
        appendLog("Bảo mật: Admin đã chặn IP " + cleanIp + " trong " + minutes + " phút");
        sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã chặn IP " + escapeJson(cleanIp) + " trong " + minutes + " phút!\"}");
    }

    private void handleUnbanIp(HttpExchange exchange, String ip) throws IOException {
        if (ip == null || ip.trim().isEmpty()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu địa chỉ IP cần mở khóa!\"}");
            return;
        }
        String cleanIp = ip.trim();
        AbuseGuard.defaultEngine().unban(cleanIp);
        LoginThrottle.resetAttempts(cleanIp, null);
        appendLog("Bảo mật: Admin đã mở khóa IP " + cleanIp);
        sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã mở khóa cho IP " + escapeJson(cleanIp) + "!\"}");
    }
}
>>>>>>> stash
