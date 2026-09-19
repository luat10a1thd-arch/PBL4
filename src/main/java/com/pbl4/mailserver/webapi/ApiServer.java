// ============================================================
// File: ApiServer.java
// Package: com.pbl4.mailserver.webapi
// ------------------------------------------------------------
// Chức năng: Khởi tạo com.sun.net.httpserver.HttpServer, đăng
// ký các route và gắn CorsFilter cho tất cả.
// ============================================================

package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.core.MailStorageEngine;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;

public class ApiServer implements Runnable {

    private final int port;
    private final MailStorageEngine storageEngine;

    public ApiServer(int port, MailStorageEngine storageEngine) {
        this.port = port;
        this.storageEngine = storageEngine;
    }

    @Override
    public void run() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

            AuthHandler authHandler = new AuthHandler(storageEngine);
            MailHandler mailHandler = new MailHandler(storageEngine);
            CorsFilter cors = new CorsFilter();

            server.createContext("/api/register", authHandler::handleRegister).getFilters().add(cors);
            server.createContext("/api/login", authHandler::handleLogin).getFilters().add(cors);
            server.createContext("/api/inbox", mailHandler::handleInbox).getFilters().add(cors);
            server.createContext("/api/send", mailHandler::handleSend).getFilters().add(cors);
            server.createContext("/api/mail", mailHandler::handleDelete).getFilters().add(cors);

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(20));
            server.start();

            System.out.println("[ApiServer] Đang lắng nghe tại port " + port);
        } catch (IOException e) {
            System.err.println("[ApiServer] Lỗi khi mở port " + port + ": " + e.getMessage());
        }
    }
}