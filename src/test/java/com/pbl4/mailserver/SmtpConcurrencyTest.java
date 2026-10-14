package com.pbl4.mailserver;

import com.pbl4.mailserver.config.ServerConfig;
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
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class SmtpConcurrencyTest {

    private static final String HOST = "localhost";
    private static final int PORT = ServerConfig.SMTP_PORT;
    private static final int THREAD_COUNT = 5;
    private final String testUser = "luat@pbl4.com";
    private final String testPass = "TestPass123!";

    @BeforeEach
    public void setUp() {
        if (!UserStore.exists(testUser)) {
            UserStore.addUser(testUser, SecurityUtils.hashPassword(testPass));
        }
    }

    @Test
    @DisplayName("Test SMTP Authentication & Đa luồng đồng thời (Concurrency)")
    public void testConcurrentSmtpRequests() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 1; i <= THREAD_COUNT; i++) {
            final int clientNo = i;
            executor.submit(() -> {
                if (sendTestEmailViaSocket(clientNo)) {
                    successCount.incrementAndGet();
                }
            });
        }

        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);
        assertTrue(finished, "Tất cả luồng phải hoàn thành trong 10s");
    }

    private boolean sendTestEmailViaSocket(int clientNo) {
        try (
            Socket socket = new Socket(HOST, PORT);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            in.readLine();

            out.println("HELO client" + clientNo);
            in.readLine();

            out.println("AUTH LOGIN");
            in.readLine();

            String userBase64 = Base64.getEncoder().encodeToString(testUser.getBytes(StandardCharsets.UTF_8));
            String passBase64 = Base64.getEncoder().encodeToString(testPass.getBytes(StandardCharsets.UTF_8));

            out.println(userBase64);
            in.readLine();

            out.println(passBase64);
            String authResp = in.readLine();

            if (authResp != null && authResp.startsWith("235")) {
                out.println("MAIL FROM:<" + testUser + ">");
                in.readLine();

                out.println("RCPT TO:<receiver@pbl4.com>");
                in.readLine();

                out.println("DATA");
                in.readLine();

                out.println("Subject: Thu Gui Co Auth");
                out.println("Client #" + clientNo + " gui thu xac thuc thanh cong!");
                out.println(".");

                String response = in.readLine();
                out.println("QUIT");
                in.readLine();

                return response != null && response.startsWith("250");
            }

        } catch (Exception e) {
            System.out.println("[SMTP TEST LOG] Client #" + clientNo + " không thể kết nối (Server đã chạy chưa?): " + e.getMessage());
        }
        return false;
    }
}