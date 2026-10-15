package com.pbl4.mailserver.config;

public class ServerConfig {

    // Cấu hình Cổng giao thức (Port)
    public static final int HTTP_PORT = 8080;
    public static final int SMTP_PORT = 2525;
    public static final int POP3_PORT = 1110;

    // Cấu hình Luồng & Quản lý kết nối
    public static final int THREAD_POOL_SIZE = 20;
    public static final int SOCKET_TIMEOUT_MS = 30000; // 30 giây

    // Cấu hình Đường dẫn lưu trữ & SSL Keystore
    public static final String BASE_DATA_DIR = "data/mailboxes/";
    public static final String KEYSTORE_PATH = "data/keystore.jks";

    // Cấu hình Quota dung lượng mặc định mỗi Hòm thư (MB)
    public static volatile long MAX_MAILBOX_QUOTA_MB = 100;

    // =========================================================================
    // FIX VẤN ĐỀ 6 BÁO CÁO: Đọc KEYSTORE_PASS và SECRET_KEY từ Biến môi trường
    // =========================================================================

    // Mật khẩu Keystore chứa chứng chỉ SSL/TLS (Đọc từ PBL4_KEYSTORE_PASS, fallback cho Dev)
    public static final String KEYSTORE_PASS = System.getenv().getOrDefault(
            "PBL4_KEYSTORE_PASS", "pbl4secretpass");

    // Khóa bí mật mã hóa (Đọc từ PBL4_SECRET_KEY, fallback cho Dev)
    public static final String SECRET_KEY = System.getenv().getOrDefault(
            "PBL4_SECRET_KEY", "dev-only-fallback-key-DO-NOT-USE-IN-PRODUCTION");
}