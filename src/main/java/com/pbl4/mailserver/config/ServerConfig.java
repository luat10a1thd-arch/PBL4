<<<<<<< HEAD
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
}
=======
package com.pbl4.mailserver.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public class ServerConfig {

    public static final int HTTP_PORT = 8080;
    public static final int SMTP_PORT = envInt("PBL4_SMTP_PORT", 2525, 1, 65535);
    public static final int POP3_PORT = 1110;

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
    public static final String ADMIN_EMAIL = "admin@" + MAIL_DOMAIN.toLowerCase(Locale.ROOT);
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

    public static final boolean PRODUCTION = envBool("PBL4_PRODUCTION", false);

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

    public static List<String> productionErrors(boolean production, String adminPassEnv, String secretKeyEnv, String mailDomain) {
        List<String> errors = new ArrayList<>();
        if (!production) return errors;
        if (adminPassEnv == null || adminPassEnv.trim().isEmpty()) {
            errors.add("PBL4_ADMIN_PASSWORD chưa được thiết lập.");
        }
        if (secretKeyEnv == null || secretKeyEnv.trim().isEmpty()) {
            errors.add("PBL4_SECRET_KEY chưa được thiết lập.");
        }
        if (mailDomain == null || mailDomain.trim().isEmpty() || "pbl4.com".equalsIgnoreCase(mailDomain.trim())) {
            errors.add("PBL4_MAIL_DOMAIN còn là giá trị mặc định \"pbl4.com\".");
        }
        return errors;
    }

    public static void validateProduction() {
        List<String> errors = productionErrors(PRODUCTION,
                System.getenv("PBL4_ADMIN_PASSWORD"), System.getenv("PBL4_SECRET_KEY"), MAIL_DOMAIN);
        if (errors.isEmpty()) return;
        System.exit(1);
    }

    static {
        if (PRODUCTION) {
            validateProduction();
        }
    }
}
>>>>>>> stash
