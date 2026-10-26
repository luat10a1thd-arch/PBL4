package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.config.ServerConfig;
import com.sun.net.httpserver.HttpExchange;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Quản lý các kết nối SSE (realtime).
 * - notifyUser KHÔNG chặn: việc ghi đẩy vào executor nhỏ có hàng đợi giới hạn (đầy thì bỏ thông báo).
 * - Mỗi kết nối chỉ đăng ký MỘT key (username đầy đủ, chữ thường); tối đa SSE_MAX_PER_USER kết nối/user.
 * - Heartbeat định kỳ để phát hiện và dọn client chết; watchdog đóng client ghi quá lâu (client treo).
 */
public class SseManager {

    private static final long WRITE_TIMEOUT_MS = 5_000;
    // MailStorageEngine hiện gọi notifyUser 2 lần cùng một nội dung (clean user == email đầy đủ với hộp thư đến);
    // cùng payload tới cùng client trong khoảng này được coi là trùng và bỏ qua.
    private static final long DEDUPE_WINDOW_MS = 5_000;
    private static final byte[] HEARTBEAT = ": ping\n\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EVICTED = "event: evicted\ndata: too_many_connections\n\n".getBytes(StandardCharsets.UTF_8);

    static final class SseClient {
        final String username;      // key đã chuẩn hóa
        final String token;         // token phiên đã xác thực kết nối này
        final OutputStream os;
        final Runnable closer;      // đóng kết nối thật (exchange.close())
        final long createdAt = System.currentTimeMillis();
        volatile boolean closed;
        final AtomicBoolean closeOnce = new AtomicBoolean();
        final Object writeLock = new Object(); // tuần tự hóa ghi theo từng client
        volatile long writeStartedAt;   // != 0 khi đang ghi (watchdog dùng)
        volatile long lastWriteAt = createdAt;
        String lastEventJson;      // chống gửi trùng cùng một thông báo (bảo vệ bởi writeLock)
        long lastEventAt;

        SseClient(String username, String token, OutputStream os, Runnable closer) {
            this.username = username;
            this.token = token;
            this.os = os;
            this.closer = closer;
        }
    }

    private static final class Ticket {
        final String username;
        final String token;
        final long expiresAt;
        Ticket(String username, String token, long expiresAt) {
            this.username = username;
            this.token = token;
            this.expiresAt = expiresAt;
        }
    }

    /** Kết quả đổi ticket. */
    public static final class TicketInfo {
        public final String username;
        public final String token;
        TicketInfo(String username, String token) {
            this.username = username;
            this.token = token;
        }
    }

    private static final int MAX_TICKETS = 50_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ConcurrentHashMap<String, List<SseClient>> userClients = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Ticket> tickets = new ConcurrentHashMap<>();
    private static final AtomicLong droppedNotifications = new AtomicLong();

    // Executor ghi dữ liệu: hàng đợi giới hạn, daemon
    private static final ThreadPoolExecutor writer = new ThreadPoolExecutor(
            ServerConfig.SSE_NOTIFY_THREADS, ServerConfig.SSE_NOTIFY_THREADS,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(ServerConfig.SSE_NOTIFY_QUEUE_SIZE),
            r -> {
                Thread t = new Thread(r, "sse-writer");
                t.setDaemon(true);
                return t;
            });

    // Scheduler: heartbeat, watchdog, dọn ticket. Chỉ lên lịch/đóng socket, không ghi dữ liệu.
    private static final ScheduledExecutorService scheduler;

