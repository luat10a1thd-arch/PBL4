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
import java.util.concurrent.ConcurrentHashMap;

public class SecurityUtils {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int KEY_SIZE = 256;
    private static final int ITERATION_COUNT = 65536;

    // Cache kết quả sinh khóa deriveKey để tăng tốc độ giải mã/mã hóa lên nhiều lần
    private static final ConcurrentHashMap<String, SecretKey> derivedKeyCache = new ConcurrentHashMap<>();

    public static String hashPassword(String plainPassword) {
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(10));
    }

    public static boolean checkPassword(String plainPassword, String hashedPassword) {
        if (hashedPassword == null || !hashedPassword.startsWith("$2a$")) {
            return false;
        }
        return BCrypt.checkpw(plainPassword, hashedPassword);
    }

    public static byte[] generateSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    public static SecretKey deriveKey(String secretKey, byte[] salt) throws Exception {
        String cacheKey = secretKey + "|" + Base64.getEncoder().encodeToString(salt);
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

    public static String encrypt(String plainText, String secretKey, byte[] userSalt) throws Exception {
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        SecretKey key = deriveKey(secretKey, userSalt);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec);

        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(encrypted);
    }

    public static String decrypt(String encryptedData, String secretKey, byte[] userSalt) throws Exception {
        SecretKey key = deriveKey(secretKey, userSalt);
        return decryptWithKey(encryptedData, key);
    }

    public static String decryptWithKey(String encryptedData, SecretKey secretKey) {
        try {
            String[] parts = encryptedData.split(":");
            if (parts.length != 2) return null;

            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] cipherText = Base64.getDecoder().decode(parts[1]);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));

            byte[] decryptedText = cipher.doFinal(cipherText);
            return new String(decryptedText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[SECURITY] Lỗi khi giải mã nội dung thư!");
            return null;
        }
    }
}