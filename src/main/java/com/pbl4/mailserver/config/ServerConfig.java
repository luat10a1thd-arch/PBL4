// FILE: src/main/java/com/pbl4/mailserver/config/ServerConfig.java
package com.pbl4.mailserver.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

public class ServerConfig {

    public static final int HTTP_PORT = 8080;
    public static final int SMTP_PORT = envInt("PBL4_SMTP_PORT", 2525, 1, 65535);
    public static final int POP3_PORT = 1110;

    /** true khi PBL4_PRODUCTION=true. Đặt sớm vì nhiều cấu hình bên dưới lấy giá trị mặc định từ đây. */
    public static final boolean PRODUCTION = envBool("PBL4_PRODUCTION", false);

    public static final long MAX_MESSAGE_BYTES = 25 * 1024 * 1024L;

    public static final String BASE_DATA_DIR = "data/mailboxes/";
    public static final String KEYSTORE_PATH = "data/keystore.jks";

    private static final String CONFIG_FILE_PATH = "data/config.properties";
    public static volatile long MAX_MAILBOX_QUOTA_MB = loadQuotaConfig();

    public static final String KEYSTORE_PASS = System.getenv().getOrDefault("PBL4_KEYSTORE_PASS", "pbl4secretpass");
    public static final String SECRET_KEY = getSecretKeyEnv();

    // Mật khẩu admin: lấy từ biến môi trường; nếu thiếu thì sinh NGẪU NHIÊN (chỉ in ra console một lần khi tạo admin lần đầu,
    // xem consumeAdminInitialPassword). Không còn mật khẩu mặc định trong source.
    private static final String ADMIN_PASS_FROM_ENV = adminPassFromEnv();
    public static final String ADMIN_DEFAULT_PASS = ADMIN_PASS_FROM_ENV != null ? ADMIN_PASS_FROM_ENV : randomPassword();
    private static boolean adminPassAnnounced = false;

    public static final boolean OUTBOUND_ENABLED = Boolean.parseBoolean(System.getenv().getOrDefault("PBL4_OUTBOUND_ENABLED", "false"));
    public static final String OUTBOUND_SMTP_HOST = System.getenv().getOrDefault("PBL4_OUTBOUND_SMTP_HOST", "");
    public static final int OUTBOUND_SMTP_PORT = Integer.parseInt(System.getenv().getOrDefault("PBL4_OUTBOUND_SMTP_PORT", "587"));
    public static final String OUTBOUND_SMTP_USER = System.getenv().getOrDefault("PBL4_OUTBOUND_SMTP_USER", "");
    public static final String OUTBOUND_SMTP_PASS = System.getenv().getOrDefault("PBL4_OUTBOUND_SMTP_PASS", "");
    public static final String MAIL_DOMAIN = System.getenv().getOrDefault("PBL4_MAIL_DOMAIN", "pbl4.com");
    public static final String ADMIN_EMAIL = "admin@" + MAIL_DOMAIN.toLowerCase(Locale.ROOT);
    public static final boolean OUTBOUND_TRUST_ALL = Boolean.parseBoolean(System.getenv().getOrDefault("PBL4_OUTBOUND_TRUST_ALL", "false"));

    /**
     * Server được coi là "mở ra ngoài" khi: PRODUCTION, hoặc PBL4_EXPOSED=true, hoặc bật gửi thư ra ngoài,
     * hoặc dùng domain khác "pbl4.com". Khi đó BẮT BUỘC có PBL4_ADMIN_PASSWORD và PBL4_SECRET_KEY, thiếu thì thoát.
     */
    public static final boolean EXPOSED = computeExposed();

    public static final int HTTP_WORKER_THREADS = getHttpThreadsConfig();

    // ---- Bảo mật tài khoản (S1) ----
    public static final int PASSWORD_MIN_LENGTH = 10;
    public static final int PASSWORD_MAX_BYTES = 72; // giới hạn thực của bcrypt

    /** OPEN: đăng ký dùng ngay | APPROVAL: tài khoản mới ở trạng thái LOCKED, Admin mở khóa = duyệt | CLOSED: không đăng ký. */
    public static final String REGISTRATION_MODE = envEnum("PBL4_REGISTRATION_MODE", PRODUCTION ? "APPROVAL" : "OPEN",
            "OPEN", "APPROVAL", "CLOSED");

