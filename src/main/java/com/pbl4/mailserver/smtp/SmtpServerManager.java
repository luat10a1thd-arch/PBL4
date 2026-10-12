package com.pbl4.mailserver.smtp;

import com.pbl4.mailserver.core.SslContextFactory;

import javax.net.ssl.SSLServerSocketFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Quản lý lắng nghe cổng 2525 cho dịch vụ SMTP.
 * Tối ưu hóa hiệu năng bằng Thread Pool Executor và hỗ trợ SSL/TLS (Mục 8.4).
 */
public class SmtpServerManager {

    private static final int SMTP_PORT = 2525;
    // Giới hạn tối đa 20 luồng xử lý đồng thời để tối ưu tài nguyên HĐH
    private static final ExecutorService threadPool = Executors.newFixedThreadPool(20);

    public static void startServer() {
        new Thread(() -> {
            SSLServerSocketFactory sslFactory = SslContextFactory.createSSLServerSocketFactory();
            ServerSocket serverSocket = null;

            try {
                if (sslFactory != null) {
                    serverSocket = sslFactory.createServerSocket(SMTP_PORT);
                    System.out.println("=== SMTP Server (SSL/TLS Thread Pool) đang chạy tại cổng: " + SMTP_PORT + " ===");
                } else {
                    serverSocket = new ServerSocket(SMTP_PORT);
                    System.out.println("=== SMTP Server (Thread Pool) đang chạy tại cổng: " + SMTP_PORT + " ===");
                }

                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    // Đẩy task vào Thread Pool quản lý thay vì new Thread() thủ công
                    threadPool.execute(new SmtpHandler(clientSocket));
                }
            } catch (IOException e) {
                System.err.println("Lỗi khởi chạy SMTP Server: " + e.getMessage());
            } finally {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    try {
                        serverSocket.close();
                    } catch (IOException ignored) {}
                }
            }
        }).start();
    }
}