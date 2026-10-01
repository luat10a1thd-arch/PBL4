package com.pbl4.mailserver.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** SMTP client dùng nội bộ để chuyển thư qua SMTP server của ứng dụng. */
public class SMTPClient {
    private static final String SMTP_HOST = "127.0.0.1";
    private static final int SMTP_PORT = 2525;

    public static boolean send(String username, String password, String to, String subject, String body) {
        if (username == null || password == null || to == null || subject == null || body == null) return false;
        username = username.trim();
        to = to.trim();
        if (username.isEmpty() || to.isEmpty()) return false;

        try (Socket socket = new Socket(SMTP_HOST, SMTP_PORT);
             PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(10000);
            if (!expect(in, "220")) return false;

            out.println("EHLO localhost");
            if (!expect(in, "250")) return false;

            out.println("AUTH LOGIN");
            if (!expect(in, "334")) return false;
            out.println(Base64.getEncoder().encodeToString(username.getBytes(StandardCharsets.UTF_8)));
            if (!expect(in, "334")) return false;
            out.println(Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)));
            if (!expect(in, "235")) return false;

            out.println("MAIL FROM:<" + username + ">");
            if (!expect(in, "250")) return false;
            out.println("RCPT TO:<" + to + ">");
            if (!expect(in, "250")) return false;
            out.println("DATA");
            if (!expect(in, "354")) return false;

            out.println("Subject: " + cleanHeader(subject));
            out.println("Date: " + java.time.ZonedDateTime.now());
            out.println();
            // SMTP yêu cầu escape dòng chỉ có dấu chấm để không kết thúc DATA sớm.
            for (String line : body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
                out.println(line.startsWith(".") ? "." + line : line);
            }
            out.println(".");
            out.flush();
            boolean accepted = expect(in, "250");
            out.println("QUIT");
            return accepted;
        } catch (Exception e) {
            System.err.println("[SMTP CLIENT] Không gửi được thư: " + e.getMessage());
            return false;
        }
    }

    private static boolean expect(BufferedReader in, String expectedCode) throws Exception {
        String response = in.readLine();
        if (response == null || !response.startsWith(expectedCode)) {
            System.err.println("[SMTP CLIENT] Phản hồi SMTP không mong đợi: " + response + " (cần " + expectedCode + ")");
            return false;
        }
        return true;
    }

    private static String cleanHeader(String value) {
        return value.replace("\r", " ").replace("\n", " ").trim();
    }
}
