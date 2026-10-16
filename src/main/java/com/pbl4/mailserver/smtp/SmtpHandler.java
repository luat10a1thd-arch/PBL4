package com.pbl4.mailserver.smtp;

import com.pbl4.mailserver.core.LoginThrottle;
import com.pbl4.mailserver.core.MailService;
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
    private static final int SOCKET_TIMEOUT_MS = 30000;

    public SmtpHandler(Socket socket) {
        this.clientSocket = socket;
    }

    @Override
    public void run() {
        try {
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
            boolean inClientHeaders = true;
            boolean isAuthenticated = false;
            String authenticatedUser = null;

            while ((line = in.readLine()) != null) {
                if (isDataMode) {
                    if (".".equals(line)) {
                        isDataMode = false;

                        MailService.DeliveryResult result = MailService.deliver(mailFrom, rcptTo, "SMTP Message", dataBuilder.toString());

                        if (result.success) {
                            out.println("250 OK: Message accepted for delivery");
                        } else {
                            out.println("451 Requested action aborted: local error");
                        }
                        rcptTo = "";
                        dataBuilder.setLength(0);
                    } else {
                        if (line.startsWith("..")) {
                            line = line.substring(1);
                        }
                        
                        if (inClientHeaders) {
                            if (line.isEmpty()) {
                                inClientHeaders = false;
                            } else {
                                String lower = line.toLowerCase();
                                if (lower.startsWith("from:") || lower.startsWith("to:") || lower.startsWith("sender:")) {
                                    continue;
                                }
                            }
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

                    if (LoginThrottle.isThrottled(decodedUser)) {
                        out.println("454 4.7.0 Too many failed attempts, try again later");
                        continue;
                    }

                    String storedHash = UserStore.findHash(decodedUser);
                    boolean isLocked = "LOCKED".equalsIgnoreCase(UserStore.getStatus(decodedUser));

                    if (storedHash != null && !isLocked && SecurityUtils.checkPassword(decodedPass, storedHash)) {
                        LoginThrottle.resetAttempts(decodedUser);
                        isAuthenticated = true;
                        authenticatedUser = decodedUser;
                        out.println("235 2.7.0 Authentication successful");
                    } else {
                        LoginThrottle.recordFailedAttempt(decodedUser);
                        out.println("535 5.7.8 Authentication credentials invalid");
                    }
                } 
                else if (upperLine.startsWith("MAIL FROM:")) {
                    if (!isAuthenticated) {
                        out.println("530 5.7.0 Authentication required");
                        continue;
                    }
                    
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
                    inClientHeaders = true;
                    dataBuilder.setLength(0);
                    out.println("354 Start mail input; end with <CR><LF>.<CR><LF>");
                } 
                else if (upperLine.startsWith("RSET")) {
                    mailFrom = "";
                    rcptTo = "";
                    dataBuilder.setLength(0);
                    isDataMode = false;
                    inClientHeaders = true;
                    out.println("250 OK Reset completed");
                }
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