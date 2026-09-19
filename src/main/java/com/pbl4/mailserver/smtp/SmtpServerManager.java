// ============================================================
// File: SmtpServerManager.java
// Package: com.pbl4.mailserver.smtp
// ------------------------------------------------------------
// Chức năng: Mở ServerSocket lắng nghe port SMTP, dùng thread
// pool (tối đa 100 luồng theo đúng thiết kế) để xử lý nhiều
// client đồng thời.
// ============================================================

package com.pbl4.mailserver.smtp;

import com.pbl4.mailserver.core.MailStorageEngine;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SmtpServerManager implements Runnable {

    private final int port;
    private final MailStorageEngine storageEngine;
    private final ExecutorService threadPool;

    public SmtpServerManager(int port, MailStorageEngine storageEngine) {
        this.port = port;
        this.storageEngine = storageEngine;
        this.threadPool = Executors.newFixedThreadPool(100);
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[SmtpServerManager] Đang lắng nghe tại port " + port);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[SmtpServerManager] Client mới: " + clientSocket.getInetAddress());
                threadPool.execute(new SmtpHandler(clientSocket, storageEngine));
            }
        } catch (IOException e) {
            System.err.println("[SmtpServerManager] Lỗi khi mở port " + port + ": " + e.getMessage());
        }
    }
}