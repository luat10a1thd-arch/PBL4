package com.pbl4.mailserver.smtp;

import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.SecurityUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Xử lý phiên làm việc SMTP hỗ trợ SMTP Authentication (AUTH LOGIN).
 */
public class SmtpHandler implements Runnable {

    private final Socket clientSocket;
    private static final String USERS_FILE = "data/users.json";

    public SmtpHandler(Socket socket) {
        this.clientSocket = socket;
    }

    @Override
    public void run() {
        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
        ) {
            out.println("220 PBL4 Secure Mail Server Ready");

            String line;
            String mailFrom = "";
            String rcptTo = "";
            StringBuilder dataBuilder = new StringBuilder();
            boolean isDataMode = false;
            boolean isAuthenticated = false;
            String authUser = null;

            while ((line = in.readLine()) != null) {
                if (isDataMode) {
                    if (".".equals(line)) {
                        isDataMode = false;
                        String rawEmailContent = "From: " + mailFrom + "\n" +
                                                 "To: " + rcptTo + "\n" +
                                                 "Subject: Thu gui qua SMTP Socket (Auth)\n\n" +
                                                 dataBuilder.toString();

                        boolean savedToRecipient = MailStorageEngine.saveEmail(rcptTo, rawEmailContent);
                        boolean savedToSender = MailStorageEngine.saveEmail(mailFrom + "_sent", rawEmailContent);

                        if (savedToRecipient && savedToSender) {
                            out.println("250 OK: Message accepted for delivery");
                        } else {
                            out.println("451 Requested action aborted: local error");
                        }
                    } else {
                        dataBuilder.append(line).append("\n");
                    }
                    continue;
                }

                String upperLine = line.toUpperCase();
                if (upperLine.startsWith("HELO") || upperLine.startsWith("EHLO")) {
                    out.println("250 Hello " + clientSocket.getInetAddress().getHostAddress());
                } 
                // XỬ LÝ LỆNH AUTH LOGIN
                else if (upperLine.startsWith("AUTH LOGIN")) {
                    out.println("334 VXNlcm5hbWU6"); // Base64 của "Username:"
                    String encodedUser = in.readLine();
                    if (encodedUser == null) break;
                    
                    String decodedUser = new String(Base64.getDecoder().decode(encodedUser.trim()), StandardCharsets.UTF_8);
                    
                    out.println("334 UGFzc3dvcmQ6"); // Base64 của "Password:"
                    String encodedPass = in.readLine();
                    if (encodedPass == null) break;
                    
                    String decodedPass = new String(Base64.getDecoder().decode(encodedPass.trim()), StandardCharsets.UTF_8);

                    // Xác thực bằng BCrypt với data/users.json
                    String storedHash = findUserHash(decodedUser);
                    if (storedHash != null && SecurityUtils.checkPassword(decodedPass, storedHash)) {
                        isAuthenticated = true;
                        authUser = decodedUser;
                        out.println("235 2.7.0 Authentication successful");
                    } else {
                        out.println("535 5.7.8 Authentication credentials invalid");
                    }
                } 
                else if (upperLine.startsWith("MAIL FROM:")) {
                    if (!isAuthenticated) {
                        out.println("530 5.7.0 Authentication required");
                        continue;
                    }
                    mailFrom = parseAddress(line);
                    out.println("250 OK");
                } 
                else if (upperLine.startsWith("RCPT TO:")) {
                    if (!isAuthenticated) {
                        out.println("530 5.7.0 Authentication required");
                        continue;
                    }
                    rcptTo = parseAddress(line);
                    out.println("250 OK");
                } 
                else if (upperLine.startsWith("DATA")) {
                    if (!isAuthenticated) {
                        out.println("530 5.7.0 Authentication required");
                        continue;
                    }
                    isDataMode = true;
                    dataBuilder.setLength(0);
                    out.println("354 Start mail input; end with <CR><LF>.<CR><LF>");
                } 
                else if (upperLine.startsWith("QUIT")) {
                    out.println("221 Bye");
                    break;
                } 
                else {
                    out.println("500 Command unrecognized");
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
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line.trim());
            }
            String content = sb.toString();

            // Tìm user trong mảng JSON
            String searchPattern = "\"username\":\"" + username + "\"";
            int userIdx = content.indexOf(searchPattern);
            if (userIdx == -1) {
                searchPattern = "\"username\": \"" + username + "\"";
                userIdx = content.indexOf(searchPattern);
            }

            if (userIdx != -1) {
                int passIdx = content.indexOf("\"passwordHash\"", userIdx);
                if (passIdx != -1) {
                    int firstQuote = content.indexOf("\"", passIdx + 14);
                    int secondQuote = content.indexOf("\"", firstQuote + 1);
                    int thirdQuote = content.indexOf("\"", secondQuote + 1);
                    return content.substring(secondQuote + 1, thirdQuote);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private String parseAddress(String line) {
        int start = line.indexOf('<');
        int end = line.indexOf('>');
        if (start != -1 && end != -1 && start < end) {
            return line.substring(start + 1, end).trim();
        }
        String[] parts = line.split(":");
        return parts.length > 1 ? parts[1].trim() : "";
    }
}