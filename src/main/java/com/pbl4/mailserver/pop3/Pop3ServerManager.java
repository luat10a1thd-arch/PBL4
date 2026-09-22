package com.pbl4.mailserver.pop3;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Quản lý lắng nghe cổng 1110 cho dịch vụ POP3.
 * Tối ưu hóa hiệu năng bằng Thread Pool Executor.
 */
public class Pop3ServerManager {

    private static final int POP3_PORT = 1110;
    private static final ExecutorService threadPool = Executors.newFixedThreadPool(20);

    public static void startServer() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(POP3_PORT)) {
                System.out.println("=== POP3 Server (Thread Pool) đang chạy tại cổng: " + POP3_PORT + " ===");
                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    threadPool.execute(new Pop3Handler(clientSocket));
                }
            } catch (IOException e) {
                System.err.println("Lỗi khởi chạy POP3 Server: " + e.getMessage());
            }
        }).start();
    }
}