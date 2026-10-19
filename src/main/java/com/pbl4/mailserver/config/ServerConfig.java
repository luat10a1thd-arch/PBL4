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
    public static final String SECRET_KEY = System.getenv().getOrDefault("PBL4_SECRET_KEY", "dev-only-fallback-key-DO-NOT-USE-IN-PRODUCTION");

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