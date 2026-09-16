package com.pbl4.mailserver;

import com.pbl4.mailserver.webapi.ApiServer;

public class MainApplication {
    public static void main(String[] args) {
        System.out.println("=== Đang khởi động PBL4 Secure Mail Server ===");
        
        // Khởi chạy Web Server lắng nghe cổng 8080
        ApiServer.startServer();
    }
}