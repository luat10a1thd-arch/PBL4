package com.pbl4.mailserver.smtp;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.SslContextFactory;

import javax.net.ssl.SSLServerSocketFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SmtpServerManager {

    private static final int SMTP_PORT = ServerConfig.SMTP_PORT;
    private static final ExecutorService threadPool = Executors.newFixedThreadPool(ServerConfig.THREAD_POOL_SIZE);
    private static volatile ServerSocket serverSocket;
    private static volatile boolean isRunning = true;

    public static void startServer() {
        new Thread(() -> {
            SSLServerSocketFactory sslFactory = SslContextFactory.createSSLServerSocketFactory();

            try {
                if (sslFactory != null) {
                    serverSocket = sslFactory.createServerSocket(SMTP_PORT);
                    System.out.println("=== SMTP Server (SSL/TLS Thread Pool) đang chạy tại cổng: " + SMTP_PORT + " ===");
                } else {
                    serverSocket = new ServerSocket(SMTP_PORT);
                    System.out.println("=== SMTP Server (Thread Pool) đang chạy tại cổng: " + SMTP_PORT + " ===");
                }

                while (isRunning && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        threadPool.execute(new SmtpHandler(clientSocket));
                    } catch (IOException e) {
                        if (!isRunning || serverSocket.isClosed()) break;
                        System.err.println("[SMTP] Lỗi chấp nhận kết nối client: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                System.err.println("Lỗi khởi chạy SMTP Server: " + e.getMessage());
            } finally {
                stopServer();
            }
        }).start();
    }

    public static void stopServer() {
        isRunning = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(3, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
        }
    }
}