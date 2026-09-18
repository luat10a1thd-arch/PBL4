package com.pbl4.mailserver.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class SMTPClient {

    private static final String SMTP_HOST = "localhost";
    private static final int SMTP_PORT = 2525; // Cổng SMTP Server của bạn

    public static boolean send(String username, String password, String to, String subject, String body) {
        try (
            Socket socket = new Socket(SMTP_HOST, SMTP_PORT);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        ) {
            in.readLine(); // Đọc greeting 220

            out.println("EHLO localhost");
            in.readLine();

            // 1. Gửi lệnh yêu cầu đăng nhập AUTH LOGIN
            out.println("AUTH LOGIN");
            in.readLine(); // Server phản hồi 334 (Yêu cầu Username)

            // 2. Gửi Username dạng mã hóa Base64
            out.println(Base64.getEncoder().encodeToString(username.getBytes(StandardCharsets.UTF_8)));
            in.readLine(); // Server phản hồi 334 (Yêu cầu Password)

            // 3. Gửi Password dạng mã hóa Base64
            out.println(Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)));
            String authResp = in.readLine(); // Server phản hồi 235 Authentication successful

            // Nếu xác thực thất bại thì dừng phiên làm việc
            if (authResp == null || !authResp.startsWith("235")) {
                return false;
            }

            // 4. Tiến hành gửi mail theo luồng chuẩn SMTP RFC 5321
            out.println("MAIL FROM:<" + username + ">");
            in.readLine();

            out.println("RCPT TO:<" + to + ">");
            in.readLine();

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