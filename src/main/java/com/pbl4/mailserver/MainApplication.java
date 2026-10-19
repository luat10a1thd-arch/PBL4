package com.pbl4.mailserver;

import com.pbl4.mailserver.core.UserStore;
import com.pbl4.mailserver.pop3.Pop3ServerManager;
import com.pbl4.mailserver.smtp.SmtpServerManager;
import com.pbl4.mailserver.webapi.ApiServer;

public class MainApplication {
    public static void main(String[] args) {
        System.out.println("=== Đang khởi động DUT Secure Mail Server ===");

        UserStore.initDefaultUsers();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n=== Đang tắt Mail Server an toàn... ===");
            SmtpServerManager.stopServer();
            Pop3ServerManager.stopServer();
            System.out.println("=== Đã đóng tất cả Socket và ThreadPool thành công! ===");
        }));

        ApiServer.startServer();
        SmtpServerManager.startServer();
        Pop3ServerManager.startServer();
    }
}