    static {
        ScheduledThreadPoolExecutor s = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "sse-scheduler");
            t.setDaemon(true);
            return t;
        });
        s.setRemoveOnCancelPolicy(true);
        scheduler = s;
        scheduler.scheduleAtFixedRate(SseManager::heartbeatAll,
                ServerConfig.SSE_HEARTBEAT_SECONDS, ServerConfig.SSE_HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(SseManager::watchdog, 2, 2, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(SseManager::cleanupTickets, 30, 30, TimeUnit.SECONDS);
    }

    private static String normalize(String username) {
        return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }

    // ===================== Ticket một lần dùng (thay token trên URL) =====================

    /** Cấp ticket sống ~30s, dùng được đúng 1 lần. Trả null nếu hệ thống đang giữ quá nhiều ticket. */
    public static String issueTicket(String username, String token) {
        if (username == null || token == null || tickets.size() >= MAX_TICKETS) return null;
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        tickets.put(id, new Ticket(normalize(username), token, System.currentTimeMillis() + ServerConfig.SSE_TICKET_TTL_MS));
        return id;
    }

    /** Đổi ticket lấy thông tin phiên; ticket bị xóa ngay (một lần dùng). */
    public static TicketInfo consumeTicket(String ticketId) {
        if (ticketId == null) return null;
        Ticket t = tickets.remove(ticketId);
        if (t == null || t.expiresAt < System.currentTimeMillis()) return null;
        return new TicketInfo(t.username, t.token);
    }

    private static void cleanupTickets() {
        long now = System.currentTimeMillis();
        tickets.entrySet().removeIf(e -> e.getValue().expiresAt < now);
    }

    // ===================== Đăng ký / hủy =====================

    /** Đăng ký kết nối SSE (chỉ MỘT key = username đầy đủ). Đóng kết nối cũ nhất nếu vượt giới hạn/user. */
    public static void register(String username, String token, HttpExchange exchange, OutputStream os) {
        register(username, token, os, () -> {
            try { exchange.close(); } catch (Exception ignored) {}
        });
    }

    static SseClient register(String username, String token, OutputStream os, Runnable closer) {
        String key = normalize(username);
        if (key == null || key.isEmpty()) return null;
        SseClient client = new SseClient(key, token, os, closer);
        List<SseClient> evicted = new ArrayList<>();

        userClients.compute(key, (k, list) -> {
            List<SseClient> l = list == null ? new ArrayList<>() : new ArrayList<>(list);
            l.removeIf(c -> c.closed);
            l.add(client);
            while (l.size() > ServerConfig.SSE_MAX_PER_USER) {
                evicted.add(l.remove(0)); // list theo thứ tự đăng ký => phần tử đầu là cũ nhất
            }
            return l;
        });

        for (SseClient old : evicted) closeAsync(old, EVICTED);
        return client;
    }

    static void unregister(SseClient client) {
        if (client == null) return;
        userClients.computeIfPresent(client.username, (k, list) -> {
            List<SseClient> l = new ArrayList<>(list);
            l.remove(client);
            return l.isEmpty() ? null : l;
        });
    }

    /** Đóng một client: gỡ khỏi danh sách rồi đóng kết nối thật (idempotent). */
    static void close(SseClient client) {
        if (client == null) return;
        // CAS thay vì synchronized: watchdog phải đóng được client dù luồng ghi đang kẹt trong writeLock
        if (!client.closeOnce.compareAndSet(false, true)) return;
        client.closed = true;
        unregister(client);
        try { client.closer.run(); } catch (Exception ignored) {}
    }

    // Đóng kèm gửi một sự kiện cuối, làm trên writer để không chặn luồng gọi
    private static void closeAsync(SseClient client, byte[] lastEvent) {
        try {
            writer.execute(() -> {
                if (lastEvent != null) write(client, lastEvent);
                close(client);
            });
        } catch (RejectedExecutionException e) {
            close(client);
        }
    }

    /** Đóng mọi SSE gắn với đúng phiên (token) đó - gọi khi logout/hết hạn. */
    public static void closeByToken(String username, String token) {
        String key = normalize(username);
        if (key == null || token == null) return;
        List<SseClient> list = userClients.get(key);
        if (list == null) return;
        for (SseClient c : list) {
            if (token.equals(c.token)) close(c);
        }
    }

    /** Đóng mọi SSE của user - gọi khi bị khóa/xóa. */
    public static void closeUser(String username) {
        String key = normalize(username);
        if (key == null) return;
        List<SseClient> list = userClients.get(key);
        if (list == null) return;
        for (SseClient c : list) close(c);
    }

    // ===================== Gửi dữ liệu =====================

    /** Bắn sự kiện realtime tới đúng hòm thư nhận mail. KHÔNG chặn: chỉ đẩy việc ghi vào hàng đợi. */
    public static void notifyUser(String username, String mailSummaryJson) {
        String key = normalize(username);
        if (key == null) return;
        List<SseClient> clients = userClients.get(key);
        if (clients == null || clients.isEmpty()) return;

        byte[] bytes = ("event: new_mail\ndata: " + mailSummaryJson + "\n\n").getBytes(StandardCharsets.UTF_8);
        try {
            writer.execute(() -> {
                List<SseClient> current = userClients.get(key);
                if (current == null) return;
                for (SseClient c : current) writeEvent(c, mailSummaryJson, bytes);
            });
        } catch (RejectedExecutionException e) {
            droppedNotifications.incrementAndGet(); // hàng đợi đầy: bỏ thông báo (client vẫn có thể tải lại danh sách)
        }
    }

    // Ghi sự kiện new_mail, bỏ qua nếu vừa gửi đúng payload này cho client (chống trùng)
    private static void writeEvent(SseClient c, String json, byte[] bytes) {
        synchronized (c.writeLock) {
            long now = System.currentTimeMillis();
            if (json != null && json.equals(c.lastEventJson) && now - c.lastEventAt < DEDUPE_WINDOW_MS) return;
            c.lastEventJson = json;
            c.lastEventAt = now;
            write(c, bytes);
        }
    }

    // Ghi tuần tự theo từng client; lỗi => đóng client
    private static void write(SseClient c, byte[] data) {
        if (c.closed) return;
        synchronized (c.writeLock) {
            if (c.closed) return;
            c.writeStartedAt = System.currentTimeMillis();
            try {
                c.os.write(data);
                c.os.flush();
                c.lastWriteAt = System.currentTimeMillis();
            } catch (Exception e) {
                c.writeStartedAt = 0;
                close(c);
                return;
            }
            c.writeStartedAt = 0;
        }
    }

    private static void heartbeatAll() {
        heartbeatAll(false);
    }

    private static void heartbeatAll(boolean force) {
        long now = System.currentTimeMillis();
        long minGap = ServerConfig.SSE_HEARTBEAT_SECONDS * 1000 / 2;
        for (Map.Entry<String, List<SseClient>> e : userClients.entrySet()) {
            for (SseClient c : e.getValue()) {
                if (c.closed) { unregister(c); continue; }
                if (!force && now - c.lastWriteAt < minGap) continue; // vừa có dữ liệu, chưa cần ping
                try {
                    writer.execute(() -> write(c, HEARTBEAT));
                } catch (RejectedExecutionException ignored) {
                    // hàng đợi đầy: bỏ qua nhịp này
                }
            }
        }
    }

    // Client treo (không đọc dữ liệu) làm lệnh ghi bị kẹt quá WRITE_TIMEOUT_MS => đóng socket để giải phóng luồng
    private static void watchdog() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, List<SseClient>> e : userClients.entrySet()) {
            for (SseClient c : e.getValue()) {
                long started = c.writeStartedAt;
                if (started != 0 && now - started > WRITE_TIMEOUT_MS) {
                    close(c);
                }
            }
        }
    }

    // ===================== Phục vụ test =====================

    static int clientCount(String username) {
        List<SseClient> l = userClients.get(normalize(username));
        return l == null ? 0 : l.size();
    }

    static long droppedCount() {
        return droppedNotifications.get();
    }

    /** Chờ hàng đợi ghi xử lý xong (test). */
    static boolean awaitIdle(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (writer.getQueue().isEmpty() && writer.getActiveCount() == 0) return true;
            Thread.sleep(5);
        }
        return false;
    }

    static void heartbeatNowForTest() {
        heartbeatAll(true);
    }
}
