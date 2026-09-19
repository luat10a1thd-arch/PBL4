// ============================================================
// File: Pop3ServerManager.java
// Package: com.pbl4.mailserver.pop3
// ------------------------------------------------------------
// Chức năng: Mở ServerSocket lắng nghe port POP3, dùng thread
// pool tối đa 100 luồng.
// ============================================================

package com.pbl4.mailserver.pop3;

import com.pbl4.mailserver.core.MailStorageEngine;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Pop3ServerManager implements Runnable {

    private final int port;
    private final MailStorageEngine storageEngine;
    private final ExecutorService threadPool;

    public Pop3ServerManager(int port, MailStorageEngine storageEngine) {
        this.port = port;
        this.storageEngine = storageEngine;
        this.threadPool = Executors.newFixedThreadPool(100);
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[Pop3ServerManager] Đang lắng nghe tại port " + port);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[Pop3ServerManager] Client mới: " + clientSocket.getInetAddress());
                threadPool.execute(new Pop3Handler(clientSocket, storageEngine));
            }
        } catch (IOException e) {
            System.err.println("[Pop3ServerManager] Lỗi khi mở port " + port + ": " + e.getMessage());
        }
    }
}