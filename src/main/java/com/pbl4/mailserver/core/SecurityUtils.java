package com.pbl4.mailserver.core;

import org.mindrot.jbcrypt.BCrypt;
import com.pbl4.mailserver.config.ServerConfig;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

public class SecurityUtils {

    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 16;
    private static final int KEY_LENGTH = 256;
    private static final int PBKDF2_ITERATIONS = 65536;

    public static byte[] encryptAES(String plainText) {
        try {
            SecureRandom random = new SecureRandom();

            byte[] salt = new byte[SALT_LENGTH];
            random.nextBytes(salt);

            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            SecretKeySpec key = deriveKey(salt);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] result = new byte[SALT_LENGTH + IV_LENGTH + cipherText.length];
            System.arraycopy(salt, 0, result, 0, SALT_LENGTH);
            System.arraycopy(iv, 0, result, SALT_LENGTH, IV_LENGTH);
            System.arraycopy(cipherText, 0, result, SALT_LENGTH + IV_LENGTH, cipherText.length);

            return result;
        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi mã hóa AES", e);
        }
    }

    public static String decryptAES(byte[] encryptedData) {
        try {
            byte[] salt = Arrays.copyOfRange(encryptedData, 0, SALT_LENGTH);
            byte[] iv = Arrays.copyOfRange(encryptedData, SALT_LENGTH, SALT_LENGTH + IV_LENGTH);
            byte[] cipherText = Arrays.copyOfRange(encryptedData, SALT_LENGTH + IV_LENGTH, encryptedData.length);

            SecretKeySpec key = deriveKey(salt);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
            byte[] plainBytes = cipher.doFinal(cipherText);

            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi giải mã AES (dữ liệu hỏng hoặc sai khóa)", e);
        }
    }

    private static SecretKeySpec deriveKey(byte[] salt) throws Exception {
        String masterSecret = ServerConfig.getMasterSecretKey();

        PBEKeySpec spec = new PBEKeySpec(
                masterSecret.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();

        return new SecretKeySpec(keyBytes, "AES");
    }

    public static String hashPassword(String plainPassword) {
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt());
    }

    public static boolean checkPassword(String plainPassword, String hashedPassword) {
        return BCrypt.checkpw(plainPassword, hashedPassword);
    }
}