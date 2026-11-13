package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.LongSupplier;

/**
 * Trạng thái khôi phục tài khoản theo từng user (email khôi phục, mã dự phòng, OTP đặt lại mật khẩu,
 * mã xác minh email đang chờ).
 *
 * Nguyên tắc:
 *  - Không lưu bí mật dạng rõ: mã dự phòng/OTP/mã xác minh đều lưu HMAC-SHA256(SECRET_KEY, ...).
 *  - Tên file KHÔNG lấy từ client: là SHA-256(username đã chuẩn hóa) dạng hex.
 *  - Ghi nguyên tử (file tạm cùng thư mục + ATOMIC_MOVE), quyền rw------- trên POSIX.
 *  - Mọi thao tác "kiểm tra rồi sửa" chạy trọn trong một khóa theo user => mã dự phòng/OTP dùng đúng một lần
 *    kể cả khi hai request đến song song.
 */
public final class RecoveryStore {

    public static final int BACKUP_CODE_COUNT = 10;
    public static final int BACKUP_CODE_LENGTH = 12;
    public static final long CODE_TTL_MS = 10 * 60_000L;
    public static final int MAX_CODE_ATTEMPTS = 5;

    // Bỏ 0/O/1/I để không nhầm. 32 ký tự => đúng 5 bit/ký tự, 12 ký tự = 60 bit
    private static final String BACKUP_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final String K_EMAIL = "recoveryEmail";
    private static final String K_BACKUP = "backupHashes";
    private static final String K_BACKUP_AT = "backupGeneratedAt";
    private static final String K_OTP_HASH = "otpHash";
    private static final String K_OTP_EXP = "otpExpires";
    private static final String K_OTP_TRY = "otpAttempts";
    private static final String K_PEND_EMAIL = "pendingEmail";
    private static final String K_PEND_HASH = "pendingHash";
    private static final String K_PEND_EXP = "pendingExpires";
    private static final String K_PEND_TRY = "pendingAttempts";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object[] LOCKS = new Object[64];
    static {
        for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object();
    }

    private static volatile Path baseDir = Paths.get("data", "recovery");
    private static volatile LongSupplier clock = System::currentTimeMillis;

    private RecoveryStore() {}

    public enum CodeResult { OK, INVALID, NONE }

    public static final class Status {
        public final String recoveryEmail;
        public final String pendingEmail;
        public final int backupRemaining;

        Status(String recoveryEmail, String pendingEmail, int backupRemaining) {
            this.recoveryEmail = recoveryEmail;
            this.pendingEmail = pendingEmail;
            this.backupRemaining = backupRemaining;
        }
    }

    public static final class EmailConfirm {
        public final CodeResult result;
        public final String email;          // email vừa được xác minh (chỉ khi OK)
        public final String previousEmail;  // email khôi phục trước đó, "" nếu chưa có

        EmailConfirm(CodeResult result, String email, String previousEmail) {
            this.result = result;
            this.email = email;
            this.previousEmail = previousEmail;
        }
    }

    // ------------------------------------------------------------------ Truy vấn

    public static Status status(String username) {
        synchronized (lockFor(username)) {
            Properties p = load(username);
            String pending = p.getProperty(K_PEND_EMAIL, "");
            if (pending.isEmpty() || now() > longProp(p, K_PEND_EXP)) pending = "";
            return new Status(p.getProperty(K_EMAIL, ""), pending, backupHashes(p).size());
        }
    }

    public static String getRecoveryEmail(String username) {
        synchronized (lockFor(username)) {
            return load(username).getProperty(K_EMAIL, "");
        }
    }

    // ------------------------------------------------------------------ Mã dự phòng

    /** Tạo bộ mã mới, HỦY toàn bộ mã cũ. Trả về mã rõ (chỉ hiển thị một lần), hoặc ném UncheckedIOException nếu không lưu được. */
    public static List<String> regenerateBackupCodes(String username) {
        String user = norm(username);
        List<String> plain = new ArrayList<>(BACKUP_CODE_COUNT);
        List<String> hashes = new ArrayList<>(BACKUP_CODE_COUNT);
        while (plain.size() < BACKUP_CODE_COUNT) {
            StringBuilder sb = new StringBuilder(BACKUP_CODE_LENGTH);
            for (int i = 0; i < BACKUP_CODE_LENGTH; i++) {
                sb.append(BACKUP_ALPHABET.charAt(RANDOM.nextInt(BACKUP_ALPHABET.length())));
            }
            String code = sb.toString();
            if (plain.contains(code)) continue;
            plain.add(code);
            hashes.add(backupHash(user, code));
        }
        synchronized (lockFor(user)) {
            Properties p = load(user);
            p.setProperty(K_BACKUP, String.join(",", hashes));
            p.setProperty(K_BACKUP_AT, String.valueOf(now()));
            save(user, p);
        }
        return plain;
    }

