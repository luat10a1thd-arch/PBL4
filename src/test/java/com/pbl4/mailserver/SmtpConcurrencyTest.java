package com.pbl4.mailserver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SmtpConcurrencyTest {

    private static final String HOST = "localhost";
    private static final int PORT = 2525;
    private static final int THREAD_COUNT = 5;

    public static void main(String[] args) {
        System.out.println("=== TEST SMTP AUTH + THREAD POOL (PORT 2525) ===");
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 1; i <= THREAD_COUNT; i++) {
            final int clientNo = i;
            executor.submit(() -> sendTestEmailViaSocket(clientNo));
        }

        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static void sendTestEmailViaSocket(int clientNo) {
        try (
            Socket socket = new Socket(HOST, PORT);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            in.readLine();

            out.println("HELO client" + clientNo);
            in.readLine();

            // GỬI AUTH LOGIN VỚI TÀI KHOẢN ĐÃ ĐĂNG KÝ
            out.println("AUTH LOGIN");
            in.readLine(); // 334 Username:
            
            // Encode Base64 cho User & Password
            String userBase64 = Base64.getEncoder().encodeToString("luat@pbl4.com".getBytes(StandardCharsets.UTF_8));
            String passBase64 = Base64.getEncoder().encodeToString("@Luat0105".getBytes(StandardCharsets.UTF_8));

            out.println(userBase64);
            in.readLine(); // 334 Password:

            out.println(passBase64);
            String authResp = in.readLine(); // 235 Auth successful

            if (authResp.startsWith("235")) {
                out.println("MAIL FROM:<userA@pbl4.com>");
                in.readLine();

                out.println("RCPT TO:<receiver@pbl4.com>");
                in.readLine();

                out.println("DATA");
                in.readLine();

                out.println("Subject: Thu Gui Co Auth");
                out.println("Client #" + clientNo + " gui thu xac thuc thanh cong!");
                out.println(".");
                
                String response = in.readLine();
                System.out.println("Client #" + clientNo + " nhận kết quả: " + response);

                out.println("QUIT");
                in.readLine();
            } else {
                System.err.println("Client #" + clientNo + " Đăng nhập thất bại: " + authResp);
            }

        } catch (Exception e) {
            System.err.println("Client #" + clientNo + " lỗi: " + e.getMessage());
        }
    }
}