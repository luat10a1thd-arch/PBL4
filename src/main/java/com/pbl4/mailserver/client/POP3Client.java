package com.pbl4.mailserver.client;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class POP3Client {

    /** Trả về danh sách nội dung thư thô (đã giải mã) của 1 mailbox, null nếu xác thực thất bại */
    public static List<String> fetchAll(String username, String password) {
        List<String> results = new ArrayList<>();
        try (Socket socket = new Socket("localhost", 1110);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            in.readLine(); // +OK banner

            out.println("USER " + username);
            in.readLine();

            out.println("PASS " + password);
            String authResp = in.readLine();
            if (authResp == null || !authResp.startsWith("+OK")) {
                return null; // sai mật khẩu hoặc user không tồn tại
            }

            out.println("LIST");
            String listHeader = in.readLine(); // +OK N messages
            int count = parseCount(listHeader);

            List<Integer> sizes = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                in.readLine(); // "1 123" (thứ tự + kích thước) - không cần dùng ở đây
            }
            in.readLine(); // dòng kết thúc "."

            for (int i = 1; i <= count; i++) {
                out.println("RETR " + i);
                String status = in.readLine(); // +OK Octets follows
                if (status == null || !status.startsWith("+OK")) continue;

                StringBuilder msg = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null && !line.equals(".")) {
                    // Bỏ một dấu chấm đã được POP3 dot-stuffing thêm vào khi gửi.
                    if (line.startsWith("..")) line = line.substring(1);
                    msg.append(line).append("\n");
                }
                results.add(msg.toString());
            }

            out.println("QUIT");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return results;
    }

    private static int parseCount(String line) {
        try {
            String[] parts = line.replace("+OK", "").trim().split(" ");
            return Integer.parseInt(parts[0]);
        } catch (Exception e) {
            return 0;
        }
    }
}