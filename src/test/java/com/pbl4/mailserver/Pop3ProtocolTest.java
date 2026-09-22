package com.pbl4.mailserver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * File Test kiểm tra giao thức POP3 qua cổng Socket 1110.
 */
public class Pop3ProtocolTest {

    private static final String HOST = "localhost";
    private static final int PORT = 1110;

    public static void main(String[] args) {
        System.out.println("=== BẮT ĐẦU TEST KẾT NỐI POP3 SOCKET (PORT 1110) ===");

        // Đảm bảo bạn đã đăng ký tài khoản receiver@pbl4.com hoặc dùng tài khoản có sẵn
        String testUser = "receiver@pbl4.com";
        String testPass = "123456";

        try (
            Socket socket = new Socket(HOST, PORT);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            // 1. Đọc dòng chào từ Server (+OK...)
            System.out.println("Server: " + in.readLine());

            // 2. Gửi lệnh USER
            out.println("USER " + testUser);
            System.out.println("Client: USER " + testUser);
            System.out.println("Server: " + in.readLine());

            // 3. Gửi lệnh PASS (Xác thực BCrypt)
            out.println("PASS " + testPass);
            System.out.println("Client: PASS ******");
            String passResp = in.readLine();
            System.out.println("Server: " + passResp);

            if (passResp.startsWith("+OK")) {
                // 4. Kiểm tra trạng thái hòm thư (STAT)
                out.println("STAT");
                System.out.println("Client: STAT");
                System.out.println("Server: " + in.readLine());

                // 5. Lấy danh sách email (LIST)
                out.println("LIST");
                System.out.println("Client: LIST");
                String listLine;
                while ((listLine = in.readLine()) != null) {
                    System.out.println("Server: " + listLine);
                    if (".".equals(listLine)) break;
                }

                // 6. Đọc thư đầu tiên (RETR 1)
                out.println("RETR 1");
                System.out.println("Client: RETR 1");
                String retrLine;
                while ((retrLine = in.readLine()) != null) {
                    System.out.println("Server: " + retrLine);
                    if (".".equals(retrLine)) break;
                }
            }

            // 7. Thoát phiên làm việc (QUIT)
            out.println("QUIT");
            System.out.println("Client: QUIT");
            System.out.println("Server: " + in.readLine());

        } catch (Exception e) {
            System.err.println("Lỗi kết nối POP3 Test: " + e.getMessage());
        }
    }
}