// ============================================================
// File: SmtpHandler.java
// Package: com.pbl4.mailserver.smtp
// ------------------------------------------------------------
// Chức năng: Trình phân tích cú pháp RFC 5321 & State Machine.
// Xử lý HELO -> MAIL FROM -> RCPT TO -> DATA -> QUIT.
// Khi nhận xong khối DATA, gọi MailStorageEngine.writeMail()
// để mã hóa AES-256 và ghi xuống đĩa.
// ============================================================

package com.pbl4.mailserver.smtp;

import com.pbl4.mailserver.core.MailStorageEngine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class SmtpHandler implements Runnable {

    private enum State { INIT, HELO_DONE, MAIL_FROM_DONE, RCPT_TO_DONE }

    private final Socket socket;
    private final MailStorageEngine storageEngine;

    private State state = State.INIT;
    private String sender;
    private String receiver;

    public SmtpHandler(Socket socket, MailStorageEngine storageEngine) {
        this.socket = socket;
        this.storageEngine = storageEngine;
    }

    @Override
    public void run() {
        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            out.println("220 MailServer SMTP Ready");

            String line;
            while ((line = in.readLine()) != null) {
                System.out.println("[SMTP] Nhận: " + line);
                String upper = line.toUpperCase();

                if (upper.equals("HELO") || upper.startsWith("HELO ") ||
                    upper.equals("EHLO") || upper.startsWith("EHLO ")) {
                    state = State.HELO_DONE;
                    out.println("250 Hello, pleased to meet you");

                } else if (upper.startsWith("MAIL FROM:")) {
                    if (state != State.HELO_DONE) {
                        out.println("503 Bad sequence of commands");
                        continue;
                    }
                    sender = extractEmail(line);
                    state = State.MAIL_FROM_DONE;
                    out.println("250 OK");

                } else if (upper.startsWith("RCPT TO:")) {
                    if (state != State.MAIL_FROM_DONE) {
                        out.println("503 Bad sequence of commands");
                        continue;
                    }
                    receiver = extractEmail(line);
                    state = State.RCPT_TO_DONE;
                    out.println("250 OK");

                } else if (upper.equals("DATA")) {
                    if (state != State.RCPT_TO_DONE) {
                        out.println("503 Bad sequence of commands");
                        continue;
                    }
                    out.println("354 Start mail input; end with <CRLF>.<CRLF>");
                    String body = readMailBody(in);

                    // Đây là điểm khác biệt lớn nhất so với bản SQLite cũ:
                    // gọi MailStorageEngine để MÃ HÓA AES-256 rồi ghi file .enc,
                    // thay vì INSERT vào database.
                    storageEngine.writeMail(sender, receiver, body);

                    out.println("250 2.0.0 Message accepted");
                    state = State.HELO_DONE; // cho phép gửi tiếp mail khác

                } else if (upper.equals("QUIT")) {
                    out.println("221 Bye");
                    break;

                } else {
                    out.println("500 Command not recognized");
                }
            }
        } catch (IOException e) {
            System.err.println("[SmtpHandler] Lỗi kết nối: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private String readMailBody(BufferedReader in) throws IOException {
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.equals(".")) break;
            if (line.startsWith("..")) line = line.substring(1); // dot-unstuffing
            body.append(line).append("\n");
        }
        return body.toString();
    }

    private String extractEmail(String line) {
        int start = line.indexOf('<');
        int end = line.indexOf('>');
        if (start >= 0 && end > start) {
            return line.substring(start + 1, end);
        }
        int colon = line.indexOf(':');
        return (colon >= 0) ? line.substring(colon + 1).trim() : line.trim();
    }
}