package com.pbl4.mailserver.smtp;

import com.pbl4.mailserver.config.ServerConfig;

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
            try {
                serverSocket = new ServerSocket(SMTP_PORT);
                System.out.println("=== SMTP Server running on port: " + SMTP_PORT + " ===");

                while (isRunning && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        threadPool.execute(new SmtpHandler(clientSocket));
                    } catch (IOException e) {
                        if (!isRunning || serverSocket.isClosed()) break;
                    }
                }
            } catch (IOException e) {
                System.err.println("SMTP Server error: " + e.getMessage());
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