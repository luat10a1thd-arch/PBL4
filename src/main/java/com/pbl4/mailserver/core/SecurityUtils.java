// FILE: src/main/java/com/pbl4/mailserver/core/SecurityUtils.java
package com.pbl4.mailserver.core;

import org.mindrot.jbcrypt.BCrypt;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SecurityUtils {

    private static final int KEY_SIZE = 256;
    /** Chỉ còn dùng để ĐỌC thư CBC cũ: phải giữ nguyên giá trị để ra đúng khóa cũ. */
    private static final int ITERATION_COUNT = 65536;

    /**
     * Khóa chủ mới: PBKDF2-HMAC-SHA256(PBL4_SECRET_KEY, muối cố định của ứng dụng, 600.000 vòng), tính đúng MỘT lần mỗi tiến trình.
     * Lý do: PBL4_SECRET_KEY có thể là cụm mật khẩu do người vận hành tự đặt (entropy thấp) nên cần kéo giãn một lần;
     * còn khóa theo từng user thì dùng HKDF (nhanh, không cần kéo giãn lần nữa) nên không phải lưu khóa vào bộ nhớ đệm lâu dài.
     */
    private static final int MASTER_PBKDF2_ITERATIONS = 600_000;
    private static final byte[] MASTER_SALT = "pbl4-mail-master-key-v1".getBytes(StandardCharsets.UTF_8);
    private static final String MAIL_KEY_INFO_PREFIX = "pbl4/mail/aes-256-gcm/v1|";

    /** Cost bcrypt hiện hành. Hash cũ (cost thấp hơn) được băm lại khi đăng nhập thành công. */
    public static final int BCRYPT_COST = 12;

    // Khóa của bộ nhớ đệm là băm SHA-256, không giữ khóa chủ dạng rõ trong key của Map.
    private static final ConcurrentHashMap<String, SecretKey> derivedKeyCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, byte[]> masterKeyCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, String> dummyHashByCost = new ConcurrentHashMap<>();

    public static String hashPassword(String plainPassword) {
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(BCRYPT_COST));
    }

    /**
     * So khớp mật khẩu với hash bcrypt. Tự băm lại rồi so bằng MessageDigest.isEqual (thời gian không phụ thuộc nội dung),
     * không dựa vào cách so sánh bên trong thư viện.
     */
    public static boolean checkPassword(String plainPassword, String hashedPassword) {
        if (plainPassword == null || hashedPassword == null || !hashedPassword.startsWith("$2a$")) {
            return false;
        }
        try {
            String recomputed = BCrypt.hashpw(plainPassword, hashedPassword);
            return MessageDigest.isEqual(recomputed.getBytes(StandardCharsets.UTF_8),
                    hashedPassword.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return false; // hash hỏng
        }
    }

    /** Cost của một hash "$2a$NN$...", hoặc -1 nếu không đọc được. */
    public static int costOf(String hash) {
        if (hash == null || hash.length() < 7 || !hash.startsWith("$2a$") || hash.charAt(6) != '$') return -1;
        try {
            return Integer.parseInt(hash.substring(4, 6));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** true nếu hash hợp lệ nhưng cost thấp hơn BCRYPT_COST. */
    public static boolean needsRehash(String hash) {
        int c = costOf(hash);
        return c >= 4 && c < BCRYPT_COST;
    }

    private static String dummyFor(int cost) {
        return dummyHashByCost.computeIfAbsent(cost,
                c -> BCrypt.hashpw("khong-phai-mat-khau-that", BCrypt.gensalt(c)));
    }

    static {
        dummyFor(BCRYPT_COST); // tính trước để lần đăng nhập đầu không chậm hơn các lần sau
    }

    /**
     * Xác thực dùng cho đăng nhập: thời gian gần như nhau dù tài khoản không tồn tại (stored == null),
     * hash hỏng, hay hash cũ cost thấp (bù bằng các lần băm giả cùng cost).
     */
    public static boolean verifyPassword(String plainPassword, String storedHash) {
        if (plainPassword == null) plainPassword = "";
        int cost = costOf(storedHash);
        if (storedHash == null || cost < 0) {
            checkPassword(plainPassword, dummyFor(BCRYPT_COST));
            return false;
        }
        boolean ok = checkPassword(plainPassword, storedHash);
        if (cost >= 4 && cost < BCRYPT_COST) {
            int extra = Math.min((1 << (BCRYPT_COST - cost)) - 1, 63);
            String d = dummyFor(cost);
            for (int i = 0; i < extra; i++) checkPassword(plainPassword, d);
        }
        return ok;
    }

    /** So sánh chuỗi bí mật/mã trong thời gian không phụ thuộc nội dung (chỉ lộ độ dài). */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] generateSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    /**
     * Khóa CŨ (PBKDF2 theo user) - chỉ dùng để đọc thư CBC cũ và cho công cụ chuyển đổi.
     * Thư mới dùng deriveMailKey().
     */
    public static SecretKey deriveKey(String secretKey, byte[] salt) throws Exception {
        String cacheKey = sha256B64(secretKey + "|" + Base64.getEncoder().encodeToString(salt));
        SecretKey cached = derivedKeyCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(secretKey.toCharArray(), salt, ITERATION_COUNT, KEY_SIZE);
        SecretKey generatedKey = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");

        derivedKeyCache.put(cacheKey, generatedKey);
        return generatedKey;
    }

    /**
     * Khóa AES-256 cho thư mới của một người dùng: HKDF-SHA256(IKM = khóa chủ đã kéo giãn,
     * salt = salt riêng của user, info = "pbl4/mail/aes-256-gcm/v1|" + tên user chữ thường).
     * Không lưu vào bộ nhớ đệm: HKDF chỉ tốn vài micro giây.
     * (Server phải giải mã khi user offline để nhận thư qua SMTP/POP3 nên không thể "xóa khóa khi logout".)
     */
    public static SecretKey deriveMailKey(String secretKey, byte[] userSalt, String cleanUser) throws GeneralSecurityException {
        byte[] master = masterFor(secretKey);
        byte[] info = (MAIL_KEY_INFO_PREFIX + cleanUser.toLowerCase(java.util.Locale.ROOT)).getBytes(StandardCharsets.UTF_8);
        byte[] okm = hkdfSha256(master, userSalt, info, 32);
        return new SecretKeySpec(okm, "AES");
    }

    private static byte[] masterFor(String secretKey) {
        return masterKeyCache.computeIfAbsent(sha256B64(secretKey), k -> {
            try {
                SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                PBEKeySpec spec = new PBEKeySpec(secretKey.toCharArray(), MASTER_SALT, MASTER_PBKDF2_ITERATIONS, 256);
                return f.generateSecret(spec).getEncoded();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Không dẫn xuất được khóa chủ", e);
            }
        });
    }

    /** HKDF (RFC 5869) với HMAC-SHA256. */
    public static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) throws GeneralSecurityException {
        if (length < 1 || length > 255 * 32) throw new IllegalArgumentException("length");
        Mac mac = Mac.getInstance("HmacSHA256");
        byte[] s = (salt == null || salt.length == 0) ? new byte[32] : salt;
        mac.init(new SecretKeySpec(s, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);

        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] okm = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        int counter = 1;
        while (pos < length) {
            mac.reset();
            mac.update(t);
            mac.update(info);
            mac.update((byte) counter);
            t = mac.doFinal();
            int n = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, okm, pos, n);
            pos += n;
            counter++;
        }
        Arrays.fill(prk, (byte) 0);
        return okm;
    }

    /** Xóa khóa CŨ (PBKDF2) khỏi bộ nhớ đệm, ví dụ sau khi chuyển đổi xong. */
    public static void clearLegacyKeyCache() {
        derivedKeyCache.clear();
    }

    private static String sha256B64(String s) {
        try {
            return Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- Quyền file (POSIX 600 / thư mục 700) ----------

    private static boolean isPosix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /** Đặt quyền chỉ chủ sở hữu (file 600, thư mục 700). Không làm gì nếu hệ thống không hỗ trợ POSIX (vd Windows). */
    public static void restrictToOwner(Path path, boolean isDir) {
        try {
            if (isPosix()) {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString(isDir ? "rwx------" : "rw-------");
                Files.setPosixFilePermissions(path, perms);
            }
        } catch (Exception ignored) {}
    }

    /** Siết quyền cả cây thư mục (thư mục 700, file 600). Không đi theo symlink. Lỗi từng file bị bỏ qua. */
    public static void restrictTreeToOwner(Path root) {
        if (!isPosix() || root == null || !Files.isDirectory(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    restrictToOwner(dir, true);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) restrictToOwner(file, false);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
    }

    /**
     * Ghi file với quyền 600 NGAY LÚC TẠO (không có khoảng hở file 644 rồi mới chmod).
     * Dùng cho mọi file chứa hash/mã khôi phục (users.json, file khôi phục...) và file thư.
     */
    public static void writeOwnerOnly(Path path, byte[] data) throws IOException {
        if (isPosix()) {
            Files.deleteIfExists(path);
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.write(path, data);
        restrictToOwner(path, false);
    }
}