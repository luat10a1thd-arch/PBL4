package com.pbl4.mailserver.smtp;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.LoginThrottle;
import com.pbl4.mailserver.core.MailService;
import com.pbl4.mailserver.core.SecurityUtils;
import com.pbl4.mailserver.core.SslContextFactory;
import com.pbl4.mailserver.core.UserStore;

import javax.net.ssl.SSLContext;
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
import java.util.Locale;
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
                                sendResponse(out, "451 Requested action aborted: " + result.message);
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

                    SSLContext sslContext = SslContextFactory.createSSLContext();
                    if (sslContext == null) {
                        sendResponse(out, "454 TLS not available on server");
                        continue;
                    }

                    sendResponse(out, "220 2.0.0 Ready to start TLS");

                    SSLSocketFactory sf = sslContext.getSocketFactory();
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
                    mailFrom = "";
                    recipients.clear();
                    isAuthenticated = false;
                    authenticatedUser = null;
                }
                else if (upperLine.startsWith("AUTH LOGIN")) {
                    sendResponse(out, "334 VXNlcm5hbWU6");
                    String encodedUser = in.readLine();
                    if (encodedUser == null) break;
                    
                    String decodedUser;
                    try {
                        decodedUser = new String(Base64.getDecoder().decode(encodedUser.trim()), StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException e) {
                        sendResponse(out, "501 5.5.4 Cannot decode Base64 username");
                        continue;
                    }

                    sendResponse(out, "334 UGFzc3dvcmQ6");
                    String encodedPass = in.readLine();
                    if (encodedPass == null) break;
                    
                    String decodedPass;
                    try {
                        decodedPass = new String(Base64.getDecoder().decode(encodedPass.trim()), StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException e) {
                        sendResponse(out, "501 5.5.4 Cannot decode Base64 password");
                        continue;
                    }

                    String authResult = authenticateUser(clientIp, decodedUser, decodedPass);
                    if (authResult != null) {
                        isAuthenticated = true;
                        authenticatedUser = authResult;
                        sendResponse(out, "235 2.7.0 Authentication successful");
                    } else {
                        sendResponse(out, "535 5.7.8 Authentication credentials invalid");
                    }
                }
                else if (upperLine.startsWith("AUTH PLAIN")) {
                    String initialResponse = "";
                    if (line.length() > 10) {
                        initialResponse = line.substring(10).trim();
                    }

                    if (initialResponse.isEmpty()) {
                        sendResponse(out, "334 ");
                        initialResponse = in.readLine();
                        if (initialResponse == null) break;
                        initialResponse = initialResponse.trim();
                    }

                    try {
                        byte[] decoded = Base64.getDecoder().decode(initialResponse);
                        String plainStr = new String(decoded, StandardCharsets.UTF_8);
                        String[] parts = plainStr.split("\0", -1);

                        String user = "";
                        String pass = "";

                        if (parts.length >= 3) {
                            user = parts[1];
                            pass = parts[2];
                        } else if (parts.length == 2) {
                            user = parts[0];
                            pass = parts[1];
                        }

                        String authResult = authenticateUser(clientIp, user, pass);
                        if (authResult != null) {
                            isAuthenticated = true;
                            authenticatedUser = authResult;
                            sendResponse(out, "235 2.7.0 Authentication successful");
                        } else {
                            sendResponse(out, "535 5.7.8 Authentication credentials invalid");
                        }
                    } catch (IllegalArgumentException e) {
                        sendResponse(out, "501 5.5.4 Invalid Base64 encoding for AUTH PLAIN");
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

                    String candidate = parseAddress(line).toLowerCase(Locale.ROOT);
                    if (candidate.isEmpty()) {
                        sendResponse(out, "501 5.1.3 Bad recipient address format");
                        continue;
                    }

                    if (isLocalDomain(candidate)) {
                        String canonicalRecipient = UserStore.canonical(candidate);
                        if (canonicalRecipient == null || !UserStore.exists(canonicalRecipient)) {
                            sendResponse(out, "550 5.1.1 No such local user");
                        } else {
                            recipients.add(canonicalRecipient);
                            sendResponse(out, "250 OK");
                        }
                    } else {
                        recipients.add(candidate);
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
            System.err.println("[SMTP] Timeout: " + clientSocket.getInetAddress());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (Exception ignored) {}
        }
    }

    private boolean isLocalDomain(String email) {
        if (!email.contains("@")) return true;
        String domain = email.substring(email.indexOf('@') + 1).trim();
        return domain.equalsIgnoreCase(ServerConfig.MAIL_DOMAIN) || domain.equalsIgnoreCase("localhost");
    }

    private String authenticateUser(String clientIp, String username, String password) {
        if (LoginThrottle.isThrottled(clientIp, username)) {
            return null;
        }

        String storedHash = UserStore.findHash(username);
        boolean isLocked = "LOCKED".equalsIgnoreCase(UserStore.getStatus(username));

        if (storedHash != null && !isLocked && SecurityUtils.checkPassword(password, storedHash)) {
            LoginThrottle.resetAttempts(clientIp, username);
            return UserStore.canonical(username);
        } else {
            LoginThrottle.recordFailedAttempt(clientIp, username);
            return null;
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