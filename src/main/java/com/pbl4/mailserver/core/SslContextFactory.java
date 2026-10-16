package com.pbl4.mailserver.core;

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

    private static final String KEYSTORE_PATH = "data/keystore.jks";
    private static final String KEYSTORE_PASS = "pbl4secretpass";

    public static SSLServerSocketFactory createSSLServerSocketFactory() {
        try {
            File file = new File(KEYSTORE_PATH);
            if (!file.exists()) {
                generateKeystoreWithKeytool();
            }

            KeyStore keyStore = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(KEYSTORE_PATH)) {
                keyStore.load(fis, KEYSTORE_PASS.toCharArray());
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, KEYSTORE_PASS.toCharArray());

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), null, null);

            return sslContext.getServerSocketFactory();
        } catch (Exception e) {
            System.err.println("[SSL FACTORY] CẢNH BÁO: Lỗi khởi tạo SSLContext (Server sẽ chạy Plaintext): " + e.getMessage());
            return null;
        }
    }

    private static synchronized void generateKeystoreWithKeytool() {
        if (Files.exists(Paths.get(KEYSTORE_PATH))) {
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
                "-keystore", KEYSTORE_PATH,
                "-storepass", KEYSTORE_PASS,
                "-keypass", KEYSTORE_PASS,
                "-dname", "CN=localhost, O=PBL4"
            );

            pb.redirectErrorStream(true);
            Process p = pb.start();

            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = p.waitFor();

            if (exitCode == 0 && Files.exists(Paths.get(KEYSTORE_PATH))) {
                System.out.println("[SSL FACTORY] Đã tự động sinh file keystore.jks thành công!");
            } else {
                System.err.println("[SSL FACTORY] Lỗi keytool (Exit Code " + exitCode + "): " + output);
            }
        } catch (Exception e) {
            System.err.println("[SSL FACTORY] Không thể tự sinh keystore qua keytool: " + e.getMessage());
        }
    }
}