    /** Bắt buộc STLS (hoặc POP3S) trước USER/PASS. Mặc định true khi PRODUCTION. */
    public static final boolean POP3_REQUIRE_TLS = envBool("PBL4_POP3_REQUIRE_TLS", PRODUCTION);

    /** Số người nhận NGOÀI hệ thống tối đa mỗi giờ cho mỗi tài khoản (admin miễn). 0 = không giới hạn. */
    public static final int SEND_LIMIT_PER_HOUR = envInt("PBL4_SEND_LIMIT_PER_HOUR", 30, 0, 1000000);

    /** Phiên hết hạn sau từng này mili giây không hoạt động. */
    public static final int SESSION_IDLE_MS = envInt("PBL4_SESSION_IDLE_MS", 2 * 60 * 60 * 1000, 60_000, 86_400_000);
    /** Thời hạn tuyệt đối của phiên kể từ lúc đăng nhập, bất kể có hoạt động. */
    public static final int SESSION_ABSOLUTE_MS = envInt("PBL4_SESSION_ABSOLUTE_MS", 12 * 60 * 60 * 1000, 60_000, 604_800_000);

    // ---- Hàm phụ cấu hình ----

    private static String rawEnv(String name) {
        String v = System.getenv(name);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private static String adminPassFromEnv() {
        String v = System.getenv("PBL4_ADMIN_PASSWORD");
        return (v == null || v.trim().isEmpty()) ? null : v;
    }

    /** Chỉ đọc biến môi trường (không phụ thuộc field static khác) để gọi được ở bất kỳ thứ tự khởi tạo nào. */
    private static boolean computeExposed() {
        if (Boolean.parseBoolean(rawEnv("PBL4_PRODUCTION"))) return true;
        if (Boolean.parseBoolean(rawEnv("PBL4_EXPOSED"))) return true;
        if (Boolean.parseBoolean(rawEnv("PBL4_OUTBOUND_ENABLED"))) return true;
        String d = rawEnv("PBL4_MAIL_DOMAIN");
        return d != null && !"pbl4.com".equalsIgnoreCase(d);
    }

    private static String randomPassword() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        final String specials = "!@#$%^&*-_=+";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 18; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        // Đảm bảo đủ chữ, số, ký tự đặc biệt để đạt chính sách mật khẩu của hệ thống
        sb.append(specials.charAt(r.nextInt(specials.length())));
        sb.append((char) ('2' + r.nextInt(8)));
        char[] arr = sb.toString().toCharArray();
        for (int i = arr.length - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            char t = arr[i];
            arr[i] = arr[j];
            arr[j] = t;
        }
        return new String(arr);
    }

    /**
     * Gọi ĐÚNG lúc tạo tài khoản admin lần đầu. Nếu mật khẩu được sinh ngẫu nhiên (thiếu PBL4_ADMIN_PASSWORD)
     * thì in ra console MỘT LẦN duy nhất; sau đó không bao giờ in lại.
     */
    public static synchronized String consumeAdminInitialPassword() {
        if (ADMIN_PASS_FROM_ENV == null && !adminPassAnnounced) {
            adminPassAnnounced = true;
            System.out.println("==============================================================");
            System.out.println("[CONFIG] Đã tạo tài khoản admin: " + ADMIN_EMAIL);
            System.out.println("[CONFIG] Mật khẩu tạm (CHỈ HIỂN THỊ MỘT LẦN, hãy đăng nhập và đổi ngay): " + ADMIN_DEFAULT_PASS);
            System.out.println("==============================================================");
        }
        return ADMIN_DEFAULT_PASS;
    }

    // ---- Khóa bí mật ----
    // Khóa dẫn xuất khóa mã hóa thư nên KHÔNG được sinh ngẫu nhiên mỗi lần chạy (sẽ mất thư cũ).
    // Dev: lưu khóa ngẫu nhiên vào data/secret.key (quyền 600). Dữ liệu cũ đã mã hóa bằng khóa mặc định cũ
    // thì vẫn đọc được nhờ chế độ tương thích (cảnh báo mỗi lần chạy).
    private static final String LEGACY_DEV_SECRET_KEY = "dev-only-fallback-key-DO-NOT-USE-IN-PRODUCTION";

