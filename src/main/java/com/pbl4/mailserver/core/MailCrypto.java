// FILE: src/main/java/com/pbl4/mailserver/core/MailCrypto.java
package com.pbl4.mailserver.core;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/**
 * Định dạng file thư trên đĩa.
 *
 * GCM (mới, luôn dùng khi ghi):  [0x01][IV 12 byte][bản mã][tag 16 byte]
 *   - AAD = 0x01 || "<hòm thư chữ thường>\n<id file>"  => chép file sang hòm khác/đổi tên là bị phát hiện.
 * CBC (cũ, chỉ để đọc):          base64(IV) ':' base64(bản mã)   (văn bản ASCII, byte đầu là ký tự base64)
 *
 * Byte đầu 0x01 không bao giờ xuất hiện ở file CBC cũ (byte đầu luôn là ký tự base64), nên nhận biết không nhập nhằng.
 * Lớp này không ghi log và không bao giờ đưa nội dung thư vào thông báo lỗi.
 */
public final class MailCrypto {

    public static final byte VERSION_GCM = 0x01;
    public static final int GCM_IV_LEN = 12;
    public static final int GCM_TAG_BITS = 128;
    public static final int GCM_TAG_LEN = 16;
    public static final int GCM_HEADER_LEN = 1 + GCM_IV_LEN;
    /** Số byte file GCM nhiều hơn dữ liệu gốc. */
    public static final int GCM_OVERHEAD = GCM_HEADER_LEN + GCM_TAG_LEN;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String[] SUFFIXES = {"_sent", "_spam", "_drafts", "_trash"};

    /**
     * Cho phép đọc file CBC cũ. Mặc định true để chạy được trước khi chuyển đổi.
     * Sau khi chuyển đổi xong hãy đặt PBL4_ALLOW_LEGACY_CBC=false (chống kẻ ghi được đĩa nhét lại file CBC cũ/không có AAD).
     */
    private static volatile boolean legacyAllowed = readLegacyFlag();

    private MailCrypto() {}

    private static boolean readLegacyFlag() {
        String v = System.getenv("PBL4_ALLOW_LEGACY_CBC");
        return v == null || !"false".equalsIgnoreCase(v.trim());
    }

    public static boolean isLegacyAllowed() {
        return legacyAllowed;
    }

    /** Chỉ dùng cho test. */
    public static void setLegacyAllowed(boolean allowed) {
        legacyAllowed = allowed;
    }

    /** Bộ khóa của MỘT người dùng. Khóa GCM tính ngay khi cần (HKDF rẻ); khóa CBC cũ chỉ tính khi gặp file cũ. */
    public static final class KeyRing {
        private final String secret;
        private final String cleanUser;
        private final byte[] salt;
        private SecretKey gcm;
        private SecretKey legacy;

        public KeyRing(String secret, String cleanUser, byte[] salt) {
            this.secret = secret;
            this.cleanUser = cleanUser;
            this.salt = salt.clone();
        }

        public synchronized SecretKey gcm() throws GeneralSecurityException {
            if (gcm == null) gcm = SecurityUtils.deriveMailKey(secret, salt, cleanUser);
            return gcm;
        }

        public synchronized SecretKey legacy() throws Exception {
            if (legacy == null) legacy = SecurityUtils.deriveKey(secret, salt);
            return legacy;
        }
    }

    /** Tên người dùng gốc (bỏ hậu tố hòm), chữ thường. Dùng làm "info" của HKDF. */
    public static String cleanUserOf(String mailbox) {
        String s = mailbox.trim();
        for (String suf : SUFFIXES) {
            if (s.endsWith(suf)) {
                s = s.substring(0, s.length() - suf.length());
                break;
            }
        }
        return s.toLowerCase(Locale.ROOT);
    }

    /** AAD gắn bản mã với đúng hòm thư và đúng id file. */
    public static byte[] aad(String mailbox, String fileId) {
        return (mailbox.trim().toLowerCase(Locale.ROOT) + "\n" + fileId).getBytes(StandardCharsets.UTF_8);
    }

