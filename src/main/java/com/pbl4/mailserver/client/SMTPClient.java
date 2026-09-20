package com.pbl4.mailserver.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class SMTPClient {

    private static final String SMTP_HOST = "localhost";
    private static final int SMTP_PORT = 2525;

    public static boolean send(String username, String password, String to, String subject, String body) {
        try (
            Socket socket = new Socket(SMTP_HOST, SMTP_PORT);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        ) {
            in.readLine(); // Greeting 220

            out.println("EHLO localhost");
            in.readLine();

            out.println("AUTH LOGIN");
            in.readLine();

            out.println(Base64.getEncoder().encodeToString(username.getBytes(StandardCharsets.UTF_8)));
            in.readLine();

            out.println(Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)));
            String authResp = in.readLine();

            if (authResp == null || !authResp.startsWith("235")) {
                return false;
            }

            out.println("MAIL FROM:<" + username + ">");
            in.readLine();

            out.println("RCPT TO:<" + to + ">");
            String rcptResp = in.readLine();

            // MỚI: Nếu phản hồi RCPT TO không bắt đầu bằng 250 (ví dụ lỗi 550 người nhận không tồn tại), ngắt gửi ngay
            if (rcptResp == null || !rcptResp.startsWith("250")) {
                out.println("QUIT");
                return false;
            }

            out.println("DATA");
            in.readLine();

            out.println("Subject: " + subject);
            out.println();
            out.println(body);
            out.println(".");
            
            String response = in.readLine();
            out.println("QUIT");

            return response != null && response.startsWith("250");
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}