// ============================================================
// File: Pop3Handler.java
// Package: com.pbl4.mailserver.pop3
// ------------------------------------------------------------
// Chức năng: Trình phân tích cú pháp RFC 1939 & State Machine
// với 3 trạng thái: AUTHORIZATION -> TRANSACTION -> UPDATE.
// Khi nhận RETR, gọi MailStorageEngine.readMail() để đọc file
// .enc và giải mã AES-256 trả về cho client.
// ============================================================

package com.pbl4.mailserver.pop3;

import com.pbl4.mailserver.core.MailStorageEngine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.List;

public class Pop3Handler implements Runnable {

    private enum State { AUTHORIZATION, TRANSACTION }

    private final Socket socket;
    private final MailStorageEngine storageEngine;

    private State state = State.AUTHORIZATION;
    private String pendingUsername; // đã gõ USER, chờ PASS
    private String authenticatedUser;
    private List<String> mailFiles; // danh sách file .enc của user, nạp khi vào TRANSACTION

    public Pop3Handler(Socket socket, MailStorageEngine storageEngine) {
        this.socket = socket;
        this.storageEngine = storageEngine;
    }

    @Override
    public void run() {
        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            out.println("+OK MailServer POP3 Ready");

            String line;
            while ((line = in.readLine()) != null) {
                System.out.println("[POP3] Nhận: " + line);
                String[] parts = line.trim().split("\\s+", 2);
                String command = parts[0].toUpperCase();
                String arg = (parts.length > 1) ? parts[1] : "";

                switch (command) {
                    case "USER" -> handleUser(arg, out);
                    case "PASS" -> handlePass(arg, out);
                    case "STAT" -> handleStat(out);
                    case "LIST" -> handleList(out);
                    case "RETR" -> handleRetr(arg, out);
                    case "DELE" -> handleDele(arg, out);
                    case "RSET" -> handleRset(out);
                    case "NOOP" -> out.println("+OK");
                    case "QUIT" -> {
                        handleQuit(out);
                        return; // đóng kết nối luôn sau QUIT
                    }
                    default -> out.println("-ERR Command not recognized");
                }
            }
        } catch (IOException e) {
            System.err.println("[Pop3Handler] Lỗi kết nối: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ------------------------------------------------------------
    // AUTHORIZATION phase
    // ------------------------------------------------------------
    private void handleUser(String username, PrintWriter out) {
        if (state != State.AUTHORIZATION) {
            out.println("-ERR Command not valid in this state");
            return;
        }
        pendingUsername = username;
        out.println("+OK User accepted, send PASS");
    }

    private void handlePass(String password, PrintWriter out) {
        if (state != State.AUTHORIZATION || pendingUsername == null) {
            out.println("-ERR Command not valid in this state");
            return;
        }
        if (storageEngine.authenticate(pendingUsername, password)) {
            authenticatedUser = pendingUsername;
            state = State.TRANSACTION;
            mailFiles = storageEngine.listMailFiles(authenticatedUser);
            out.println("+OK Mailbox open, " + mailFiles.size() + " messages");
        } else {
            out.println("-ERR Authentication failed");
            pendingUsername = null;
        }
    }

    // ------------------------------------------------------------
    // TRANSACTION phase
    // ------------------------------------------------------------
    private void handleStat(PrintWriter out) {
        if (!requireTransaction(out)) return;
        long totalSize = 0;
        for (String f : mailFiles) totalSize += storageEngine.getMailSize(authenticatedUser, f);
        out.println("+OK " + mailFiles.size() + " " + totalSize);
    }

    private void handleList(PrintWriter out) {
        if (!requireTransaction(out)) return;
        out.println("+OK " + mailFiles.size() + " messages");
        for (int i = 0; i < mailFiles.size(); i++) {
            long size = storageEngine.getMailSize(authenticatedUser, mailFiles.get(i));
            // Message ID = số thứ tự (1-based) theo đúng chuẩn POP3
            out.println((i + 1) + " " + size);
        }
        out.println(".");
    }

    private void handleRetr(String arg, PrintWriter out) {
        if (!requireTransaction(out)) return;
        int index = parseMessageId(arg);
        if (index < 0 || index >= mailFiles.size()) {
            out.println("-ERR No such message");
            return;
        }
        String fileName = mailFiles.get(index);
        String content = storageEngine.readMail(authenticatedUser, fileName);
        if (content == null) {
            out.println("-ERR Error reading message");
            return;
        }
        long size = storageEngine.getMailSize(authenticatedUser, fileName);
        out.println("+OK " + size + " octets");
        // Gửi nội dung, kết thúc bằng dòng chỉ có dấu "."
        for (String contentLine : content.split("\n")) {
            if (contentLine.startsWith(".")) contentLine = "." + contentLine; // dot-stuffing
            out.println(contentLine);
        }
        out.println(".");
    }

    private void handleDele(String arg, PrintWriter out) {
        if (!requireTransaction(out)) return;
        int index = parseMessageId(arg);
        if (index < 0 || index >= mailFiles.size()) {
            out.println("-ERR No such message");
            return;
        }
        storageEngine.markForDeletion(authenticatedUser, mailFiles.get(index));
        out.println("+OK Message deleted");
    }

    private void handleRset(PrintWriter out) {
        if (!requireTransaction(out)) return;
        storageEngine.unmarkAllDeletions(authenticatedUser);
        out.println("+OK");
    }

    // ------------------------------------------------------------
    // UPDATE phase - xảy ra khi QUIT
    // ------------------------------------------------------------
    private void handleQuit(PrintWriter out) {
        if (state == State.TRANSACTION) {
            storageEngine.purgeMarkedDeletions(authenticatedUser);
        }
        out.println("+OK MailServer signing off");
    }

    // ------------------------------------------------------------
    // Hàm phụ trợ
    // ------------------------------------------------------------
    private boolean requireTransaction(PrintWriter out) {
        if (state != State.TRANSACTION) {
            out.println("-ERR Not authenticated");
            return false;
        }
        return true;
    }

    // Message ID trong lệnh của client là 1-based (VD "RETR 1" = mail đầu tiên),
    // nhưng danh sách mailFiles là 0-based, nên phải trừ đi 1.
    private int parseMessageId(String arg) {
        try {
            return Integer.parseInt(arg.trim()) - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}