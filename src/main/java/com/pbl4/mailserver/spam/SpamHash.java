// FILE: src/main/java/com/pbl4/mailserver/spam/SpamHash.java
package com.pbl4.mailserver.spam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Băm SHA-256 có muối riêng của lần cài đặt (data/spam/salt, 32 byte ngẫu nhiên, ghi nguyên tử, quyền 600).
 * Dùng để ẩn danh người gửi/người báo cáo: không lưu tên hay địa chỉ thật. Không đọc đĩa mỗi lần băm (muối được cache).
 */
final class SpamHash {
    private SpamHash() {
    }

    private static final Object LOCK = new Object();
    private static byte[] salt;
    private static Path loadedFor;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static byte[] salt() {
        synchronized (LOCK) {
            Path base = SpamEnv.baseDir().toAbsolutePath().normalize();
            if (salt != null && base.equals(loadedFor)) return salt;
            Path file = base.resolve("salt");
            byte[] s = null;
            try {
                byte[] b = SpamStore.readLimited(file);
                if (b != null) s = parseHex(new String(b, StandardCharsets.ISO_8859_1).trim());
            } catch (IOException | RuntimeException ignored) {
                // sẽ tạo muối mới
            }
            if (s == null || s.length != 32) {
                s = new byte[32];
                new SecureRandom().nextBytes(s);
                try {
                    SpamStore.writeAtomic(file, toHex(s, 32).getBytes(StandardCharsets.ISO_8859_1));
                } catch (IOException | RuntimeException ignored) {
                    // không ghi được: vẫn dùng muối trong RAM (mất sau khi khởi động lại, không ảnh hưởng việc giao thư)
                }
            }
            salt = s;
            loadedFor = base;
            return s;
        }
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] salted(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt());
            md.update((byte) 0);
            md.update(s.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 64 bit đầu của SHA-256(muối + chuỗi). Chỉ dùng trong RAM. */
    static long id64(String s) {
        byte[] d = salted(s);
        return toLong(d, 0);
    }

    /** 128 bit đầu của SHA-256(muối + chuỗi), dạng 32 ký tự hex. Dùng để lưu đĩa. */
    static String hex(String s) {
        return toHex(salted(s), 16);
    }

    static long toLong(byte[] d, int off) {
        long r = 0;
        for (int i = 0; i < 8; i++) r = (r << 8) | (d[off + i] & 0xFFL);
        return r;
    }

    private static String toHex(byte[] d, int n) {
        char[] c = new char[n * 2];
        for (int i = 0; i < n; i++) {
            c[i * 2] = HEX[(d[i] >> 4) & 0xF];
            c[i * 2 + 1] = HEX[d[i] & 0xF];
        }
        return new String(c);
    }

    private static byte[] parseHex(String h) {
        if (h == null || h.length() != 64) return null;
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            int hi = Character.digit(h.charAt(i * 2), 16);
            int lo = Character.digit(h.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    static boolean isHex32(String s) {
        if (s == null || s.length() != 32) return false;
        for (int i = 0; i < 32; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /** Chỉ cho test: buộc nạp lại muối từ file. */
    static void resetForTest() {
        synchronized (LOCK) {
            salt = null;
            loadedFor = null;
        }
    }
}