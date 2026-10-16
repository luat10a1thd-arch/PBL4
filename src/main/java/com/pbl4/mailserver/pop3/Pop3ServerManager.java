package com.pbl4.mailserver.pop3;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.SslContextFactory;

import javax.net.ssl.SSLServerSocketFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Pop3ServerManager {

    private static final int POP3_PORT = ServerConfig.POP3_PORT;
    private static final ExecutorService threadPool = Executors.newFixedThreadPool(ServerConfig.THREAD_POOL_SIZE);
    private static volatile ServerSocket serverSocket;
    private static volatile boolean isRunning = true;

    public static void startServer() {
        new Thread(() -> {
            SSLServerSocketFactory sslFactory = SslContextFactory.createSSLServerSocketFactory();

            try {
                if (sslFactory != null) {
                    serverSocket = sslFactory.createServerSocket(POP3_PORT);
                    System.out.println("=== POP3 Server (SSL/TLS Thread Pool) đang chạy tại cổng: " + POP3_PORT + " ===");
                } else {
                    serverSocket = new ServerSocket(POP3_PORT);
                    System.out.println("=== POP3 Server (Thread Pool) đang chạy tại cổng: " + POP3_PORT + " ===");
                }

                while (isRunning && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        threadPool.execute(new Pop3Handler(clientSocket));
                    } catch (IOException e) {
                        if (!isRunning || serverSocket.isClosed()) break;
                        System.err.println("[POP3] Lỗi chấp nhận kết nối client: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                System.err.println("Lỗi khởi chạy POP3 Server: " + e.getMessage());
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