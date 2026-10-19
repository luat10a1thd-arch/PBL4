package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;

public class SslContextFactory {

    public static SSLServerSocketFactory createSSLServerSocketFactory() {
        try {
            File file = new File(ServerConfig.KEYSTORE_PATH);
            if (!file.exists()) {
                generateKeystoreWithKeytool();
            }

            KeyStore keyStore = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(ServerConfig.KEYSTORE_PATH)) {
                keyStore.load(fis, ServerConfig.KEYSTORE_PASS.toCharArray());
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, ServerConfig.KEYSTORE_PASS.toCharArray());

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), null, null);

            return sslContext.getServerSocketFactory();
        } catch (Exception e) {
            System.err.println("[SSL FACTORY] CẢNH BÁO: Lỗi khởi tạo SSLContext (Server sẽ chạy Plaintext): " + e.getMessage());
            return null;
        }
    }

    private static synchronized void generateKeystoreWithKeytool() {
        if (Files.exists(Paths.get(ServerConfig.KEYSTORE_PATH))) {
            return;
        }

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
                "-keystore", ServerConfig.KEYSTORE_PATH,
                "-storepass", ServerConfig.KEYSTORE_PASS,
                "-keypass", ServerConfig.KEYSTORE_PASS,
                "-dname", "CN=localhost, O=PBL4"
            );

            pb.redirectErrorStream(true);
            Process p = pb.start();

            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = p.waitFor();

            if (exitCode == 0 && Files.exists(Paths.get(ServerConfig.KEYSTORE_PATH))) {
                System.out.println("[SSL FACTORY] Đã tự động sinh file keystore.jks thành công!");
            } else {
                System.err.println("[SSL FACTORY] Lỗi keytool (Exit Code " + exitCode + "): " + output);
            }
        } catch (Exception e) {
            System.err.println("[SSL FACTORY] Không thể tự sinh keystore qua keytool: " + e.getMessage());
        }
    }
}