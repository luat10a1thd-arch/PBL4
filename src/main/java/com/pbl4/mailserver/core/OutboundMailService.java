package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class OutboundMailService implements OutboundSender {

    private static final OutboundMailService INSTANCE = new OutboundMailService();
    private static final long OVERALL_TIMEOUT_MS = 60000;

    public static OutboundMailService getInstance() {
        return INSTANCE;
    }

    private static class Attachment {
        final String fileName;
        final String base64Data;

        Attachment(String fileName, String base64Data) {
            this.fileName = fileName;
            this.base64Data = base64Data;
        }
    }

    @Override
    public MailService.DeliveryResult send(String sender, Set<String> externalRecipients, String rawEmailContent) {
        if (!ServerConfig.OUTBOUND_ENABLED) {
            return new MailService.DeliveryResult(false, 0, externalRecipients.size(), "Chức năng gửi thư ra Internet chưa được bật!");
        }
        if (ServerConfig.OUTBOUND_SMTP_HOST.isEmpty() || ServerConfig.OUTBOUND_SMTP_USER.isEmpty()) {
            return new MailService.DeliveryResult(false, 0, externalRecipients.size(), "Cấu hình SMTP Relay chưa hoàn chỉnh!");
        }

        Socket plainSocket = null;
        SSLSocket sslSocket = null;
        BufferedReader in = null;
        PrintWriter out = null;

        int successCount = 0;
        int failCount = 0;
        long sessionStartTime = System.currentTimeMillis();

        try {
            plainSocket = new Socket();
            plainSocket.connect(new InetSocketAddress(ServerConfig.OUTBOUND_SMTP_HOST, ServerConfig.OUTBOUND_SMTP_PORT), 5000);
            plainSocket.setSoTimeout(15000);

            in = new BufferedReader(new InputStreamReader(plainSocket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(plainSocket.getOutputStream(), StandardCharsets.UTF_8), true);

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

            sslSocket = createSslSocket(plainSocket);
            sslSocket.startHandshake();

            in = new BufferedReader(new InputStreamReader(sslSocket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(sslSocket.getOutputStream(), StandardCharsets.UTF_8), true);

            sendCommand(out, "EHLO " + ServerConfig.MAIL_DOMAIN);
            readUntilLastLine(in);

            if (!authenticateRelay(in, out)) {
                return new MailService.DeliveryResult(false, 0, externalRecipients.size(), "Xác thực SMTP Relay thất bại!");
            }

            String outboundSender = ServerConfig.OUTBOUND_SMTP_USER;
            sendCommand(out, "MAIL FROM:<" + outboundSender + ">");
            if (!checkResponse(in, "250")) {
                return new MailService.DeliveryResult(false, 0, externalRecipients.size(), "MAIL FROM bị SMTP Relay từ chối!");
            }

            Set<String> acceptedRcpts = new LinkedHashSet<>();
            for (String rcpt : externalRecipients) {
                if (System.currentTimeMillis() - sessionStartTime > OVERALL_TIMEOUT_MS) {
                    throw new IOException("Quá hạn thời gian tổng phiên gửi thư ra ngoài");
                }
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

            streamMimeContent(out, sender, acceptedRcpts, rawEmailContent);

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
            closeQuietly(in, out, sslSocket, plainSocket);
        }
    }

    private SSLSocket createSslSocket(Socket baseSocket) throws Exception {
        SSLContext sslContext;
        if (ServerConfig.OUTBOUND_TRUST_ALL) {
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }}, new java.security.SecureRandom());
        } else {
            sslContext = SSLContext.getDefault();
        }

        SSLSocketFactory sf = sslContext.getSocketFactory();
        SSLSocket sslSocket = (SSLSocket) sf.createSocket(baseSocket, ServerConfig.OUTBOUND_SMTP_HOST, ServerConfig.OUTBOUND_SMTP_PORT, true);

        if (!ServerConfig.OUTBOUND_TRUST_ALL) {
            SSLParameters sslParams = sslSocket.getSSLParameters();
            sslParams.setEndpointIdentificationAlgorithm("HTTPS");
            sslSocket.setSSLParameters(sslParams);
        }
        return sslSocket;
    }

    private boolean authenticateRelay(BufferedReader in, PrintWriter out) throws Exception {
        sendCommand(out, "AUTH LOGIN");
        if (!checkResponse(in, "334")) return false;

        sendCommand(out, Base64.getEncoder().encodeToString(ServerConfig.OUTBOUND_SMTP_USER.getBytes(StandardCharsets.UTF_8)));
        if (!checkResponse(in, "334")) return false;

        sendCommand(out, Base64.getEncoder().encodeToString(ServerConfig.OUTBOUND_SMTP_PASS.getBytes(StandardCharsets.UTF_8)));
        return checkResponse(in, "235");
    }

    private void streamMimeContent(PrintWriter out, String sender, Set<String> acceptedRcpts, String rawEmailContent) {
        String safeSubject = extractSubject(rawEmailContent);
        String fullBody = extractBody(rawEmailContent);

        String textPart = fullBody;
        List<Attachment> attachments = new ArrayList<>();

        int attachIdx = fullBody.indexOf("[ATTACHMENT:");
        if (attachIdx != -1) {
            textPart = fullBody.substring(0, attachIdx).trim();
            int curr = attachIdx;
            while (curr != -1) {
                int nameEnd = fullBody.indexOf("]", curr);
                int endTag = fullBody.indexOf("[/ATTACHMENT]", curr);

                if (nameEnd != -1 && endTag != -1 && nameEnd < endTag) {
                    String fileName = fullBody.substring(curr + 12, nameEnd).trim();
                    String dataPart = fullBody.substring(nameEnd + 1, endTag).trim();

                    if (dataPart.startsWith("data:")) {
                        int comma = dataPart.indexOf(",");
                        if (comma != -1) dataPart = dataPart.substring(comma + 1).trim();
                    }

                    StringBuilder cleanB64 = new StringBuilder();
                    for (int k = 0; k < dataPart.length(); k++) {
                        char c = dataPart.charAt(k);
                        if (!Character.isWhitespace(c)) cleanB64.append(c);
                    }

                    attachments.add(new Attachment(fileName, cleanB64.toString()));
                    curr = fullBody.indexOf("[ATTACHMENT:", endTag);
                } else {
                    break;
                }
            }
        }

        out.print("From: " + sender + "\r\n");
        out.print("To: " + String.join(", ", acceptedRcpts) + "\r\n");
        out.print("Subject: " + safeSubject + "\r\n");
        out.print("MIME-Version: 1.0\r\n");

        if (attachments.isEmpty()) {
            out.print("Content-Type: text/plain; charset=UTF-8\r\n\r\n");
            streamTextWithDotStuffing(out, textPart);
        } else {
            String boundary = "----=_Part_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
            out.print("Content-Type: multipart/mixed; boundary=\"" + boundary + "\"\r\n\r\n");

            out.print("--" + boundary + "\r\n");
            out.print("Content-Type: text/plain; charset=UTF-8\r\n");
            out.print("Content-Transfer-Encoding: 8bit\r\n\r\n");
            streamTextWithDotStuffing(out, textPart);
            out.print("\r\n\r\n");

            for (Attachment att : attachments) {
                out.print("--" + boundary + "\r\n");
                out.print("Content-Type: application/octet-stream; name=\"" + att.fileName + "\"\r\n");
                out.print("Content-Transfer-Encoding: base64\r\n");
                out.print("Content-Disposition: attachment; filename=\"" + att.fileName + "\"\r\n\r\n");

                String b64 = att.base64Data;
                for (int i = 0; i < b64.length(); i += 76) {
                    int end = Math.min(i + 76, b64.length());
                    out.print(b64.substring(i, end) + "\r\n");
                }
                out.print("\r\n");
            }
            out.print("--" + boundary + "--");
        }

        out.print("\r\n.\r\n");
        out.flush();
    }

    private static void streamTextWithDotStuffing(PrintWriter out, String text) {
        if (text == null) return;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith(".")) {
                out.print("." + line);
            } else {
                out.print(line);
            }
            if (i < lines.length - 1) {
                out.print("\r\n");
            }
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

    private static void closeQuietly(AutoCloseable... closeables) {
        for (AutoCloseable c : closeables) {
            if (c != null) {
                try { c.close(); } catch (Exception ignored) {}
            }
        }
    }
}
