package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tạo SSLContext cho STARTTLS / SMTPS / STLS.
 *
 * Nguồn chứng chỉ (theo thứ tự ưu tiên):
 *  1. PEM thật (Let's Encrypt): env PBL4_TLS_CERT_FILE (fullchain.pem) + PBL4_TLS_KEY_FILE (privkey.pem).
 *     Hỗ trợ khóa "PRIVATE KEY" (PKCS#8), "RSA PRIVATE KEY" (PKCS#1) và "EC PRIVATE KEY" (SEC1).
 *     Khóa mã hóa bằng mật khẩu không được hỗ trợ. Khi file đổi (certbot renew) sẽ tự nạp lại
 *     (kiểm tra tối đa mỗi 60 giây); nạp lại lỗi thì GIỮ chứng chỉ đang chạy.
 *  2. Keystore JKS tại ServerConfig.KEYSTORE_PATH (tự sinh bằng keytool, chứng chỉ TỰ KÝ) - như cũ.
 */
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
    private static volatile boolean usingPem = false;
    private static boolean lastAttemptFailed = false;
    private static long lastFailureNanos = 0L;

    // --- PEM + tự nạp lại ---
    private static volatile Path certPath = envPath("PBL4_TLS_CERT_FILE");
    private static volatile Path keyPath = envPath("PBL4_TLS_KEY_FILE");
    private static volatile long reloadCheckIntervalMs = 60_000L;
    private static volatile long lastCheckNanos = 0L;
    private static String loadedStamp = null;      // dấu vết file PEM đã nạp thành công (null = đang dùng keystore)
    private static String lastFailedStamp = null;  // dấu vết file PEM đã thử mà hỏng (tránh thử lại vô ích/spam log)
    private static boolean warnedSelfSigned = false;

    private static final byte[] OID_RSA_ENCRYPTION = {0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01};
    private static final byte[] OID_EC_PUBLIC_KEY = {0x06, 0x07, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x02, 0x01};

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]+)-----([A-Za-z0-9+/=\\s]*?)-----END \\1-----");

    private static Path envPath(String name) {
        String v = System.getenv(name);
        if (v == null || v.trim().isEmpty()) return null;
        return Paths.get(v.trim());
    }

    // ------------------------------------------------------------------ API công khai

    /**
     * Trả về SSLContext dùng chung. Chỉ tạo MỘT lần (lazy, thread-safe), các lần sau
     * trả ngay bản đã cache. Nếu cấu hình PEM, định kỳ kiểm tra file đổi và nạp lại.
     * Trả null nếu không tạo được (người gọi trả 454 / -ERR).
     */
    public static SSLContext createSSLContext() {
        SSLContext ctx = cachedContext;
        if (ctx != null) {
            if (pemConfigured()) {
                maybeReload();
                ctx = cachedContext;
            }
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
                SSLContext built = null;
                if (pemConfigured()) {
                    String stamp = pemStamp();
                    if (stamp == null) {
                        System.err.println("[SSL FACTORY] CẢNH BÁO: đã đặt PBL4_TLS_CERT_FILE/PBL4_TLS_KEY_FILE nhưng file chưa tồn tại ("
                                + certPath + ", " + keyPath + "). Tạm dùng chứng chỉ TỰ KÝ; sẽ tự nạp PEM khi file xuất hiện.");
                    } else {
                        try {
                            built = loadFromPem(certPath, keyPath);
                            usingPem = true;
                            loadedStamp = stamp;
                            lastFailedStamp = null;
                            System.out.println("[SSL FACTORY] Đã nạp chứng chỉ PEM: " + certPath);
                        } catch (Exception e) {
                            lastFailedStamp = stamp;
                            System.err.println("[SSL FACTORY] CẢNH BÁO: không nạp được PEM (" + e.getMessage()
                                    + "). Tạm dùng chứng chỉ TỰ KÝ.");
                        }
                    }
                } else if (!warnedSelfSigned && ServerConfig.PRODUCTION) {
                    System.err.println("[SSL FACTORY] CẢNH BÁO: PRODUCTION nhưng chưa đặt PBL4_TLS_CERT_FILE/PBL4_TLS_KEY_FILE; "
                            + "dùng chứng chỉ tự ký, máy chủ mail/client chuẩn sẽ không tin cậy.");
                }
                if (built == null) {
                    built = buildKeystoreContext();
                    usingPem = false;
                    loadedStamp = null;
                    if (!warnedSelfSigned) {
                        warnedSelfSigned = true;
                        System.err.println("[SSL FACTORY] Đang dùng chứng chỉ TỰ KÝ (" + ServerConfig.KEYSTORE_PATH
                                + "). Chỉ phù hợp dev/test, cần Let's Encrypt cho môi trường thật.");
                    }
                }
                lastCheckNanos = System.nanoTime();
                cachedContext = built;
                lastAttemptFailed = false;
                return built;
            } catch (Exception e) {
                lastAttemptFailed = true;
                lastFailureNanos = System.nanoTime();
                System.err.println("[SSL FACTORY] Error creating SSLContext: " + e.getMessage());
                return null;
            }
        }
    }

    /**
     * Bọc socket thường thành SSLSocket phía server (dùng cho STARTTLS/STLS/SMTPS) và giới hạn
     * giao thức TLS 1.2+. Người gọi tự gọi startHandshake() sau khi đặt timeout.
     * Không truyền IP làm hostname (host = null) để tránh dùng IP như tên SNI/peer host.
     */
    public static SSLSocket wrapServerSocket(Socket plain) throws IOException {
        SSLContext ctx = createSSLContext();
        if (ctx == null) {
            throw new IOException("SSLContext chưa sẵn sàng");
        }
        SSLSocket sslSocket = (SSLSocket) ctx.getSocketFactory().createSocket(
                plain,
                null,
                plain.getPort(),
                true
        );
        sslSocket.setUseClientMode(false);
        sslSocket.setEnabledProtocols(pickProtocols(sslSocket.getSupportedProtocols()));
        return sslSocket;
    }

    /** true nếu SSLContext đang chạy được nạp từ PEM (không phải tự ký). */
    public static boolean isUsingPem() {
        return usingPem;
    }

    /** Đổi đường dẫn PEM (dùng cho test/quản trị) và xóa cache để nạp lại ở lần gọi tới. */
    public static void setPemFiles(Path cert, Path key) {
        synchronized (LOCK) {
            certPath = cert;
            keyPath = key;
            resetLocked();
        }
    }

    /** Chu kỳ kiểm tra file PEM đổi (ms). Mặc định 60000; 0 = kiểm tra mỗi lần gọi (test). */
    public static void setReloadCheckIntervalMs(long ms) {
        reloadCheckIntervalMs = Math.max(0L, ms);
    }

    /** Xóa cache SSLContext. */
    public static void reset() {
        synchronized (LOCK) {
            resetLocked();
        }
    }

    private static void resetLocked() {
        cachedContext = null;
        usingPem = false;
        lastAttemptFailed = false;
        lastFailureNanos = 0L;
        lastCheckNanos = 0L;
        loadedStamp = null;
        lastFailedStamp = null;
        warnedSelfSigned = false;
    }

    /**
     * Dựng SSLContext từ file PEM (không đụng tới cache). Ném exception với thông báo rõ nếu hỏng.
     */
    public static SSLContext loadFromPem(Path cert, Path key) throws Exception {
        String certText = new String(Files.readAllBytes(cert), StandardCharsets.ISO_8859_1);
        List<byte[]> certDers = new ArrayList<>();
        for (PemBlock b : parsePem(certText)) {
            if ("CERTIFICATE".equals(b.type) || "X509 CERTIFICATE".equals(b.type)) {
                certDers.add(b.der);
            }
        }
        if (certDers.isEmpty()) {
            throw new GeneralSecurityException("Không tìm thấy khối CERTIFICATE trong " + cert);
        }
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<Certificate> chain = new ArrayList<>();
        for (byte[] der : certDers) {
            chain.add(cf.generateCertificate(new ByteArrayInputStream(der)));
        }

        String keyText = new String(Files.readAllBytes(key), StandardCharsets.ISO_8859_1);
        PrivateKey privateKey = readPrivateKey(keyText, key);
        X509Certificate leaf = (X509Certificate) chain.get(0);
        verifyKeyMatchesCert(privateKey, leaf, cert);
        warnIfExpiring(leaf);

        char[] pw = randomPassword();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("pbl4", privateKey, pw, chain.toArray(new Certificate[0]));

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        return sslContext;
    }

    // ------------------------------------------------------------------ nạp lại khi file đổi

    private static boolean pemConfigured() {
        return certPath != null && keyPath != null;
    }

    /** Dấu vết (mtime:size) của cặp file PEM; null nếu thiếu file. */
    private static String pemStamp() {
        try {
            Path c = certPath;
            Path k = keyPath;
            if (c == null || k == null || !Files.exists(c) || !Files.exists(k)) return null;
            return Files.getLastModifiedTime(c).toMillis() + ":" + Files.size(c) + "|"
                    + Files.getLastModifiedTime(k).toMillis() + ":" + Files.size(k);
        } catch (IOException e) {
            return null;
        }
    }

    private static void maybeReload() {
        long intervalNanos = TimeUnit.MILLISECONDS.toNanos(reloadCheckIntervalMs);
        if (System.nanoTime() - lastCheckNanos < intervalNanos) {
            return;
        }
        synchronized (LOCK) {
            long now = System.nanoTime();
            if (now - lastCheckNanos < intervalNanos) {
                return;
            }
            lastCheckNanos = now;
            String stamp = pemStamp();
            if (stamp == null || stamp.equals(loadedStamp) || stamp.equals(lastFailedStamp)) {
                return;
            }
            try {
                SSLContext fresh = loadFromPem(certPath, keyPath);
                cachedContext = fresh;
                usingPem = true;
                loadedStamp = stamp;
                lastFailedStamp = null;
                System.out.println("[SSL FACTORY] Đã nạp lại chứng chỉ PEM (file thay đổi): " + certPath);
            } catch (Exception e) {
                lastFailedStamp = stamp;
                System.err.println("[SSL FACTORY] CẢNH BÁO: nạp lại PEM thất bại (" + e.getMessage()
                        + "); giữ chứng chỉ đang chạy, sẽ thử lại khi file đổi tiếp.");
            }
        }
    }

    private static void warnIfExpiring(X509Certificate leaf) {
        long left = leaf.getNotAfter().getTime() - System.currentTimeMillis();
        if (left < 0) {
            System.err.println("[SSL FACTORY] CẢNH BÁO: chứng chỉ ĐÃ HẾT HẠN (" + leaf.getNotAfter() + ").");
        } else if (left < TimeUnit.DAYS.toMillis(14)) {
            System.err.println("[SSL FACTORY] CẢNH BÁO: chứng chỉ sắp hết hạn (" + leaf.getNotAfter() + ").");
        }
    }

    private static char[] randomPassword() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return Base64.getEncoder().encodeToString(b).toCharArray();
    }

    // ------------------------------------------------------------------ PEM / DER

    private static final class PemBlock {
        final String type;
        final byte[] der;

        PemBlock(String type, byte[] der) {
            this.type = type;
            this.der = der;
        }
    }

    private static List<PemBlock> parsePem(String text) {
        List<PemBlock> out = new ArrayList<>();
        Matcher m = PEM_BLOCK.matcher(text);
        while (m.find()) {
            try {
                out.add(new PemBlock(m.group(1), Base64.getMimeDecoder().decode(m.group(2).trim())));
            } catch (IllegalArgumentException ignored) {
                // khối base64 hỏng: bỏ qua
            }
        }
        return out;
    }

    private static PrivateKey readPrivateKey(String keyText, Path where) throws Exception {
        for (PemBlock b : parsePem(keyText)) {
            switch (b.type) {
                case "PRIVATE KEY":
                    return parsePkcs8(b.der);
                case "RSA PRIVATE KEY":
                    return parsePkcs8(wrapRsaPkcs1(b.der));
                case "EC PRIVATE KEY":
                    return parsePkcs8(wrapEcSec1(b.der));
                case "ENCRYPTED PRIVATE KEY":
                    throw new GeneralSecurityException("Khóa riêng trong " + where
                            + " được mã hóa bằng mật khẩu, không hỗ trợ (dùng khóa không mật khẩu của certbot).");
                default:
                    break;
            }
        }
        throw new GeneralSecurityException("Không tìm thấy khóa riêng (PRIVATE KEY / RSA PRIVATE KEY / EC PRIVATE KEY) trong " + where);
    }

    private static PrivateKey parsePkcs8(byte[] pkcs8) throws GeneralSecurityException {
        for (String alg : new String[]{"RSA", "EC"}) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            } catch (InvalidKeySpecException ignored) {
                // thử thuật toán kế tiếp
            }
        }
        throw new GeneralSecurityException("Định dạng khóa riêng không được hỗ trợ (chỉ RSA hoặc EC).");
    }

    private static void verifyKeyMatchesCert(PrivateKey key, X509Certificate leaf, Path certFile) throws GeneralSecurityException {
        String alg = key.getAlgorithm();
        String sigAlg;
        if ("RSA".equalsIgnoreCase(alg)) {
            sigAlg = "SHA256withRSA";
        } else if ("EC".equalsIgnoreCase(alg) || "ECDSA".equalsIgnoreCase(alg)) {
            sigAlg = "SHA256withECDSA";
        } else {
            return;
        }
        byte[] probe = "pbl4-key-check".getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance(sigAlg);
        s.initSign(key);
        s.update(probe);
        byte[] sig = s.sign();
        Signature v = Signature.getInstance(sigAlg);
        v.initVerify(leaf.getPublicKey());
        v.update(probe);
        if (!v.verify(sig)) {
            throw new GeneralSecurityException("Khóa riêng không khớp với chứng chỉ đầu tiên trong " + certFile);
        }
    }

    /** PKCS#1 RSAPrivateKey -> PKCS#8 PrivateKeyInfo. */
    private static byte[] wrapRsaPkcs1(byte[] pkcs1) {
        byte[] algId = der(0x30, OID_RSA_ENCRYPTION, new byte[]{0x05, 0x00});
        return der(0x30, new byte[]{0x02, 0x01, 0x00}, algId, der(0x04, pkcs1));
    }

    /** SEC1 ECPrivateKey -> PKCS#8 PrivateKeyInfo (lấy OID đường cong từ trường [0]). */
    private static byte[] wrapEcSec1(byte[] sec1) throws IOException {
        int[] root = tlv(sec1, 0);
        if (root[0] != 0x30) throw new IOException("EC PRIVATE KEY không hợp lệ");
        int p = root[1];
        int end = root[1] + root[2];
        byte[] curveOid = null;
        while (p < end) {
            int[] t = tlv(sec1, p);
            if (t[0] == 0xA0) {
                int cs = p + t[1];
                int[] o = tlv(sec1, cs);
                if (o[0] == 0x06) {
                    curveOid = Arrays.copyOfRange(sec1, cs, cs + o[1] + o[2]);
                }
            }
            p += t[1] + t[2];
        }
        if (curveOid == null) throw new IOException("EC PRIVATE KEY thiếu tham số đường cong");
        byte[] algId = der(0x30, OID_EC_PUBLIC_KEY, curveOid);
        return der(0x30, new byte[]{0x02, 0x01, 0x00}, algId, der(0x04, sec1));
    }

    /** Đọc header DER tại vị trí p: trả {tag, độ dài header, độ dài nội dung}. */
    private static int[] tlv(byte[] b, int p) throws IOException {
        if (p + 2 > b.length) throw new IOException("DER bị cắt cụt");
        int tag = b[p] & 0xFF;
        int l = b[p + 1] & 0xFF;
        int hdr = 2;
        int len;
        if (l < 0x80) {
            len = l;
        } else {
            int nb = l & 0x7F;
            if (nb == 0 || nb > 3 || p + 2 + nb > b.length) throw new IOException("DER độ dài không hợp lệ");
            len = 0;
            for (int i = 0; i < nb; i++) {
                len = (len << 8) | (b[p + 2 + i] & 0xFF);
            }
            hdr = 2 + nb;
        }
        if (p + hdr + len > b.length) throw new IOException("DER bị cắt cụt");
        return new int[]{tag, hdr, len};
    }

    private static byte[] der(int tag, byte[]... parts) {
        int n = 0;
        for (byte[] part : parts) n += part.length;
        ByteArrayOutputStream o = new ByteArrayOutputStream(n + 6);
        o.write(tag);
        if (n < 128) {
            o.write(n);
        } else if (n < 256) {
            o.write(0x81);
            o.write(n);
        } else if (n < 65536) {
            o.write(0x82);
            o.write(n >> 8);
            o.write(n & 0xFF);
        } else {
            o.write(0x83);
            o.write(n >> 16);
            o.write((n >> 8) & 0xFF);
            o.write(n & 0xFF);
        }
        for (byte[] part : parts) o.write(part, 0, part.length);
        return o.toByteArray();
    }

    // ------------------------------------------------------------------ keystore tự ký (như cũ)

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

    private static SSLContext buildKeystoreContext() throws Exception {
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