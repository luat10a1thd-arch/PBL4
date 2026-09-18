package com.pbl4.mailserver.smtp;

import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.SecurityUtils;
import com.pbl4.mailserver.core.UserStore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class SmtpHandler implements Runnable {

    private final Socket clientSocket;

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

            while ((line = in.readLine()) != null) {
                if (isDataMode) {
                    if (".".equals(line)) {
                        isDataMode = false;
                        
                        // ĐỔI MỚI: Dùng đúng nội dung client gửi (bao gồm cả Subject nếu có)
                        String rawEmailContent = "From: " + mailFrom + "\nTo: " + rcptTo + "\n" + dataBuilder.toString();

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
                else if (upperLine.startsWith("AUTH LOGIN")) {
                    out.println("334 VXNlcm5hbWU6");
                    String encodedUser = in.readLine();
                    if (encodedUser == null) break;
                    String decodedUser = new String(Base64.getDecoder().decode(encodedUser.trim()), StandardCharsets.UTF_8);
                    
                    out.println("334 UGFzc3dvcmQ6");
                    String encodedPass = in.readLine();
                    if (encodedPass == null) break;
                    String decodedPass = new String(Base64.getDecoder().decode(encodedPass.trim()), StandardCharsets.UTF_8);

                    // Dùng UserStore.findHash()
                    String storedHash = UserStore.findHash(decodedUser);
                    if (storedHash != null && SecurityUtils.checkPassword(decodedPass, storedHash)) {
                        isAuthenticated = true;
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