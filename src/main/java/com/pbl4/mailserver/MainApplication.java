// FILE: src/main/java/com/pbl4/mailserver/MainApplication.java
package com.pbl4.mailserver;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.UserStore;
import com.pbl4.mailserver.pop3.Pop3ServerManager;
import com.pbl4.mailserver.smtp.MxHandler;
import com.pbl4.mailserver.smtp.SmtpListener;
import com.pbl4.mailserver.smtp.SmtpServerManager;
import com.pbl4.mailserver.smtp.SubmissionHandler;
import com.pbl4.mailserver.webapi.ApiServer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

public class MainApplication {

    // Các listener đã khởi động thành công, để tắt gọn trong shutdown hook
    private static final List<SmtpListener> LISTENERS = new CopyOnWriteArrayList<>();

    public static void main(String[] args) {
        System.out.println("=== Đang khởi động DUT Secure Mail Server ===");

        // Truy cập ServerConfig sớm: nạp lớp sẽ kiểm tra cấu hình PRODUCTION (thiếu thì thoát mã 1)
        ServerConfig.validateProduction();
        System.out.println("Chế độ: " + (ServerConfig.PRODUCTION ? "PRODUCTION" : "DEV (PRODUCTION=false)")
                + " | domain=" + ServerConfig.MAIL_DOMAIN + " | hostname=" + ServerConfig.MAIL_HOSTNAME);

        UserStore.initDefaultUsers();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n=== Đang tắt Mail Server an toàn... ===");
            for (SmtpListener l : LISTENERS) {
                try {
                    l.stop();
                } catch (Exception e) {
                    System.err.println("[SHUTDOWN] Lỗi khi tắt SMTP listener: " + e.getMessage());
                }
            }
            try {
                SmtpServerManager.stopServer();
            } catch (Exception e) {
                System.err.println("[SHUTDOWN] Lỗi khi tắt SmtpServerManager: " + e.getMessage());
            }
            try {
                Pop3ServerManager.stopServer();
            } catch (Exception e) {
                System.err.println("[SHUTDOWN] Lỗi khi tắt Pop3ServerManager: " + e.getMessage());
            }
            System.out.println("=== Đã đóng tất cả Socket và ThreadPool thành công! ===");
        }));

        ApiServer.startServer();

        // Cổng SMTP cũ (SMTP_PORT, mặc định 2525) cho dev/test
        try {
            SmtpServerManager.startServer();
        } catch (Exception e) {
            System.err.println("[LỖI] Không khởi động được SMTP cổng " + ServerConfig.SMTP_PORT + ": " + e.getMessage() + " (tiếp tục các cổng khác)");
        }

        // Các cổng mới của CONTRACT v2: mỗi listener bọc try/catch riêng
        startListener("mx", ServerConfig.MX_PORT, false, MxHandler::new);
        startListener("submission", ServerConfig.SUBMISSION_PORT, false, SubmissionHandler::new);
        if (ServerConfig.SMTPS_PORT > 0) {
            startListener("smtps", ServerConfig.SMTPS_PORT, true, SubmissionHandler::new);
        }

        Pop3ServerManager.startServer();
    }

    private static void startListener(String name, int port, boolean implicitTls,
                                      Supplier<SmtpListener.SessionHandler> factory) {
        try {
            SmtpListener listener = new SmtpListener(name, port, implicitTls, factory);
            listener.start();
            LISTENERS.add(listener);
            System.out.println("[SMTP] Listener '" + name + "' đang chạy ở cổng " + port + (implicitTls ? " (TLS ngầm định)" : ""));
        } catch (Exception e) {
            System.err.println("[LỖI] Listener '" + name + "' không khởi động được ở cổng " + port + ": " + e.getMessage()
                    + " (ví dụ cổng <1024 cần quyền root/CAP_NET_BIND_SERVICE). Tiếp tục chạy các cổng khác.");
        }
    }
}