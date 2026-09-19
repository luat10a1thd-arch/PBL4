// ============================================================
// File: SMTPClient.java
// Package: com.pbl4.mailserver.client
// ------------------------------------------------------------
// Chức năng: Client console demo đúng giao thức SMTP thuần,
// kết nối Socket thô tới SmtpServerManager - KHÔNG qua webapi.
// Dùng để test/chấm điểm đúng phần lõi giao thức của đồ án.
// ============================================================

package com.pbl4.mailserver.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

public class SMTPClient {

    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner(System.in);

        System.out.print("Nhập host server (VD: localhost hoặc IP/ngrok): ");
        String host = scanner.nextLine().trim();
        System.out.print("Nhập port SMTP (VD: 2525): ");
        int port = Integer.parseInt(scanner.nextLine().trim());

        try (
            Socket socket = new Socket(host, port);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            System.out.println("Server: " + in.readLine()); // 220 Ready

            send(out, in, "HELO client-console");

            System.out.print("Người gửi (from): ");
            String from = scanner.nextLine().trim();
            send(out, in, "MAIL FROM:<" + from + ">");

            System.out.print("Người nhận (to): ");
            String to = scanner.nextLine().trim();
            send(out, in, "RCPT TO:<" + to + ">");

            send(out, in, "DATA");

            System.out.println("Gõ nội dung mail, kết thúc bằng dòng chỉ có dấu . :");
            String line;
            while (!(line = scanner.nextLine()).equals(".")) {
                out.println(line);
            }
            out.println(".");
            System.out.println("Server: " + in.readLine()); // 250 accepted

            send(out, in, "QUIT");
        }
    }

    private static void send(PrintWriter out, BufferedReader in, String command) throws IOException {
        System.out.println("Client gửi: " + command);
        out.println(command);
        System.out.println("Server trả: " + in.readLine());
    }
}