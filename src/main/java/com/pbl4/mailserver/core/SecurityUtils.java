package com.pbl4.mailserver.core;

import org.mindrot.jbcrypt.BCrypt;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * Lớp tiện ích quản lý bảo mật:
 * 1. Băm & đối soát mật khẩu bằng BCrypt.
 * 2. Mã hóa & giải mã nội dung email bằng AES-256-CBC.
 */
public class SecurityUtils {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int KEY_SIZE = 256;
    private static final int ITERATION_COUNT = 65536;
    private static final byte[] FIXED_SALT = "PBL4_FIXED_SALT_SECURE_MAIL".getBytes(StandardCharsets.UTF_8);

    // ==========================================
    // 1. XỬ LÝ MẬT KHẨU (BCRYPT)
    // ==========================================

    /**
     * Băm mật khẩu người dùng với BCrypt (tự động tạo Salt)
     */
    public static String hashPassword(String plainPassword) {
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(10));
    }

    /**
     * Đối soát mật khẩu nhập vào với chuỗi băm đã lưu
     */
    public static boolean checkPassword(String plainPassword, String hashedPassword) {
        if (hashedPassword == null || !hashedPassword.startsWith("$2a$")) {
            return false;
        }
        return BCrypt.checkpw(plainPassword, hashedPassword);
    }

    // ==========================================
    // 2. MÃ HÓA & GIẢI MÃ NỘI DUNG MAIL (AES-256-CBC)
    // ==========================================

    /**
     * Sinh khóa SecretKey 256-bit từ một chuỗi passphrase/secret
     */
    private static SecretKey deriveKey(String secretKey) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(secretKey.toCharArray(), FIXED_SALT, ITERATION_COUNT, KEY_SIZE);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    /**
     * Mã hóa chuỗi văn bản bằng AES-256-CBC
     * Trả về định dạng: Base64(IV) + ":" + Base64(Ciphertext)
     */
    public static String encrypt(String plainText, String secretKey) throws Exception {
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        SecretKey key = deriveKey(secretKey);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec);

        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        String base64Iv = Base64.getEncoder().encodeToString(iv);
        String base64Cipher = Base64.getEncoder().encodeToString(encrypted);

        return base64Iv + ":" + base64Cipher;
    }

    /**
     * Giải mã dữ liệu mã hóa về chuỗi văn bản ban đầu
     */
    public static String decrypt(String encryptedData, String secretKey) throws Exception {
        String[] parts = encryptedData.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Định dạng dữ liệu mã hóa không hợp lệ.");
        }

        byte[] iv = Base64.getDecoder().decode(parts[0]);
        byte[] cipherText = Base64.getDecoder().decode(parts[1]);

        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        SecretKey key = deriveKey(secretKey);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, ivSpec);

        byte[] original = cipher.doFinal(cipherText);
        return new String(original, StandardCharsets.UTF_8);
    }
}