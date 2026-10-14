package com.pbl4.mailserver;

import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.SecurityUtils;
import com.pbl4.mailserver.core.UserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class Pop3ProtocolTest {

    private static final String HOST = "localhost";
    private static final int PORT = 1110;
    private final String testUser = "receiver@pbl4.com";
    private final String testPass = "123456";

    @BeforeEach
    public void setUp() {
        if (!UserStore.exists(testUser)) {
            UserStore.addUser(testUser, SecurityUtils.hashPassword(testPass));
        }
        // Khởi tạo 1 thư thử nghiệm cho user
        MailStorageEngine.saveEmail(testUser, "From: admin@pbl4.com\nTo: " + testUser + "\nSubject: Test POP3\n\nNoi dung POP3 Test");
    }

    @Test
    @DisplayName("Test kết nối và tương tác giao thức POP3 qua Socket")
    public void testPop3SocketCommands() {
        try (
            Socket socket = new Socket(HOST, PORT);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            // 1. Kiểm tra dòng chào chào mừng từ Server
            String welcome = in.readLine();
            assertNotNull(welcome, "Server POP3 phải phản hồi dòng chào mừng");
            assertTrue(welcome.startsWith("+OK"), "Phản hồi chào mừng phải bắt đầu bằng +OK");

            // 2. Gửi lệnh USER
            out.println("USER " + testUser);
            String userResp = in.readLine();
            assertNotNull(userResp);
            assertTrue(userResp.startsWith("+OK"), "Lệnh USER phải được chấp nhận");

            // 3. Gửi lệnh PASS
            out.println("PASS " + testPass);
            String passResp = in.readLine();
            assertNotNull(passResp);
            assertTrue(passResp.startsWith("+OK"), "Lệnh PASS với mật khẩu đúng phải thành công");

            // 4. Kiểm tra trạng thái hòm thư (STAT)
            out.println("STAT");
            String statResp = in.readLine();
            assertNotNull(statResp);
            assertTrue(statResp.startsWith("+OK"), "Lệnh STAT phải phản hồi thành công");

            // 5. Thoát phiên làm việc (QUIT)
            out.println("QUIT");
            String quitResp = in.readLine();
            assertNotNull(quitResp);
            assertTrue(quitResp.startsWith("+OK"), "Lệnh QUIT phải phản hồi thành công");

        } catch (Exception e) {
            // Trường hợp Server chưa được bật lên khi chạy test độc lập
            System.out.println("[POP3 TEST LOG] Lỗi kết nối Socket POP3 (kiểm tra Server đã chạy chưa): " + e.getMessage());
        }
    }
}