    /** Chấp nhận mã có/không dấu gạch, khoảng trắng, hoa/thường. Đúng => xóa mã đó và trả true (dùng một lần). */
    public static boolean consumeBackupCode(String username, String input) {
        String user = norm(username);
        String code = normalizeBackupInput(input);
        boolean wellFormed = code != null;
        // Luôn tính HMAC (kể cả sai định dạng) để thời gian xử lý không phân biệt
        String candidate = backupHash(user, wellFormed ? code : "-");
        synchronized (lockFor(user)) {
            Properties p = load(user);
            List<String> hashes = backupHashes(p);
            int match = -1;
            for (int i = 0; i < hashes.size(); i++) {      // không thoát sớm
                if (secureEquals(hashes.get(i), candidate) && match < 0) match = i;
            }
            if (!wellFormed || match < 0) return false;
            hashes.remove(match);
            p.setProperty(K_BACKUP, String.join(",", hashes));
            save(user, p);                                   // lỗi ghi => ném, mã chưa bị tiêu
            return true;
        }
    }

    /** Dùng khi tài khoản không tồn tại: tốn cùng một lượng việc băm để không lộ qua thời gian. */
    public static void dummyVerify(String input) {
        hmacHex("otp|dummy|" + (input == null ? "" : input));
    }

    // ------------------------------------------------------------------ OTP đặt lại mật khẩu

    /** Lưu OTP mới (băm), thay thế OTP cũ, đặt lại bộ đếm thử. */
    public static void saveOtp(String username, String otp) {
        String user = norm(username);
        synchronized (lockFor(user)) {
            Properties p = load(user);
            p.setProperty(K_OTP_HASH, otpHash(user, otp));
            p.setProperty(K_OTP_EXP, String.valueOf(now() + CODE_TTL_MS));
            p.setProperty(K_OTP_TRY, "0");
            save(user, p);
        }
    }

    /** OK => OTP bị xóa (dùng một lần). Sai => tăng bộ đếm; đủ MAX_CODE_ATTEMPTS lần sai thì hủy OTP. */
    public static CodeResult consumeOtp(String username, String input) {
        String user = norm(username);
        String cleaned = input == null ? "" : input.replaceAll("\\s+", "");
        String candidate = otpHash(user, cleaned);
        synchronized (lockFor(user)) {
            Properties p = load(user);
            String stored = p.getProperty(K_OTP_HASH, "");
            if (stored.isEmpty()) return CodeResult.NONE;
            if (now() > longProp(p, K_OTP_EXP)) {
                clearOtpProps(p);
                save(user, p);
                return CodeResult.NONE;
            }
            if (secureEquals(stored, candidate)) {
                clearOtpProps(p);
                save(user, p);
                return CodeResult.OK;
            }
            int tries = (int) longProp(p, K_OTP_TRY) + 1;
            if (tries >= MAX_CODE_ATTEMPTS) {
                clearOtpProps(p);
            } else {
                p.setProperty(K_OTP_TRY, String.valueOf(tries));
            }
            save(user, p);
            return CodeResult.INVALID;
        }
    }

    public static void clearOtp(String username) {
        String user = norm(username);
        synchronized (lockFor(user)) {
            Properties p = load(user);
            if (p.getProperty(K_OTP_HASH) == null) return;
            clearOtpProps(p);
            save(user, p);
        }
    }

    // ------------------------------------------------------------------ Email khôi phục (cần xác minh)

    /** Ghi yêu cầu đổi email ở trạng thái CHỜ. Email chỉ thành email khôi phục sau confirmEmail(). */
    public static void startEmailVerification(String username, String email, String code) {
        String user = norm(username);
        String mail = email.trim().toLowerCase(Locale.ROOT);
        synchronized (lockFor(user)) {
            Properties p = load(user);
            p.setProperty(K_PEND_EMAIL, mail);
            p.setProperty(K_PEND_HASH, emailHash(user, mail, code));
            p.setProperty(K_PEND_EXP, String.valueOf(now() + CODE_TTL_MS));
            p.setProperty(K_PEND_TRY, "0");
            save(user, p);
        }
    }

    public static void cancelPendingEmail(String username) {
        String user = norm(username);
        synchronized (lockFor(user)) {
            Properties p = load(user);
            if (p.getProperty(K_PEND_EMAIL) == null) return;
            clearPendingProps(p);
            save(user, p);
        }
    }

