// ============================================================
// File: MainApplication.java
// Package: com.pbl4.mailserver
// ------------------------------------------------------------
// Chức năng: Master Entry Point - khởi chạy đồng thời SMTP,
// POP3, và WebAPI server trên các thread riêng.
// ============================================================

package com.pbl4.mailserver;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.pop3.Pop3ServerManager;
import com.pbl4.mailserver.smtp.SmtpServerManager;
import com.pbl4.mailserver.webapi.ApiServer;

public class MainApplication {

    public static void main(String[] args) {
        System.out.println("==========================================");
        System.out.println("   DỰ ÁN HỆ THỐNG MAIL SERVER (PBL4)");
        System.out.println("==========================================");

        MailStorageEngine storageEngine = new MailStorageEngine();

        int smtpPort = ServerConfig.getSmtpPort();
        int pop3Port = ServerConfig.getPop3Port();
        int apiPort = 8080; // Cổng WebAPI - có thể thêm vào ServerConfig sau nếu cần

        new Thread(new SmtpServerManager(smtpPort, storageEngine)).start();
        new Thread(new Pop3ServerManager(pop3Port, storageEngine)).start();
        new Thread(new ApiServer(apiPort, storageEngine)).start();

        System.out.println("[MainApplication] Toàn bộ hệ thống đã khởi chạy!");
    }
}