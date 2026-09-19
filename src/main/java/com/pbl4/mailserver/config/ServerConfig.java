package com.pbl4.mailserver.config;

import java.io.InputStream;
import java.util.Properties;

public class ServerConfig {

    private static final Properties properties = new Properties();

    static {
        try (InputStream input = ServerConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (input == null) {
                throw new RuntimeException("Không tìm thấy application.properties trong classpath");
            }
            properties.load(input);
        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi đọc application.properties", e);
        }
    }

    public static int getSmtpPort() {
        return Integer.parseInt(properties.getProperty("smtp.port", "2525"));
    }

    public static int getPop3Port() {
        return Integer.parseInt(properties.getProperty("pop3.port", "1110"));
    }

    public static String getDataRoot() {
        return properties.getProperty("data.root", "data/mailboxes");
    }

    public static String getUsersRoot() {
        return properties.getProperty("users.root", "data/users");
    }

    public static String getMasterSecretKey() {
        return properties.getProperty("master.secret.key");
    }
}