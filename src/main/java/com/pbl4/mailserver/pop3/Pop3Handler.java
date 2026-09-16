package com.pbl4.mailserver.pop3;

import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.SecurityUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Xử lý phiên làm việc POP3 Server qua cổng Socket 1110.
 */
public class Pop3Handler implements Runnable {

    private final Socket clientSocket;
    private static final String USERS_FILE = "data/users.json";

    public Pop3Handler(Socket socket) {
        this.clientSocket = socket;
    }

    @Override
    public void run() {
        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
        ) {
            out.println("+OK PBL4 POP3 Server Ready");

            String line;
            String currentUser = null;
            boolean isAuthenticated = false;
            List<String> emails = new ArrayList<>();

            while ((line = in.readLine()) != null) {
                String[] tokens = line.split(" ", 2);
                String command = tokens[0].toUpperCase();
                String argument = tokens.length > 1 ? tokens[1].trim() : "";

                if ("USER".equals(command)) {
                    currentUser = argument;
                    out.println("+OK User accepted");
                } 
                else if ("PASS".equals(command)) {
                    if (currentUser == null) {
                        out.println("-ERR USER command required first");
                        continue;
                    }
                    
                    // Xác thực BCrypt mật khẩu từ users.json
                    String storedHash = findUserHash(currentUser);
                    if (storedHash != null && SecurityUtils.checkPassword(argument, storedHash)) {
                        isAuthenticated = true;
                        emails = MailStorageEngine.readAllEmails(currentUser);
                        out.println("+OK Logged in successfully");
                    } else {
                        out.println("-ERR Invalid username or password");
                    }
                } 
                else if ("STAT".equals(command)) {
                    if (!isAuthenticated) {
                        out.println("-ERR Please authenticate first");
                        continue;
                    }
                    out.println("+OK " + emails.size() + " messages");
                } 
                else if ("LIST".equals(command)) {
                    if (!isAuthenticated) {
                        out.println("-ERR Please authenticate first");
                        continue;
                    }
                    out.println("+OK " + emails.size() + " messages");
                    for (int i = 0; i < emails.size(); i++) {
                        out.println((i + 1) + " " + emails.get(i).getBytes(StandardCharsets.UTF_8).length);
                    }
                    out.println(".");
                } 
                else if ("RETR".equals(command)) {
                    if (!isAuthenticated) {
                        out.println("-ERR Please authenticate first");
                        continue;
                    }
                    try {
                        int index = Integer.parseInt(argument) - 1;
                        if (index >= 0 && index < emails.size()) {
                            out.println("+OK Octets follows");
                            out.println(emails.get(index));
                            out.println(".");
                        } else {
                            out.println("-ERR Message not found");
                        }
                    } catch (NumberFormatException e) {
                        out.println("-ERR Invalid message number");
                    }
                } 
                else if ("QUIT".equals(command)) {
                    out.println("+OK Logging out");
                    break;
                } 
                else {
                    out.println("-ERR Unknown command");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (Exception ignored) {}
        }
    }

    private String findUserHash(String username) {
        File file = new File(USERS_FILE);
        if (!file.exists()) return null;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String l;
            while ((l = reader.readLine()) != null) {
                if (l.contains("\"username\": \"" + username + "\"")) {
                    String nextLine = reader.readLine();
                    if (nextLine != null && nextLine.contains("passwordHash")) {
                        return nextLine.split(":")[1].replace("\"", "").replace("}", "").trim();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}