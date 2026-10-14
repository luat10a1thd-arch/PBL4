package com.pbl4.mailserver;

import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.SecurityUtils;
import com.pbl4.mailserver.core.UserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CryptoEngineTest {

    private final String testUser = "user1@pbl4.com";

    @BeforeEach
    public void setUp() {
        if (!UserStore.exists(testUser)) {
            UserStore.addUser(testUser, SecurityUtils.hashPassword("123456"));
        }
    }

    @Test
    @DisplayName("Test băm và kiểm tra mật khẩu BCrypt")
    public void testBCryptPassword() {
        String pass = "123456";
        String hash = SecurityUtils.hashPassword(pass);

        assertNotNull(hash, "Chuỗi hash không được null");
        assertTrue(SecurityUtils.checkPassword("123456", hash), "Mật khẩu đúng phải kiểm tra thành công");
        assertFalse(SecurityUtils.checkPassword("sai_mat_khau", hash), "Mật khẩu sai phải thất bại");
    }

    @Test
    @DisplayName("Test Mail Storage Engine (Ghi và đọc email mã hóa AES)")
    public void testMailStorageEngine() {
        String rawMail = "From: admin@pbl4.com\nTo: " + testUser + "\nSubject: Chào mừng!\n\nĐây là email mã hóa AES-256 lưu xuống đĩa.";

        boolean saveSuccess = MailStorageEngine.saveEmail(testUser, rawMail);
        assertTrue(saveSuccess, "Ghi mail mã hóa xuống đĩa phải thành công");

        List<String> userEmails = MailStorageEngine.readAllEmails(testUser);
        assertNotNull(userEmails, "Danh sách email không được null");
        assertFalse(userEmails.isEmpty(), "Danh sách email không được rỗng sau khi đã ghi");
    }
}