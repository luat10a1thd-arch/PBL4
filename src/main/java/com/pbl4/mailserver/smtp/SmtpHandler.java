package com.pbl4.mailserver.smtp;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.LoginThrottle;
import com.pbl4.mailserver.core.MailService;
import com.pbl4.mailserver.core.SecurityUtils;
import com.pbl4.mailserver.core.SslContextFactory;
import com.pbl4.mailserver.core.UserStore;

import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

public class SmtpHandler implements Runnable {

    private Socket clientSocket;
    private static final int SOCKET_TIMEOUT_MS = 30000;

    public SmtpHandler(Socket socket) {
        this.clientSocket = socket;
    }

    @Override
    public void run() {
        try {
            clientSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
        } catch (Exception ignored) {}

        String clientIp = clientSocket.getInetAddress().getHostAddress();

        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true);

            sendResponse(out, "220 PBL4 Secure Mail Server Ready");

            String line;
            String mailFrom = "";
            Set<String> recipients = new LinkedHashSet<>();
            StringBuilder dataBuilder = new StringBuilder();
            boolean isDataMode = false;
            boolean isAuthenticated = false;
            String authenticatedUser = null;
            long dataBytes = 0;
            boolean tooLarge = false;
            boolean isTlsActive = false;

            while ((line = in.readLine()) != null) {
                if (isDataMode) {
                    if (".".equals(line)) {
                        isDataMode = false;

                        if (tooLarge) {
                            sendResponse(out, "552 5.3.4 Message size exceeds fixed limit");
                        } else {
                            MailService.DeliveryResult result = MailService.deliverRaw(mailFrom, recipients, dataBuilder.toString());
                            if (result.success) {
                                sendResponse(out, "250 OK: Message accepted for delivery");
                            } else {
                                sendResponse(out, "451 Requested action aborted: local error");
                            }
                        }
                        recipients.clear();
                        dataBuilder.setLength(0);
                        dataBytes = 0;
                        tooLarge = false;
                    } else {
                        if (line.startsWith("..")) {
                            line = line.substring(1);
                        }
                        dataBytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
                        if (dataBytes > ServerConfig.MAX_MESSAGE_BYTES) {
                            tooLarge = true;
                        } else {
                            dataBuilder.append(line).append("\n");
                        }
                    }
                    continue;
                }

                String upperLine = line.toUpperCase();
                if (upperLine.startsWith("HELO") || upperLine.startsWith("EHLO")) {
                    sendResponse(out, "250-pbl4.com Hello " + clientIp);
                    sendResponse(out, "250-SIZE " + ServerConfig.MAX_MESSAGE_BYTES);
                    if (!isTlsActive) {
                        sendResponse(out, "250-STARTTLS");
                    }
                    sendResponse(out, "250 AUTH LOGIN PLAIN");
                } 
                else if (upperLine.startsWith("STARTTLS")) {
                    if (isTlsActive) {
                        sendResponse(out, "454 TLS already active");
                        continue;
                    }
                    SSLServerSocketFactory sslFactory = SslContextFactory.createSSLServerSocketFactory();
                    if (sslFactory == null) {
                        sendResponse(out, "454 TLS not available on server");
                        continue;
                    }
                    sendResponse(out, "220 2.0.0 Ready to start TLS");

                    SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
                    SSLSocket sslSocket = (SSLSocket) sf.createSocket(
                            clientSocket,
                            clientSocket.getInetAddress().getHostAddress(),
                            clientSocket.getPort(),
                            true
                    );
                    sslSocket.setUseClientMode(false);
                    sslSocket.startHandshake();

                    clientSocket = sslSocket;
                    in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
                    out = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true);
                    isTlsActive = true;
                }
                else if (upperLine.startsWith("AUTH LOGIN")) {
                    sendResponse(out, "334 VXNlcm5hbWU6");
                    String encodedUser = in.readLine();
                    if (encodedUser == null) break;
                    String decodedUser = new String(Base64.getDecoder().decode(encodedUser.trim()), StandardCharsets.UTF_8);
                    
                    sendResponse(out, "334 UGFzc3dvcmQ6");
                    String encodedPass = in.readLine();
                    if (encodedPass == null) break;
                    String decodedPass = new String(Base64.getDecoder().decode(encodedPass.trim()), StandardCharsets.UTF_8);

                    if (LoginThrottle.isThrottled(clientIp)) {
                        sendResponse(out, "454 4.7.0 Too many failed attempts, try again later");
                        continue;
                    }

                    String storedHash = UserStore.findHash(decodedUser);
                    boolean isLocked = "LOCKED".equalsIgnoreCase(UserStore.getStatus(decodedUser));

                    if (storedHash != null && !isLocked && SecurityUtils.checkPassword(decodedPass, storedHash)) {
                        LoginThrottle.resetAttempts(clientIp);
                        isAuthenticated = true;
                        authenticatedUser = UserStore.canonical(decodedUser);
                        sendResponse(out, "235 2.7.0 Authentication successful");
                    } else {
                        LoginThrottle.recordFailedAttempt(clientIp);
                        sendResponse(out, "535 5.7.8 Authentication credentials invalid");
                    }
                } 
                else if (upperLine.startsWith("MAIL FROM:")) {
                    if (!isAuthenticated) {
                        sendResponse(out, "530 5.7.0 Authentication required");
                        continue;
                    }
                    
                    String candidate = parseAddress(line);
                    String canonicalCandidate = UserStore.canonical(candidate);
                    if (candidate.isEmpty() || canonicalCandidate == null || !canonicalCandidate.equalsIgnoreCase(authenticatedUser)) {
                        sendResponse(out, "553 5.7.1 Sender must match authenticated account");
                    } else {
                        mailFrom = canonicalCandidate;
                        recipients.clear();
                        sendResponse(out, "250 OK");
                    }
                } 
                else if (upperLine.startsWith("RCPT TO:")) {
                    if (!isAuthenticated) {
                        sendResponse(out, "530 5.7.0 Authentication required");
                        continue;
                    }

                    if (mailFrom == null || mailFrom.isEmpty()) {
                        sendResponse(out, "503 5.5.1 Need valid MAIL FROM first");
                        continue;
                    }

                    String candidate = parseAddress(line);
                    String canonicalRecipient = UserStore.canonical(candidate);
                    if (candidate.isEmpty() || canonicalRecipient == null || !UserStore.exists(canonicalRecipient)) {
                        sendResponse(out, "550 5.1.1 No such user here");
                    } else {
                        recipients.add(canonicalRecipient);
                        sendResponse(out, "250 OK");
                    }
                } 
                else if (upperLine.startsWith("DATA")) {
                    if (!isAuthenticated) {
                        sendResponse(out, "530 5.7.0 Authentication required");
                        continue;
                    }

                    if (mailFrom == null || mailFrom.isEmpty()) {
                        sendResponse(out, "503 5.5.1 Need valid MAIL FROM");
                        continue;
                    }
                    if (recipients.isEmpty()) {
                        sendResponse(out, "503 5.5.1 Need RCPT (recipient)");
                        continue;
                    }

                    isDataMode = true;
                    dataBuilder.setLength(0);
                    dataBytes = 0;
                    tooLarge = false;
                    sendResponse(out, "354 Start mail input; end with <CR><LF>.<CR><LF>");
                } 
                else if (upperLine.startsWith("RSET")) {
                    mailFrom = "";
                    recipients.clear();
                    dataBuilder.setLength(0);
                    dataBytes = 0;
                    tooLarge = false;
                    isDataMode = false;
                    sendResponse(out, "250 OK Reset completed");
                }
                else if (upperLine.startsWith("NOOP")) {
                    sendResponse(out, "250 OK");
                }
                else if (upperLine.startsWith("QUIT")) {
                    sendResponse(out, "221 Bye");
                    break;
                } 
                else {
                    sendResponse(out, "500 Command unrecognized");
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

    private void sendResponse(PrintWriter out, String response) {
        out.print(response + "\r\n");
        out.flush();
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