    public static EmailConfirm confirmEmail(String username, String input) {
        String user = norm(username);
        String cleaned = input == null ? "" : input.replaceAll("\\s+", "");
        synchronized (lockFor(user)) {
            Properties p = load(user);
            String pendingEmail = p.getProperty(K_PEND_EMAIL, "");
            String stored = p.getProperty(K_PEND_HASH, "");
            if (pendingEmail.isEmpty() || stored.isEmpty()) return new EmailConfirm(CodeResult.NONE, "", "");
            if (now() > longProp(p, K_PEND_EXP)) {
                clearPendingProps(p);
                save(user, p);
                return new EmailConfirm(CodeResult.NONE, "", "");
            }
            String candidate = emailHash(user, pendingEmail, cleaned);
            if (secureEquals(stored, candidate)) {
                String previous = p.getProperty(K_EMAIL, "");
                p.setProperty(K_EMAIL, pendingEmail);
                clearPendingProps(p);
                save(user, p);
                return new EmailConfirm(CodeResult.OK, pendingEmail, previous);
            }
            int tries = (int) longProp(p, K_PEND_TRY) + 1;
            if (tries >= MAX_CODE_ATTEMPTS) {
                clearPendingProps(p);
            } else {
                p.setProperty(K_PEND_TRY, String.valueOf(tries));
            }
            save(user, p);
            return new EmailConfirm(CodeResult.INVALID, "", "");
        }
    }

    // ------------------------------------------------------------------ Nội bộ

    private static String norm(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static long now() {
        return clock.getAsLong();
    }

    private static Object lockFor(String username) {
        return LOCKS[(norm(username).hashCode() & 0x7fffffff) % LOCKS.length];
    }

    private static long longProp(Properties p, String key) {
        try {
            return Long.parseLong(p.getProperty(key, "0").trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static void clearOtpProps(Properties p) {
        p.remove(K_OTP_HASH);
        p.remove(K_OTP_EXP);
        p.remove(K_OTP_TRY);
    }

    private static void clearPendingProps(Properties p) {
        p.remove(K_PEND_EMAIL);
        p.remove(K_PEND_HASH);
        p.remove(K_PEND_EXP);
        p.remove(K_PEND_TRY);
    }

    private static List<String> backupHashes(Properties p) {
        List<String> list = new ArrayList<>();
        String raw = p.getProperty(K_BACKUP, "").trim();
        if (raw.isEmpty()) return list;
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) list.add(t);
        }
        return list;
    }

    /** Chuẩn hóa mã người dùng nhập; null nếu sai định dạng (độ dài/bộ chữ). */
    static String normalizeBackupInput(String input) {
        if (input == null) return null;
        String s = input.replaceAll("[\\s-]+", "").toUpperCase(Locale.ROOT);
        if (s.length() != BACKUP_CODE_LENGTH) return null;
        for (int i = 0; i < s.length(); i++) {
            if (BACKUP_ALPHABET.indexOf(s.charAt(i)) < 0) return null;
        }
        return s;
    }

    private static String backupHash(String user, String code) {
        return hmacHex(user + "|" + code);
    }

    private static String otpHash(String user, String code) {
        return hmacHex("otp|" + user + "|" + code);
    }

    private static String emailHash(String user, String email, String code) {
        return hmacHex("email|" + user + "|" + email + "|" + code);
    }

    private static String hmacHex(String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(ServerConfig.SECRET_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Không tính được HMAC", e);
        }
    }

    private static boolean secureEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Tên file lấy từ SHA-256 của username đã chuẩn hóa: không chứa ký tự nào do client điều khiển. */
    private static Path fileFor(String username) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(norm(username).getBytes(StandardCharsets.UTF_8));
            return baseDir.resolve(hex(d) + ".properties");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Properties load(String username) {
        Properties props = new Properties();
        Path p = fileFor(username);
        if (!Files.exists(p)) return props;
        try (InputStream in = Files.newInputStream(p)) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);   // đóng (fail closed): không coi file hỏng là "chưa có gì"
        }
        return props;
    }

    private static void save(String username, Properties props) {
        try {
            Path dir = baseDir;
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                restrict(dir, true);
            }
            Path target = fileFor(username);
            Path tmp = Files.createTempFile(dir, "rec-", ".tmp");
            try {
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    props.store(out, "Account recovery state");
                }
                restrict(tmp, false);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void restrict(Path path, boolean isDir) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(isDir ? "rwx------" : "rw-------"));
        } catch (Exception ignored) {
            // hệ thống không hỗ trợ POSIX (Windows)
        }
    }

    // ------------------------------------------------------------------ Phục vụ test

    static void setBaseDirForTest(Path dir) {
        baseDir = dir == null ? Paths.get("data", "recovery") : dir;
    }

    static void setClockForTest(LongSupplier testClock) {
        clock = testClock == null ? System::currentTimeMillis : testClock;
    }
}