package com.pbl4.mailserver.core;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;

public class SslContextFactory {

    private static final String KEYSTORE_PATH = "data/keystore.jks";
    private static final String KEYSTORE_PASS = "pbl4secretpass";

    public static SSLServerSocketFactory createSSLServerSocketFactory() {
        try {
            File file = new File(KEYSTORE_PATH);
            if (!file.exists()) {
                // Tự động tạo keystore bằng lệnh keytool hệ thống nếu chưa có
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
            System.err.println("[SSL FACTORY] Lỗi khởi tạo SSLContext: " + e.getMessage());
            return null;
        }
    }

    private static void generateKeystoreWithKeytool() {
        try {
            File dataDir = new File("data");
            if (!dataDir.exists()) {
                dataDir.mkdirs();
            }

            String cmd = String.format(
                "keytool -genkeypair -alias pbl4mail -keyalg RSA -keysize 2048 " +
                "-keystore %s -storepass %s -keypass %s -validity 365 " +
                "-dname \"CN=PBL4 Mail Server, OU=DUT, O=DUT, L=DaNang, C=VN\"",
                KEYSTORE_PATH, KEYSTORE_PASS, KEYSTORE_PASS
            );

            Process process = Runtime.getRuntime().exec(cmd);
            process.waitFor();
            System.out.println("[SSL FACTORY] Đã tự động sinh file keystore.jks thành công!");
        } catch (Exception e) {
            System.err.println("[SSL FACTORY] Không thể tự sinh keystore qua keytool: " + e.getMessage());
        }
    }
}