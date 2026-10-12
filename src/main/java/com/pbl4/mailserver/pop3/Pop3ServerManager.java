package com.pbl4.mailserver.pop3;

import com.pbl4.mailserver.core.SslContextFactory;

import javax.net.ssl.SSLServerSocketFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Quản lý lắng nghe cổng 1110 cho dịch vụ POP3.
 * Tối ưu hóa hiệu năng bằng Thread Pool Executor và hỗ trợ SSL/TLS (Mục 8.4).
 */
public class Pop3ServerManager {

    private static final int POP3_PORT = 1110;
    // Giới hạn tối đa 20 luồng xử lý đồng thời
    private static final ExecutorService threadPool = Executors.newFixedThreadPool(20);

    public static void startServer() {
        new Thread(() -> {
            SSLServerSocketFactory sslFactory = SslContextFactory.createSSLServerSocketFactory();
            ServerSocket serverSocket = null;

            try {
                if (sslFactory != null) {
                    serverSocket = sslFactory.createServerSocket(POP3_PORT);
                    System.out.println("=== POP3 Server (SSL/TLS Thread Pool) đang chạy tại cổng: " + POP3_PORT + " ===");
                } else {
                    serverSocket = new ServerSocket(POP3_PORT);
                    System.out.println("=== POP3 Server (Thread Pool) đang chạy tại cổng: " + POP3_PORT + " ===");
                }

                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    // Đẩy task vào Thread Pool quản lý
                    threadPool.execute(new Pop3Handler(clientSocket));
                }
            } catch (IOException e) {
                System.err.println("Lỗi khởi chạy POP3 Server: " + e.getMessage());
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