    public static boolean isGcm(byte[] file) {
        return file != null && file.length > 0 && file[0] == VERSION_GCM;
    }

    public static byte[] encryptGcm(byte[] plain, SecretKey key, byte[] aad) throws GeneralSecurityException {
        byte[] iv = new byte[GCM_IV_LEN];
        RANDOM.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        c.updateAAD(new byte[]{VERSION_GCM});
        c.updateAAD(aad);
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[GCM_HEADER_LEN + ct.length];
        out[0] = VERSION_GCM;
        System.arraycopy(iv, 0, out, 1, GCM_IV_LEN);
        System.arraycopy(ct, 0, out, GCM_HEADER_LEN, ct.length);
        return out;
    }

    private static byte[] decryptGcm(byte[] file, SecretKey key, byte[] aad) throws GeneralSecurityException {
        if (file.length < GCM_OVERHEAD) throw new GeneralSecurityException("short");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, file, 1, GCM_IV_LEN));
        c.updateAAD(new byte[]{VERSION_GCM});
        c.updateAAD(aad);
        return c.doFinal(file, GCM_HEADER_LEN, file.length - GCM_HEADER_LEN);
    }

    private static byte[] decryptLegacyCbc(byte[] fileBytes, SecretKey key) throws Exception {
        int colon = -1;
        for (int i = 0; i < fileBytes.length; i++) {
            if (fileBytes[i] == ':') { colon = i; break; }
        }
        if (colon <= 0) return null;
        int end = fileBytes.length;
        while (end > colon + 1 && isAsciiSpace(fileBytes[end - 1])) end--;
        byte[] iv = Base64.getDecoder().decode(Arrays.copyOfRange(fileBytes, 0, colon));
        byte[] ct = Base64.getDecoder().decode(Arrays.copyOfRange(fileBytes, colon + 1, end));
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
        return cipher.doFinal(ct);
    }

    private static boolean isAsciiSpace(byte b) {
        return b == '\r' || b == '\n' || b == ' ' || b == '\t';
    }

    /** Giải mã theo cờ legacyAllowed toàn cục. Trả null nếu sai khóa, bị sửa, bị tráo hòm/id hoặc file hỏng. */
    public static byte[] decrypt(byte[] file, KeyRing ring, byte[] aad) {
        return decrypt(file, ring, aad, legacyAllowed);
    }

    public static byte[] decrypt(byte[] file, KeyRing ring, byte[] aad, boolean allowLegacy) {
        if (file == null || file.length == 0) return null;
        try {
            if (isGcm(file)) return decryptGcm(file, ring.gcm(), aad);
            if (!allowLegacy) return null;
            return decryptLegacyCbc(file, ring.legacy());
        } catch (Exception e) {
            return null; // cố ý không đính kèm thông điệp lỗi
        }
    }

    /**
     * Giải mã KHÔNG xác thực phần đầu của một file GCM (dùng CTR, đếm bắt đầu từ 2 đúng như GCM với IV 96 bit)
     * để đọc tiêu đề thư khi file lớn mà không phải đọc cả file. CHỈ dùng cho danh sách tóm tắt;
     * nội dung thư luôn đi qua decrypt() có xác thực. Điều kiện: buf chứa phần ĐẦU file và len < độ dài file - 16
     * (không chứa tag).
     */
    public static byte[] peekGcmPrefix(byte[] buf, int len, SecretKey key) {
        try {
            if (!isGcm(buf) || len <= GCM_HEADER_LEN) return null;
            byte[] ctr = new byte[16];
            System.arraycopy(buf, 1, ctr, 0, GCM_IV_LEN);
            ctr[15] = 2;
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(ctr));
            return c.doFinal(buf, GCM_HEADER_LEN, len - GCM_HEADER_LEN);
        } catch (Exception e) {
            return null;
        }
    }
}