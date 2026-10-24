package com.pbl4.mailserver.core;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * SMTP client dùng để chuyển thư tới máy chủ khác (máy chủ MX của người nhận, hoặc relay).
 * Tự cài đặt theo RFC 5321: EHLO, STARTTLS, AUTH PLAIN (tuỳ chọn), MAIL FROM, RCPT TO, DATA (có dot-stuffing), QUIT.
 */
public final class SmtpDeliveryClient {

    public enum TlsMode {
        NONE,          // không dùng TLS
        OPPORTUNISTIC, // dùng STARTTLS nếu máy chủ có hỗ trợ (kiểu gửi thư giữa các MTA)
        REQUIRED,      // bắt buộc STARTTLS
        IMPLICIT       // TLS ngay từ đầu (cổng 465)
    }

    public static final class Result {
        public final boolean ok;
        public final boolean permanent;  // true: lỗi 5xx, không nên thử lại
        public final boolean tlsFailed;  // bắt tay TLS lỗi: có thể thử lại không dùng TLS
        public final String message;
        public final List<String> rejected;

        Result(boolean ok, boolean permanent, boolean tlsFailed, String message, List<String> rejected) {
            this.ok = ok;
            this.permanent = permanent;
            this.tlsFailed = tlsFailed;
            this.message = message;
            this.rejected = rejected;
        }
    }

    private static final class Reply {
        final int code;
        final String text;

        Reply(int code, String text) {
            this.code = code;
            this.text = text;
        }
    }

    private static final class Conn {
        final BufferedReader in;
        final BufferedWriter out;

        Conn(Socket socket) throws IOException {
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        void write(String line) throws IOException {
            out.write(line);
            out.write("\r\n");
            out.flush();
        }

        Reply cmd(String line) throws IOException {
            write(line);
            return read();
        }

        // Đọc một phản hồi, gồm cả trường hợp nhiều dòng (250-... rồi 250 ...)
        Reply read() throws IOException {
            StringBuilder text = new StringBuilder();
            int code;
            while (true) {
                String line = in.readLine();
                if (line == null) throw new IOException("Máy chủ đóng kết nối đột ngột");
                if (line.length() < 3) throw new IOException("Phản hồi SMTP không hợp lệ: " + line);
                try {
                    code = Integer.parseInt(line.substring(0, 3));
                } catch (NumberFormatException e) {
                    throw new IOException("Phản hồi SMTP không hợp lệ: " + line);
                }
                if (text.length() > 0) text.append(' ');
                text.append(line.length() > 4 ? line.substring(4) : "");
                if (line.length() == 3 || line.charAt(3) != '-') break;
            }
            return new Reply(code, text.toString());
        }
    }

    private SmtpDeliveryClient() {}

    public static Result send(String host, int port, TlsMode tls, boolean verifyHostname,
                              String user, String pass, String heloName,
                              String envelopeFrom, Collection<String> rcpts, String message) {
        Socket socket = null;
        boolean tlsActive = false;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 15_000);
            socket.setSoTimeout(60_000);

            if (tls == TlsMode.IMPLICIT) {
                socket = wrapTls(socket, host, port, verifyHostname);
                tlsActive = true;
            }

            Conn c = new Conn(socket);
            Reply r = c.read();
            if (r.code != 220) return fail(r, "Chào hỏi");

            r = c.cmd("EHLO " + heloName);
            if (r.code != 250) return fail(r, "EHLO");

            if (!tlsActive && (tls == TlsMode.OPPORTUNISTIC || tls == TlsMode.REQUIRED)) {
                boolean offersTls = r.text.toUpperCase(Locale.ROOT).contains("STARTTLS");
                if (offersTls) {
                    r = c.cmd("STARTTLS");
                    if (r.code != 220) return fail(r, "STARTTLS");
                    try {
                        socket = wrapTls(socket, host, port, verifyHostname);
                    } catch (IOException e) {
                        return new Result(false, false, true, "Bắt tay TLS thất bại: " + e.getMessage(), new ArrayList<>());
                    }
                    tlsActive = true;
                    c = new Conn(socket);
                    r = c.cmd("EHLO " + heloName);
                    if (r.code != 250) return fail(r, "EHLO sau TLS");
                } else if (tls == TlsMode.REQUIRED) {
                    return new Result(false, true, false, "Máy chủ không hỗ trợ STARTTLS", new ArrayList<>());
                }
            }

            if (user != null && !user.isEmpty()) {
                if (!tlsActive) {
                    return new Result(false, true, false, "Từ chối gửi mật khẩu khi chưa có TLS", new ArrayList<>());
                }
                String token = Base64.getEncoder().encodeToString(("\0" + user + "\0" + pass).getBytes(StandardCharsets.UTF_8));
                r = c.cmd("AUTH PLAIN " + token);
                if (r.code != 235) return fail(r, "Xác thực");
            }

            r = c.cmd("MAIL FROM:<" + envelopeFrom + ">");
            if (r.code != 250) return fail(r, "MAIL FROM");

            List<String> accepted = new ArrayList<>();
            List<String> rejected = new ArrayList<>();
            for (String rcpt : rcpts) {
                r = c.cmd("RCPT TO:<" + rcpt + ">");
                if (r.code == 250 || r.code == 251) {
                    accepted.add(rcpt);
                } else if (r.code >= 500) {
                    rejected.add(rcpt + " (" + r.code + " " + shorten(r.text) + ")");
                } else {
                    return fail(r, "RCPT TO " + rcpt);
                }
            }
            if (accepted.isEmpty()) {
                return new Result(false, true, false, "Tất cả người nhận bị từ chối: " + String.join("; ", rejected), rejected);
            }

            r = c.cmd("DATA");
            if (r.code != 354) return fail(r, "DATA");

            String[] lines = message.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            for (String line : lines) {
                c.out.write(line.startsWith(".") ? "." + line : line); // dot-stuffing
                c.out.write("\r\n");
            }
            c.out.write(".\r\n");
            c.out.flush();

            r = c.read();
            if (r.code != 250) return fail(r, "Nội dung thư");

            try {
                c.write("QUIT");
            } catch (IOException ignored) {
            }
            return new Result(true, false, false, r.code + " " + shorten(r.text), rejected);

        } catch (IOException e) {
            return new Result(false, false, false, "Lỗi mạng khi kết nối " + host + ":" + port + " - " + e.getMessage(), new ArrayList<>());
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static SSLSocket wrapTls(Socket plain, String host, int port, boolean verifyHostname) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) factory.createSocket(plain, host, port, true);
        if (verifyHostname) {
            SSLParameters params = ssl.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(params);
        }
        ssl.startHandshake();
        return ssl;
    }

    private static Result fail(Reply r, String stage) {
        // 5xx là lỗi vĩnh viễn, trừ lỗi xác thực (530/534/535) vì thường do cấu hình, nên cho thử lại
        boolean permanent = r.code >= 500 && r.code != 530 && r.code != 534 && r.code != 535;
        return new Result(false, permanent, false, stage + ": " + r.code + " " + shorten(r.text), new ArrayList<>());
    }

    private static String shorten(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 400 ? t.substring(0, 400) + "..." : t;
    }
}
