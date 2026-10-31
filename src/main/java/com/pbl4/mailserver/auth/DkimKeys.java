package com.pbl4.mailserver.auth;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quản lý khóa riêng DKIM (RSA 2048, PKCS#8 PEM) trong DKIM_KEY_DIR.
 * Tên file: &lt;selector&gt;.&lt;domain&gt;.private.pem. Tự sinh ở lần dùng đầu tiên, quyền file chỉ chủ sở hữu đọc/ghi.
 */
final class DkimKeys {

    private static final ConcurrentHashMap<String, RSAPrivateCrtKey> CACHE = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private DkimKeys() {}

    static Path keyFile(Path dir, String domain, String selector) {
        return dir.resolve(safe(selector) + "." + safe(domain) + ".private.pem");
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    static RSAPrivateCrtKey loadOrCreate(Path dir, String domain, String selector) throws Exception {
        Path file = keyFile(dir, domain, selector);
        String cacheKey = file.toAbsolutePath().normalize().toString();
        RSAPrivateCrtKey cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        synchronized (LOCK) {
            cached = CACHE.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            RSAPrivateCrtKey key = Files.isRegularFile(file) ? read(file) : generate(dir, file);
            CACHE.put(cacheKey, key);
            return key;
        }
    }

    /** Khóa công khai dạng SubjectPublicKeyInfo (X.509) mã hóa Base64 - đúng định dạng thẻ p= trong bản ghi DNS. */
    static String publicKeyBase64(RSAPrivateCrtKey k) throws Exception {
        byte[] enc = KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(k.getModulus(), k.getPublicExponent()))
                .getEncoded();
        return Base64.getEncoder().encodeToString(enc);
    }

    private static RSAPrivateCrtKey generate(Path dir, Path file) throws Exception {
        if (POSIX) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectories(dir);
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        RSAPrivateCrtKey key = (RSAPrivateCrtKey) kp.getPrivate();

        String b64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";

        Path tmp = POSIX
                ? Files.createTempFile(dir, "dkim-", ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(dir, "dkim-", ".tmp");
        try {
            if (!POSIX) {
                File f = tmp.toFile();
                f.setReadable(false, false);
                f.setReadable(true, true);
                f.setWritable(false, false);
                f.setWritable(true, true);
            }
            Files.write(tmp, pem.getBytes(StandardCharsets.US_ASCII));
            try {
                Files.move(tmp, file); // không ghi đè: nếu tiến trình khác vừa tạo thì dùng khóa của họ
            } catch (FileAlreadyExistsException e) {
                return read(file);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return key;
    }

    private static RSAPrivateCrtKey read(Path file) throws Exception {
        String pem = Files.readString(file, StandardCharsets.US_ASCII);
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IOException("Khóa " + file + " ở dạng PKCS#1; hãy đổi sang PKCS#8: "
                    + "openssl pkcs8 -topk8 -nocrypt -in cu.pem -out moi.pem");
        }
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        Object k = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        if (!(k instanceof RSAPrivateCrtKey crt)) {
            throw new IOException("Khóa " + file + " không phải RSA CRT");
        }
        return crt;
    }
}
