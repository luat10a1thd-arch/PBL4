package com.pbl4.mailserver;

import com.pbl4.mailserver.pop3.Pop3ServerManager;
import com.pbl4.mailserver.smtp.SmtpServerManager;
import com.pbl4.mailserver.webapi.ApiServer;

public class MainApplication {
    public static void main(String[] args) {
        System.out.println("=== Đang khởi động DUT Secure Mail Server ===");
        
        // 1. Web API Server (8080)
        ApiServer.startServer();

        // 2. SMTP Socket Server (2525)
        SmtpServerManager.startServer();

        // 3. POP3 Socket Server (1110)
        Pop3ServerManager.startServer();
    }
}