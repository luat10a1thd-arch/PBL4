package com.pbl4.mailserver.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public class ServerConfig {

    public static final int HTTP_PORT = 8080;
    public static final int SMTP_PORT = 2525;
    public static final int POP3_PORT = 1110;

    public static final int THREAD_POOL_SIZE = 20;
    public static final int SOCKET_TIMEOUT_MS = 30000;
    public static final long MAX_MESSAGE_BYTES = 25 * 1024 * 1024L;

    public static final String BASE_DATA_DIR = "data/mailboxes/";
    public static final String KEYSTORE_PATH = "data/keystore.jks";

    private static final String CONFIG_FILE_PATH = "data/config.properties";
    public static volatile long MAX_MAILBOX_QUOTA_MB = loadQuotaConfig();

    public static final String KEYSTORE_PASS = System.getenv().getOrDefault("PBL4_KEYSTORE_PASS", "pbl4secretpass");
    public static final String SECRET_KEY = getSecretKeyEnv();
    public static final String ADMIN_DEFAULT_PASS = System.getenv().getOrDefault("PBL4_ADMIN_PASSWORD", "@Luat0105");

    public static final boolean OUTBOUND_ENABLED = Boolean.parseBoolean(System.getenv().getOrDefault("PBL4_OUTBOUND_ENABLED", "false"));
    public static final String OUTBOUND_SMTP_HOST = System.getenv().getOrDefault("PBL4_OUTBOUND_SMTP_HOST", "");
    public static final int OUTBOUND_SMTP_PORT = Integer.parseInt(System.getenv().getOrDefault("PBL4_OUTBOUND_SMTP_PORT", "587"));
    public static final String OUTBOUND_SMTP_USER = System.getenv().getOrDefault("PBL4_OUTBOUND_SMTP_USER", "");
    public static final String OUTBOUND_SMTP_PASS = System.getenv().getOrDefault("PBL4_OUTBOUND_SMTP_PASS", "");
    public static final String MAIL_DOMAIN = System.getenv().getOrDefault("PBL4_MAIL_DOMAIN", "pbl4.com");
    public static final boolean OUTBOUND_TRUST_ALL = Boolean.parseBoolean(System.getenv().getOrDefault("PBL4_OUTBOUND_TRUST_ALL", "false"));

    public static final int HTTP_WORKER_THREADS = getHttpThreadsConfig();

    private static int getHttpThreadsConfig() {
        String env = System.getenv("PBL4_HTTP_THREADS");
        if (env != null) {
            try {
                int val = Integer.parseInt(env.trim());
                if (val >= 1 && val <= 1024) return val;
            } catch (NumberFormatException ignored) {}
        }
        return Math.max(16, Runtime.getRuntime().availableProcessors() * 4);
    }

    private static String getSecretKeyEnv() {
        String key = System.getenv("PBL4_SECRET_KEY");
        if (key == null || key.trim().isEmpty()) {
            System.err.println("[WARNING] PBL4_SECRET_KEY chưa được thiết lập. Dùng khóa mặc định phát triển!");
            return "dev-only-fallback-key-DO-NOT-USE-IN-PRODUCTION";
        }
        return key.trim();
    }

    private static synchronized long loadQuotaConfig() {
        File file = new File(CONFIG_FILE_PATH);
        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                Properties props = new Properties();
                props.load(fis);
                String val = props.getProperty("MAX_MAILBOX_QUOTA_MB");
                if (val != null) {
                    long q = Long.parseLong(val.trim());
                    if (q >= 1 && q <= 10240) return q;
                }
            } catch (Exception ignored) {}
        }
        return 100;
    }

    public static synchronized void saveQuotaConfig(long newQuotaMb) {
        MAX_MAILBOX_QUOTA_MB = newQuotaMb;
        File file = new File(CONFIG_FILE_PATH);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        Properties props = new Properties();
        props.setProperty("MAX_MAILBOX_QUOTA_MB", String.valueOf(newQuotaMb));
        try (FileOutputStream fos = new FileOutputStream(file)) {
            props.store(fos, "PBL4 Server Configurations");
        } catch (IOException ignored) {}
    }

    // ---- PHẦN 2 ----
    // Cấu hình SMTP/POP3 cho tải lớn (>1000 người dùng). Tất cả đọc từ biến môi trường PBL4_*.

    private static int envInt(String name, int def, int min, int max) {
        String env = System.getenv(name);
        if (env != null) {
            try {
                int val = Integer.parseInt(env.trim());
                if (val >= min && val <= max) return val;
            } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private static boolean envBool(String name, boolean def) {
        String env = System.getenv(name);
        if (env == null || env.trim().isEmpty()) return def;
        return Boolean.parseBoolean(env.trim());
    }

    // Hàng đợi kết nối chờ accept của hệ điều hành
    public static final int SOCKET_BACKLOG = envInt("PBL4_SOCKET_BACKLOG", 500, 50, 65535);

    // Pool SMTP: số worker, độ dài hàng đợi chờ, số kết nối đồng thời tối đa mỗi IP (0 = không giới hạn)
    public static final int SMTP_POOL_SIZE = envInt("PBL4_SMTP_POOL_SIZE", 150, 1, 5000);
    public static final int SMTP_QUEUE_SIZE = envInt("PBL4_SMTP_QUEUE_SIZE", 50, 1, 10000);
    public static final int SMTP_MAX_CONN_PER_IP = envInt("PBL4_SMTP_MAX_CONN_PER_IP", 30, 0, 100000);

    // Pool POP3 (cùng ý nghĩa như SMTP)
    public static final int POP3_POOL_SIZE = envInt("PBL4_POP3_POOL_SIZE", 150, 1, 5000);
    public static final int POP3_QUEUE_SIZE = envInt("PBL4_POP3_QUEUE_SIZE", 50, 1, 10000);
    public static final int POP3_MAX_CONN_PER_IP = envInt("PBL4_POP3_MAX_CONN_PER_IP", 30, 0, 100000);

    // Timeout đọc: trước khi xác thực (ngắn), sau khi xác thực; và tổng thời gian tối đa của một phiên
    public static final int PREAUTH_TIMEOUT_MS = envInt("PBL4_PREAUTH_TIMEOUT_MS", 15000, 1000, 600000);
    public static final int POSTAUTH_TIMEOUT_MS = envInt("PBL4_POSTAUTH_TIMEOUT_MS", 30000, 1000, 3600000);
    public static final int SESSION_MAX_MS = envInt("PBL4_SESSION_MAX_MS", 1800000, 10000, 86400000);

    // Độ dài dòng tối đa (byte, không tính CRLF): dòng lệnh SMTP/POP3 và dòng nội dung trong DATA
    public static final int MAX_COMMAND_LINE_BYTES = envInt("PBL4_MAX_COMMAND_LINE_BYTES", 1024, 128, 65536);
    public static final int SMTP_MAX_DATA_LINE_BYTES = envInt("PBL4_SMTP_MAX_DATA_LINE_BYTES", 1000, 100, 1048576);

    // Số người nhận tối đa mỗi thư SMTP
    public static final int SMTP_MAX_RECIPIENTS = envInt("PBL4_SMTP_MAX_RECIPIENTS", 100, 1, 100000);

    // Bắt buộc STARTTLS trước AUTH (tắt cho dev/test bằng PBL4_SMTP_REQUIRE_TLS=false)
    public static final boolean SMTP_REQUIRE_TLS = envBool("PBL4_SMTP_REQUIRE_TLS", true);

    // ---- PHẦN 1 ----
    // Số phần tử tối đa của các LRU cache trong MailStorageEngine (tóm tắt thư / kích thước thư cho POP3)
    public static final int MAIL_SUMMARY_CACHE_MAX_ENTRIES = 20000;
    public static final int MAIL_META_CACHE_MAX_ENTRIES = 100000;
    // ---- HẾT PHẦN 1 ----

    // ---- PHẦN 3 ----
    // Giới hạn kích thước body HTTP (byte)
    public static final int MAX_BODY_AUTH_BYTES = 4 * 1024;
    public static final int MAX_BODY_ADMIN_BYTES = 200 * 1024;
    // Thư gửi: MAX_MESSAGE_BYTES x 1.5 (Base64 + URL-encode) + 64KB dư địa cho tham số to/subject
    public static final long MAX_BODY_SEND_BYTES = MAX_MESSAGE_BYTES * 3 / 2 + 64 * 1024;
    // Timeout đọc body: không có byte mới trong IDLE ms, hoặc tổng thời gian vượt TOTAL ms thì ngắt kết nối
    public static final long BODY_READ_IDLE_TIMEOUT_MS = 10_000;
    public static final long BODY_READ_TOTAL_TIMEOUT_SMALL_MS = 15_000;
    // JDK HttpServer ("sun.net.httpserver.maxReqTime") cắt mọi request nhận quá lâu (kể cả upload chậm hợp lệ);
    // đặt 60s và để timeout của /send thấp hơn một chút để server trả 408 trước khi JDK đóng im lặng.
    public static final int HTTP_MAX_REQ_TIME_SECONDS = 60;
    public static final long BODY_READ_TOTAL_TIMEOUT_SEND_MS = 55_000;

    // SSE
    public static final int SSE_MAX_PER_USER = 5;
    public static final long SSE_HEARTBEAT_SECONDS = 20;
    public static final int SSE_NOTIFY_THREADS = 2;
    public static final int SSE_NOTIFY_QUEUE_SIZE = 10_000;
    public static final long SSE_TICKET_TTL_MS = 30_000;

    // Phiên đăng nhập
    public static final int SESSION_MAX_PER_USER = 10;

    // LoginThrottle
    public static final int THROTTLE_MAX_ATTEMPTS_PER_PAIR = 5;     // theo cặp IP+user
    public static final int THROTTLE_MAX_ATTEMPTS_PER_IP = 30;      // tổng theo IP
    public static final int THROTTLE_MAX_REGISTER_PER_IP = 20;      // số lần đăng ký / IP / cửa sổ
    public static final long THROTTLE_WINDOW_MS = 15 * 60 * 1000L;  // cửa sổ đếm / thời gian khóa

    // Admin
    public static final long ADMIN_STATS_CACHE_MS = 45_000;
    public static final int ADMIN_LOGS_DEFAULT_LINES = 500;
    public static final int ADMIN_LOGS_MAX_LINES = 2000;
    public static final long ADMIN_LOG_MAX_BYTES = 5 * 1024 * 1024L; // xoay vòng khi vượt
    public static final int ADMIN_LOG_KEEP_FILES = 3;
    // ---- HẾT PHẦN 3 ----
}