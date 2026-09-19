// ============================================================
// File: POP3Client.java
// Package: com.pbl4.mailserver.client
// ------------------------------------------------------------
// Chức năng: Client console demo đúng giao thức POP3 thuần,
// kết nối Socket thô tới Pop3ServerManager.
// ============================================================

package com.pbl4.mailserver.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

public class POP3Client {

    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner(System.in);

        System.out.print("Nhập host server: ");
        String host = scanner.nextLine().trim();
        System.out.print("Nhập port POP3 (VD: 1110): ");
        int port = Integer.parseInt(scanner.nextLine().trim());

        try (
            Socket socket = new Socket(host, port);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            System.out.println("Server: " + in.readLine()); // +OK Ready

            System.out.print("Username: ");
            send(out, in, "USER " + scanner.nextLine().trim());

            System.out.print("Password: ");
            send(out, in, "PASS " + scanner.nextLine().trim());

            System.out.println("Gõ lệnh POP3 (STAT, LIST, RETR 1, DELE 1, RSET, QUIT):");
            String command;
            while (!(command = scanner.nextLine()).equalsIgnoreCase("QUIT")) {
                out.println(command);
                // LIST và RETR trả về nhiều dòng, kết thúc bằng dòng chỉ có "."
                if (command.toUpperCase().startsWith("LIST") || command.toUpperCase().startsWith("RETR")) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        System.out.println("Server: " + line);
                        if (line.equals(".")) break;
                    }
                } else {
                    System.out.println("Server: " + in.readLine());
                }
            }
            send(out, in, "QUIT");
        }
    }

    private static void send(PrintWriter out, BufferedReader in, String command) throws IOException {
        out.println(command);
        System.out.println("Server trả: " + in.readLine());
    }
}