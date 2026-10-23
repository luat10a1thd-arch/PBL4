package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OutboundMailService {

    private static class Attachment {
        String fileName;
        String base64Data;

        Attachment(String fileName, String base64Data) {
            this.fileName = fileName;
            this.base64Data = base64Data;
        }
    }

    public static MailService.DeliveryResult send(String sender, Set<String> externalRecipients, String rawEmailContent) {
        if (!ServerConfig.OUTBOUND_ENABLED) {
            return new MailService.DeliveryResult(false, 0, externalRecipients.size(), "Chức năng gửi thư ra Internet chưa được bật!");
        }

        if (ServerConfig.OUTBOUND_SMTP_HOST.isEmpty() || ServerConfig.OUTBOUND_SMTP_USER.isEmpty()) {
            return new MailService.DeliveryResult(false, 0, externalRecipients.size(), "Cấu hình SMTP Relay chưa hoàn chỉnh!");
        }

        Socket socket = null;
        BufferedReader in = null;
        PrintWriter out = null;
        int successCount = 0;
        int failCount = 0;

        try {
            socket = new Socket(ServerConfig.OUTBOUND_SMTP_HOST, ServerConfig.OUTBOUND_SMTP_PORT);
            socket.setSoTimeout(15000);

            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            if (!checkResponse(in, "220")) {
                return new MailService.DeliveryResult(false, 0, externalRecipients.size(), "Lỗi kết nối tới SMTP Relay Gateway!");
            }

            sendCommand(out, "EHLO " + ServerConfig.MAIL_DOMAIN);
            readUntilLastLine(in);

            sendCommand(out, "STARTTLS");
            String starttlsResp = in.readLine();
            if (starttlsResp == null || !starttlsResp.startsWith("220")) {
                return new MailService.DeliveryResult(false, 0, externalRecipients.size(), "SMTP Relay không hỗ trợ STARTTLS!");
            }

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }}, new java.security.SecureRandom());

            SSLSocketFactory sf = sslContext.getSocketFactory();
            SSLSocket sslSocket = (SSLSocket) sf.createSocket(socket, ServerConfig.OUTBOUND_SMTP_HOST, ServerConfig.OUTBOUND_SMTP_PORT, true);
            sslSocket.startHandshake();

            in = new BufferedReader(new InputStreamReader(sslSocket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(sslSocket.getOutputStream(), StandardCharsets.UTF_8), true);

            sendCommand(out, "EHLO " + ServerConfig.MAIL_DOMAIN);
            readUntilLastLine(in);

            sendCommand(out, "AUTH LOGIN");
            if (!checkResponse(in, "334")) {
                return new MailService.DeliveryResult(false, 0, externalRecipients.size(), "SMTP Relay từ chối phương thức AUTH LOGIN!");
            }

            sendCommand(out, Base64.getEncoder().encodeToString(ServerConfig.OUTBOUND_SMTP_USER.getBytes(StandardCharsets.UTF_8)));
            if (!checkResponse(in, "334")) {
                return new MailService.DeliveryResult(false, 0, externalRecipients.size(), "Sai Username SMTP Relay!");
            }

            sendCommand(out, Base64.getEncoder().encodeToString(ServerConfig.OUTBOUND_SMTP_PASS.getBytes(StandardCharsets.UTF_8)));
            if (!checkResponse(in, "235")) {
                return new MailService.DeliveryResult(false, 0, externalRecipients.size(), "Xác thực SMTP Relay thất bại!");
            }

            String outboundSender = ServerConfig.OUTBOUND_SMTP_USER;
            sendCommand(out, "MAIL FROM:<" + outboundSender + ">");
            if (!checkResponse(in, "250")) {
                return new MailService.DeliveryResult(false, 0, externalRecipients.size(), "MAIL FROM bị SMTP Relay từ chối!");
            }

            Set<String> acceptedRcpts = new java.util.LinkedHashSet<>();
            for (String rcpt : externalRecipients) {
                sendCommand(out, "RCPT TO:<" + rcpt + ">");
                String resp = in.readLine();
                if (resp != null && resp.startsWith("250")) {
                    acceptedRcpts.add(rcpt);
                } else {
                    failCount++;
                }
            }

            if (acceptedRcpts.isEmpty()) {
                sendCommand(out, "QUIT");
                return new MailService.DeliveryResult(false, 0, failCount, "Toàn bộ người nhận external bị từ chối!");
            }

            sendCommand(out, "DATA");
            if (!checkResponse(in, "354")) {
                return new MailService.DeliveryResult(false, 0, externalRecipients.size(), "Lỗi khi gửi lệnh DATA tới Relay!");
            }

            String safeSubject = extractSubject(rawEmailContent);
            String fullBody = extractBody(rawEmailContent);

            String textPart = fullBody;
            List<Attachment> attachments = new ArrayList<>();

            if (fullBody.contains("[ATTACHMENT:")) {
                int firstAttachIdx = fullBody.indexOf("[ATTACHMENT:");
                textPart = fullBody.substring(0, firstAttachIdx).trim();

                Pattern attachPattern = Pattern.compile("\\[ATTACHMENT:([^\\]\\r\\n]+)\\](?:data:[^;]+;base64,)?([A-Za-z0-9+/=\\s]+)\\[/ATTACHMENT\\]");
                Matcher matcher = attachPattern.matcher(fullBody);

                while (matcher.find()) {
                    String fileName = matcher.group(1).trim();
                    String b64Data = matcher.group(2).replaceAll("\\s+", "");
                    attachments.add(new Attachment(fileName, b64Data));
                }
            }

            StringBuilder mimeMsg = new StringBuilder();
            mimeMsg.append("From: ").append(outboundSender).append("\r\n");
            mimeMsg.append("To: ").append(String.join(", ", acceptedRcpts)).append("\r\n");
            mimeMsg.append("Subject: ").append(safeSubject).append("\r\n");
            mimeMsg.append("MIME-Version: 1.0\r\n");

            if (attachments.isEmpty()) {
                mimeMsg.append("Content-Type: text/plain; charset=UTF-8\r\n\r\n");
                mimeMsg.append(textPart);
            } else {
                String boundary = "----=_Part_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
                mimeMsg.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n\r\n");

                mimeMsg.append("--").append(boundary).append("\r\n");
                mimeMsg.append("Content-Type: text/plain; charset=UTF-8\r\n");
                mimeMsg.append("Content-Transfer-Encoding: 8bit\r\n\r\n");
                mimeMsg.append(textPart).append("\r\n\r\n");

                for (Attachment att : attachments) {
                    mimeMsg.append("--").append(boundary).append("\r\n");
                    mimeMsg.append("Content-Type: application/octet-stream; name=\"").append(att.fileName).append("\"\r\n");
                    mimeMsg.append("Content-Transfer-Encoding: base64\r\n");
                    mimeMsg.append("Content-Disposition: attachment; filename=\"").append(att.fileName).append("\"\r\n\r\n");

                    String cleanB64 = att.base64Data;
                    for (int i = 0; i < cleanB64.length(); i += 76) {
                        int end = Math.min(i + 76, cleanB64.length());
                        mimeMsg.append(cleanB64.substring(i, end)).append("\r\n");
                    }
                    mimeMsg.append("\r\n");
                }

                mimeMsg.append("--").append(boundary).append("--");
            }

            out.print(mimeMsg.toString());
            out.print("\r\n.\r\n");
            out.flush();

            if (checkResponse(in, "250")) {
                successCount = acceptedRcpts.size();
            } else {
                failCount += acceptedRcpts.size();
            }

            sendCommand(out, "QUIT");

            if (successCount > 0) {
                return new MailService.DeliveryResult(true, successCount, failCount, "Đã gửi qua SMTP Relay thành công!");
            } else {
                return new MailService.DeliveryResult(false, 0, failCount, "Gửi thư qua SMTP Relay thất bại!");
            }

        } catch (Exception e) {
            return new MailService.DeliveryResult(false, 0, externalRecipients.size(), "Lỗi kết nối Outbound: " + e.getMessage());
        } finally {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }
    }

    private static void sendCommand(PrintWriter out, String cmd) {
        out.print(cmd + "\r\n");
        out.flush();
    }

    private static boolean checkResponse(BufferedReader in, String expectedPrefix) throws Exception {
        String line = in.readLine();
        return line != null && line.startsWith(expectedPrefix);
    }

    private static void readUntilLastLine(BufferedReader in) throws Exception {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.length() >= 4 && line.charAt(3) == ' ') {
                break;
            }
        }
    }

    private static String extractSubject(String raw) {
        for (String line : raw.split("\r?\\n")) {
            if (line.toLowerCase().startsWith("subject:")) {
                return line.substring(8).trim();
            }
        }
        return "No Subject";
    }

    private static String extractBody(String raw) {
        String[] parts = raw.split("\r?\\n\r?\\n", 2);
        return parts.length > 1 ? parts[1] : raw;
    }
}