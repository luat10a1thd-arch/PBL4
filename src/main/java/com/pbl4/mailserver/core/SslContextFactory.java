package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SslContextFactory {

    // Chỉ cho phép TLS 1.2 trở lên (loại TLS 1.0/1.1/SSLv3)
    private static final String[] PREFERRED_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    // Sau khi khởi tạo thất bại, đợi một lúc mới thử lại (tránh chạy keytool/đọc đĩa liên tục)
    private static final long RETRY_AFTER_FAILURE_MS = 5000L;

    // Số lần thử đọc keystore khi file đang được ghi dở (tồn tại nhưng chưa ghi xong)
    private static final int LOAD_ATTEMPTS = 5;
    private static final long LOAD_RETRY_SLEEP_MS = 300L;

    private static final Object LOCK = new Object();
    private static volatile SSLContext cachedContext;
    private static boolean lastAttemptFailed = false;
    private static long lastFailureNanos = 0L;

    /**
     * Trả về SSLContext dùng chung. Chỉ tạo MỘT lần (lazy, thread-safe), các lần sau
     * trả ngay bản đã cache — không đọc keystore / không dựng KeyManager mỗi kết nối.
     * Trả null nếu không tạo được (người gọi trả 454 / -ERR).
     */
    public static SSLContext createSSLContext() {
        SSLContext ctx = cachedContext;
        if (ctx != null) {
            return ctx;
        }
        synchronized (LOCK) {
            if (cachedContext != null) {
                return cachedContext;
            }
            if (lastAttemptFailed
                    && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastFailureNanos) < RETRY_AFTER_FAILURE_MS) {
                return null;
            }
            try {
                cachedContext = buildSSLContext();
                lastAttemptFailed = false;
                return cachedContext;
            } catch (Exception e) {
                lastAttemptFailed = true;
                lastFailureNanos = System.nanoTime();
                System.err.println("[SSL FACTORY] Error creating SSLContext: " + e.getMessage());
                return null;
            }
        }
    }

    /**
     * Bọc socket thường thành SSLSocket phía server (dùng cho STARTTLS/STLS) và giới hạn
     * giao thức TLS 1.2+. Người gọi tự gọi startHandshake() sau khi đặt timeout.
     */
    public static SSLSocket wrapServerSocket(Socket plain) throws IOException {
        SSLContext ctx = createSSLContext();
        if (ctx == null) {
            throw new IOException("SSLContext chưa sẵn sàng");
        }
        SSLSocket sslSocket = (SSLSocket) ctx.getSocketFactory().createSocket(
                plain,
                plain.getInetAddress().getHostAddress(),
                plain.getPort(),
                true
        );
        sslSocket.setUseClientMode(false);
        sslSocket.setEnabledProtocols(pickProtocols(sslSocket.getSupportedProtocols()));
        return sslSocket;
    }

    private static String[] pickProtocols(String[] supported) throws IOException {
        List<String> supportedList = Arrays.asList(supported);
        List<String> enabled = new ArrayList<>();
        for (String p : PREFERRED_PROTOCOLS) {
            if (supportedList.contains(p)) {
                enabled.add(p);
            }
        }
        if (enabled.isEmpty()) {
            throw new IOException("JVM không hỗ trợ TLS 1.2 trở lên");
        }
        return enabled.toArray(new String[0]);
    }

    private static SSLContext buildSSLContext() throws Exception {
        File file = new File(ServerConfig.KEYSTORE_PATH);
        if (!file.exists()) {
            generateKeystoreWithKeytool();
        }

        KeyStore keyStore = loadKeyStoreWithRetry();

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, ServerConfig.KEYSTORE_PASS.toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        return sslContext;
    }

    /**
     * Đọc keystore, thử lại vài lần nếu file mới xuất hiện nhưng chưa ghi xong
     * (tiến trình/luồng khác đang tạo). Lỗi cuối cùng được ném ra nếu vẫn hỏng.
     */
    private static KeyStore loadKeyStoreWithRetry() throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= LOAD_ATTEMPTS; attempt++) {
            try {
                KeyStore keyStore = KeyStore.getInstance("JKS");
                try (FileInputStream fis = new FileInputStream(ServerConfig.KEYSTORE_PATH)) {
                    keyStore.load(fis, ServerConfig.KEYSTORE_PASS.toCharArray());
                }
                return keyStore;
            } catch (IOException e) {
                last = e;
                if (attempt < LOAD_ATTEMPTS) {
                    try {
                        Thread.sleep(LOAD_RETRY_SLEEP_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw ie;
                    }
                }
            }
        }
        throw last;
    }

    /**
     * Tạo keystore bằng keytool. Ghi ra file tạm rồi đổi tên nguyên tử sang đường dẫn thật,
     * nên không bao giờ có lúc keystore tồn tại mà chưa ghi xong.
     */
    private static synchronized void generateKeystoreWithKeytool() {
        Path target = Paths.get(ServerConfig.KEYSTORE_PATH);
        if (Files.exists(target)) {
            return;
        }

        Path tmp = Paths.get(ServerConfig.KEYSTORE_PATH + ".tmp-" + System.nanoTime());
        try {
            File dataDir = new File("data");
            if (!dataDir.exists()) {
                dataDir.mkdirs();
            }

            String keytoolPath = Paths.get(System.getProperty("java.home"), "bin", "keytool").toString();

            ProcessBuilder pb = new ProcessBuilder(
                keytoolPath,
                "-genkeypair",
                "-alias", "pbl4",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "365",
                "-keystore", tmp.toString(),
                "-storepass", ServerConfig.KEYSTORE_PASS,
                "-keypass", ServerConfig.KEYSTORE_PASS,
                "-dname", "CN=localhost, O=PBL4"
            );

            pb.redirectErrorStream(true);
            Process p = pb.start();

            // keytool chạy rất nhanh; đặt giới hạn để không treo vĩnh viễn
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                System.err.println("[SSL FACTORY] Keytool timeout");
                return;
            }
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = p.exitValue();

            if (exitCode != 0 || !Files.exists(tmp)) {
                System.err.println("[SSL FACTORY] Keytool error (Exit Code " + exitCode + "): " + output);
                return;
            }

            if (!Files.exists(target)) {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (Exception e) {
            System.err.println("[SSL FACTORY] Failed to generate keystore: " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {}
        }
    }
}
