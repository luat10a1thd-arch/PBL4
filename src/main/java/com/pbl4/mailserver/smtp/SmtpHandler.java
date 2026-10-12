package com.pbl4.mailserver.smtp;

import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.SecurityUtils;
import com.pbl4.mailserver.core.UserStore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class SmtpHandler implements Runnable {

    private final Socket clientSocket;
    private static final int SOCKET_TIMEOUT_MS = 30000; // --- MỤC 8.5: Timeout 30s ---

    public SmtpHandler(Socket socket) {
        this.clientSocket = socket;
    }

    @Override
    public void run() {
        try {
            // --- MỤC 8.5: Cấu hình Read Timeout phòng chống treo kết nối làm ngạt ThreadPool ---
            clientSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
        } catch (Exception ignored) {}

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
            String authenticatedUser = null;

            while ((line = in.readLine()) != null) {
                if (isDataMode) {
                    if (".".equals(line)) {
                        isDataMode = false;
                        String rawEmailContent = "From: " + mailFrom + "\nTo: " + rcptTo + "\n" + dataBuilder.toString();

                        boolean savedToRecipient = MailStorageEngine.saveEmail(rcptTo, rawEmailContent);
                        boolean savedToSender = MailStorageEngine.saveEmail(mailFrom + "_sent", rawEmailContent);

                        if (savedToRecipient && savedToSender) {
                            out.println("250 OK: Message accepted for delivery");
                        } else {
                            out.println("451 Requested action aborted: local error");
                        }
                        rcptTo = "";
                        dataBuilder.setLength(0);
                    } else {
                        // FIX MỤC 8.2: Unstuffing
                        if (line.startsWith("..")) {
                            line = line.substring(1);
                        }
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

                    String storedHash = UserStore.findHash(decodedUser);
                    if (storedHash != null && SecurityUtils.checkPassword(decodedPass, storedHash)) {
                        isAuthenticated = true;
                        authenticatedUser = decodedUser;
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
                    
                    // FIX MỤC 8.1: Kiểm tra chống giả mạo
                    String candidate = parseAddress(line);
                    if (candidate.isEmpty() || !candidate.equalsIgnoreCase(authenticatedUser)) {
                        out.println("553 5.7.1 Sender must match authenticated account");
                    } else {
                        mailFrom = candidate;
                        out.println("250 OK");
                    }
                } 
                else if (upperLine.startsWith("RCPT TO:")) {
                    if (!isAuthenticated) {
                        out.println("530 5.7.0 Authentication required");
                        continue;
                    }

                    if (mailFrom == null || mailFrom.isEmpty()) {
                        out.println("503 5.5.1 Need valid MAIL FROM first");
                        continue;
                    }

                    String candidate = parseAddress(line);
                    if (!UserStore.exists(candidate)) {
                        out.println("550 5.1.1 No such user here");
                    } else {
                        rcptTo = candidate;
                        out.println("250 OK");
                    }
                } 
                else if (upperLine.startsWith("DATA")) {
                    if (!isAuthenticated) {
                        out.println("530 5.7.0 Authentication required");
                        continue;
                    }

                    if (mailFrom == null || mailFrom.isEmpty()) {
                        out.println("503 5.5.1 Need valid MAIL FROM");
                        continue;
                    }
                    if (rcptTo == null || rcptTo.isEmpty()) {
                        out.println("503 5.5.1 Need RCPT (recipient)");
                        continue;
                    }

                    isDataMode = true;
                    dataBuilder.setLength(0);
                    out.println("354 Start mail input; end with <CR><LF>.<CR><LF>");
                } 
                // FIX MỤC 8.3: Bổ sung lệnh RSET
                else if (upperLine.startsWith("RSET")) {
                    mailFrom = "";
                    rcptTo = "";
                    dataBuilder.setLength(0);
                    isDataMode = false;
                    out.println("250 OK Reset completed");
                }
                // FIX MỤC 8.3: Bổ sung lệnh NOOP
                else if (upperLine.startsWith("NOOP")) {
                    out.println("250 OK");
                }
                else if (upperLine.startsWith("QUIT")) {
                    out.println("221 Bye");
                    break;
                } 
                else {
                    out.println("500 Command unrecognized");
                }
            }
        } catch (SocketTimeoutException e) {
            System.err.println("[SMTP] Ngắt kết nối do quá thời gian chờ (Timeout): " + clientSocket.getInetAddress());
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