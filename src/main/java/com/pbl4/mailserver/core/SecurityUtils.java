package com.pbl4.mailserver.core;

import org.mindrot.jbcrypt.BCrypt;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

public class SecurityUtils {

    private static final int KEY_SIZE = 256;
    private static final int ITERATION_COUNT = 65536;

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
}