    private static String getSecretKeyEnv() {
        String key = System.getenv("PBL4_SECRET_KEY");
        if (key != null && !key.trim().isEmpty()) {
            return key.trim();
        }
        if (computeExposed()) {
            return ""; // validateProduction() sẽ thoát kèm thông báo rõ ràng trước khi khóa được dùng
        }
        return loadOrCreateDevKey();
    }

    private static String loadOrCreateDevKey() {
        Path keyFile = Paths.get("data", "secret.key");
        try {
            if (Files.isRegularFile(keyFile)) {
                String k = new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8).trim();
                if (!k.isEmpty()) return k;
            }
            if (hasExistingMailboxData()) {
                System.err.println("[CONFIG] CẢNH BÁO: chưa đặt PBL4_SECRET_KEY và đã có dữ liệu thư mã hóa bằng khóa mặc định cũ. "
                        + "Đang dùng khóa tương thích. Hãy đặt PBL4_SECRET_KEY trước khi mở server ra ngoài.");
                return LEGACY_DEV_SECRET_KEY;
            }
            byte[] rnd = new byte[32];
            new SecureRandom().nextBytes(rnd);
            String k = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
            Path parent = keyFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.deleteIfExists(keyFile);
                Files.createFile(keyFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            }
            Files.write(keyFile, k.getBytes(StandardCharsets.UTF_8));
            System.err.println("[CONFIG] Đã sinh khóa bí mật cho môi trường dev tại data/secret.key (hãy sao lưu file này cùng dữ liệu thư).");
            return k;
        } catch (IOException e) {
            System.err.println("[CONFIG] KHÔNG THỂ KHỞI ĐỘNG: không đọc/ghi được data/secret.key (" + e.getMessage()
                    + "). Đặt biến PBL4_SECRET_KEY hoặc cấp quyền ghi thư mục data/.");
            System.exit(1);
            return "";
        }
    }

    private static boolean hasExistingMailboxData() {
        Path dir = Paths.get("data", "mailboxes");
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> s = Files.list(dir)) {
            return s.findAny().isPresent();
        } catch (IOException e) {
            return true; // không chắc: coi như có dữ liệu để không đổi khóa bừa
        }
    }

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

    private static String envString(String name, String def) {
        String env = System.getenv(name);
        if (env == null || env.trim().isEmpty()) return def;
        return env.trim();
    }

    private static String envEnum(String name, String def, String... allowed) {
        String env = System.getenv(name);
        if (env == null || env.trim().isEmpty()) return def;
        String v = env.trim().toUpperCase(Locale.ROOT);
        for (String a : allowed) {
            if (a.equals(v)) return v;
        }
        return def;
    }

    public static final int SOCKET_BACKLOG = envInt("PBL4_SOCKET_BACKLOG", 500, 50, 65535);

    public static final int SMTP_POOL_SIZE = envInt("PBL4_SMTP_POOL_SIZE", 150, 1, 5000);
    public static final int SMTP_QUEUE_SIZE = envInt("PBL4_SMTP_QUEUE_SIZE", 50, 1, 10000);
    public static final int SMTP_MAX_CONN_PER_IP = envInt("PBL4_SMTP_MAX_CONN_PER_IP", 30, 0, 100000);

    public static final int POP3_POOL_SIZE = envInt("PBL4_POP3_POOL_SIZE", 150, 1, 5000);
    public static final int POP3_QUEUE_SIZE = envInt("PBL4_POP3_QUEUE_SIZE", 50, 1, 10000);
    public static final int POP3_MAX_CONN_PER_IP = envInt("PBL4_POP3_MAX_CONN_PER_IP", 30, 0, 100000);

    public static final int PREAUTH_TIMEOUT_MS = envInt("PBL4_PREAUTH_TIMEOUT_MS", 15000, 1000, 600000);
    public static final int POSTAUTH_TIMEOUT_MS = envInt("PBL4_POSTAUTH_TIMEOUT_MS", 30000, 1000, 3600000);
    public static final int SESSION_MAX_MS = envInt("PBL4_SESSION_MAX_MS", 1800000, 10000, 86400000);

    public static final int MAX_COMMAND_LINE_BYTES = envInt("PBL4_MAX_COMMAND_LINE_BYTES", 1024, 128, 65536);
    public static final int SMTP_MAX_DATA_LINE_BYTES = envInt("PBL4_SMTP_MAX_DATA_LINE_BYTES", 1000, 100, 1048576);

    public static final int SMTP_MAX_RECIPIENTS = envInt("PBL4_SMTP_MAX_RECIPIENTS", 100, 1, 100000);

    public static final boolean SMTP_REQUIRE_TLS = envBool("PBL4_SMTP_REQUIRE_TLS", true);

    public static final int MAIL_SUMMARY_CACHE_MAX_ENTRIES = 20000;
    public static final int MAIL_META_CACHE_MAX_ENTRIES = 100000;

    public static final int MAX_BODY_AUTH_BYTES = 4 * 1024;
    public static final int MAX_BODY_ADMIN_BYTES = 200 * 1024;
    public static final long MAX_BODY_SEND_BYTES = MAX_MESSAGE_BYTES * 3 / 2 + 64 * 1024;
    public static final long BODY_READ_IDLE_TIMEOUT_MS = 10_000;
    public static final long BODY_READ_TOTAL_TIMEOUT_SMALL_MS = 15_000;
    public static final int HTTP_MAX_REQ_TIME_SECONDS = 60;
    public static final long BODY_READ_TOTAL_TIMEOUT_SEND_MS = 55_000;

    public static final int SSE_MAX_PER_USER = 5;
    public static final long SSE_HEARTBEAT_SECONDS = 20;
    public static final int SSE_NOTIFY_THREADS = 2;
    public static final int SSE_NOTIFY_QUEUE_SIZE = 10_000;
    public static final long SSE_TICKET_TTL_MS = 30_000;

    public static final int SESSION_MAX_PER_USER = 10;

    public static final int THROTTLE_MAX_ATTEMPTS_PER_PAIR = 5;
    public static final int THROTTLE_MAX_ATTEMPTS_PER_IP = 30;
    public static final int THROTTLE_MAX_REGISTER_PER_IP = 20;
    public static final long THROTTLE_WINDOW_MS = 15 * 60 * 1000L;

    public static final long ADMIN_STATS_CACHE_MS = 45_000;
    public static final int ADMIN_LOGS_DEFAULT_LINES = 500;
    public static final int ADMIN_LOGS_MAX_LINES = 2000;
    public static final long ADMIN_LOG_MAX_BYTES = 5 * 1024 * 1024L;
    public static final int ADMIN_LOG_KEEP_FILES = 3;

    public static final String MAIL_HOSTNAME = envString("PBL4_MAIL_HOSTNAME", "mail." + MAIL_DOMAIN);

    public static final int MX_PORT = envInt("PBL4_MX_PORT", 25, 1, 65535);
    public static final int SUBMISSION_PORT = envInt("PBL4_SUBMISSION_PORT", 587, 1, 65535);
    public static final int SMTPS_PORT = envInt("PBL4_SMTPS_PORT", 465, 0, 65535);

    public static final String OUTBOUND_MODE = envEnum("PBL4_OUTBOUND_MODE", "DIRECT", "DIRECT", "RELAY");
    public static final String OUTBOUND_TLS = envEnum("PBL4_OUTBOUND_TLS", "OPPORTUNISTIC", "OPPORTUNISTIC", "REQUIRED", "OFF");
    public static final int OUTBOUND_REMOTE_PORT = envInt("PBL4_OUTBOUND_REMOTE_PORT", 25, 1, 65535);
    public static final int OUTBOUND_WORKERS = envInt("PBL4_OUTBOUND_WORKERS", 10, 1, 1000);
    public static final int OUTBOUND_PER_DOMAIN_CONN = envInt("PBL4_OUTBOUND_PER_DOMAIN_CONN", 2, 1, 100);
    public static final int OUTBOUND_MAX_AGE_HOURS = envInt("PBL4_OUTBOUND_MAX_AGE_HOURS", 96, 1, 24 * 90);
    public static final boolean OUTBOUND_USE_IPV6 = envBool("PBL4_OUTBOUND_USE_IPV6", false);

    public static final String DKIM_SELECTOR = envString("PBL4_DKIM_SELECTOR", "pbl4");
    public static final String DKIM_KEY_DIR = envString("PBL4_DKIM_KEY_DIR", "data/dkim/");
    public static final String SPF_MODE = envEnum("PBL4_SPF_MODE", "MARK", "OFF", "MARK", "ENFORCE");
    public static final boolean DMARC_ENFORCE = envBool("PBL4_DMARC_ENFORCE", true);

    public static final String DNS_SERVERS = envString("PBL4_DNS_SERVERS", "");

    public static final int INBOUND_MAX_CONN_PER_IP = envInt("PBL4_INBOUND_MAX_CONN_PER_IP", 20, 0, 100000);
    public static final int INBOUND_MSGS_PER_IP_PER_MIN = envInt("PBL4_INBOUND_MSGS_PER_IP_PER_MIN", 30, 0, 1000000);

    private static final String ERR_ADMIN_PASS = "PBL4_ADMIN_PASSWORD chưa được thiết lập.";
    private static final String ERR_SECRET_KEY = "PBL4_SECRET_KEY chưa được thiết lập.";

    public static List<String> productionErrors(boolean production, String adminPassEnv, String secretKeyEnv, String mailDomain) {
        List<String> errors = new ArrayList<>();
        if (!production) return errors;
        if (adminPassEnv == null || adminPassEnv.trim().isEmpty()) {
            errors.add(ERR_ADMIN_PASS);
        }
        if (secretKeyEnv == null || secretKeyEnv.trim().isEmpty()) {
            errors.add(ERR_SECRET_KEY);
        }
        if (mailDomain == null || mailDomain.trim().isEmpty() || "pbl4.com".equalsIgnoreCase(mailDomain.trim())) {
            errors.add("PBL4_MAIL_DOMAIN còn là giá trị mặc định \"pbl4.com\".");
        }
        return errors;
    }

    /** Khi server mở ra ngoài (nhưng chưa PRODUCTION): bắt buộc mật khẩu admin và khóa bí mật do người vận hành đặt. */
    public static List<String> exposureErrors(boolean exposed, String adminPassEnv, String secretKeyEnv) {
        List<String> errors = new ArrayList<>();
        if (!exposed) return errors;
        if (adminPassEnv == null || adminPassEnv.trim().isEmpty()) {
            errors.add(ERR_ADMIN_PASS);
        }
        if (secretKeyEnv == null || secretKeyEnv.trim().isEmpty()) {
            errors.add(ERR_SECRET_KEY);
        }
        return errors;
    }

    public static void validateProduction() {
        String adminEnv = System.getenv("PBL4_ADMIN_PASSWORD");
        String secretEnv = System.getenv("PBL4_SECRET_KEY");
        Set<String> errors = new LinkedHashSet<>(productionErrors(PRODUCTION, adminEnv, secretEnv, MAIL_DOMAIN));
        errors.addAll(exposureErrors(EXPOSED, adminEnv, secretEnv));
        if (errors.isEmpty()) return;

        System.err.println("==============================================================");
        System.err.println("[CONFIG] KHÔNG THỂ KHỞI ĐỘNG: cấu hình chưa an toàn để mở server ra ngoài.");
        System.err.println("[CONFIG] Lý do kiểm tra: " + (PRODUCTION
                ? "PBL4_PRODUCTION=true"
                : "server được mở ra ngoài (PBL4_EXPOSED / PBL4_OUTBOUND_ENABLED / PBL4_MAIL_DOMAIN khác pbl4.com)"));
        for (String e : errors) {
            System.err.println("[CONFIG]  - " + e);
        }
        System.err.println("[CONFIG] Cách sửa, ví dụ: export PBL4_ADMIN_PASSWORD='<mật khẩu mạnh>' PBL4_SECRET_KEY=\"$(openssl rand -base64 32)\"");
        System.err.println("[CONFIG] Lưu ý: PBL4_SECRET_KEY là khóa mã hóa thư, đổi giá trị sẽ làm mất khả năng đọc thư đã lưu.");
        System.err.println("==============================================================");
        System.exit(1);
    }

    static {
        if (PRODUCTION || EXPOSED) {
            validateProduction();
        }
    }
}