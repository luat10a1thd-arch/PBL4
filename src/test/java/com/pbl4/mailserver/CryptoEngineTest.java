package com.pbl4.mailserver;

import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.SecurityUtils;
import java.util.List;

public class CryptoEngineTest {
    public static void main(String[] args) {
        try {
            System.out.println("=== 1. TEST BCRYPT PASSWORD ===");
            String pass = "123456";
            String hash = SecurityUtils.hashPassword(pass);
            System.out.println("Password Hash: " + hash);
            System.out.println("Check đúng: " + SecurityUtils.checkPassword("123456", hash));

            System.out.println("\n=== 2. TEST MAIL STORAGE ENGINE (GHI FILE MÃ HÓA) ===");
            String testUser = "user1@pbl4.com";
            String rawMail = "Tiêu đề: Chào mừng!\nNội dung: Đây là email mã hóa AES-256 lưu xuống đĩa.";

            boolean saveSuccess = MailStorageEngine.saveEmail(testUser, rawMail);
            System.out.println("Ghi mail mã hóa thành công: " + saveSuccess);

            System.out.println("\n=== 3. TEST MAIL STORAGE ENGINE (ĐỌC & GIẢI MÃ FILE) ===");
            List<String> userEmails = MailStorageEngine.readAllEmails(testUser);
            System.out.println("Số lượng email tìm thấy: " + userEmails.size());
            for (int i = 0; i < userEmails.size(); i++) {
                System.out.println("--- Email " + (i + 1) + " ---");
                System.out.println(userEmails.get(i));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}