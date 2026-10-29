package com.pbl4.mailserver.outbound;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Client SMTP mức thấp dùng chung cho MxDeliverer và RelayDeliverer (chỉ dùng trong package outbound).
 * Làm việc hoàn toàn trên byte: thân thư không bao giờ bị đổi sang String.
 */
final class SmtpClient implements Closeable {

    static final int MAX_LINE_BYTES = 8192;
    static final int MAX_REPLY_LINES = 500;

    // ------------------------------------------------------------------ kiểu dữ liệu

    /** Một phản hồi SMTP (đã đọc hết mọi dòng "250-..." cho tới dòng "250 ..."). */
    record Reply(int code, List<String> lines) {
        boolean positive() { return code >= 200 && code < 300; }

        /** 5xx = FAILED; mọi mã khác không thành công (4xx, 3xx bất ngờ...) = DEFERRED. */
        RcptStatus failureStatus() { return code >= 500 && code < 600 ? RcptStatus.FAILED : RcptStatus.DEFERRED; }

        String text() { return String.join("\n", lines); }

        /** Tham số của phần mở rộng EHLO (vd "SIZE 35882577" -> "35882577"); null nếu không quảng cáo. */
        String extensionArgs(String name) {
            for (int i = 1; i < lines.size(); i++) { // dòng đầu là lời chào, không phải extension
                String l = lines.get(i);
                if (l.length() < 4) continue;
                String ext = l.substring(4).trim();
                if (ext.equalsIgnoreCase(name)) return "";
                if (ext.regionMatches(true, 0, name + " ", 0, name.length() + 1)) return ext.substring(name.length() + 1).trim();
            }
            return null;
        }

        boolean hasExtension(String name) { return extensionArgs(name) != null; }

        long sizeLimit() {
            String a = extensionArgs("SIZE");
            if (a == null || a.isEmpty()) return 0;
            try { return Long.parseLong(a.split("\\s+")[0]); } catch (NumberFormatException e) { return 0; }
        }
    }

    /**
     * Kết quả một lần thử giao: hoặc đã có kết luận cho từng người nhận (results != null, không thử MX khác),
     * hoặc cần thử đích kế tiếp (retryReason != null). retryPlain = thử lại cùng IP nhưng không TLS.
     */
    record Outcome(List<RcptResult> results, String retryReason, boolean retryPlain) {
        static Outcome done(List<RcptResult> r) { return new Outcome(r, null, false); }
        static Outcome retry(String reason) { return new Outcome(null, reason, false); }
        static Outcome retryPlain(String reason) { return new Outcome(null, reason, true); }
        boolean finished() { return results != null; }
    }

    // ------------------------------------------------------------------ trạng thái

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "outbound-watchdog");
        t.setDaemon(true);
        return t;
    });

    private volatile Socket socket;
    private InputStream in;
    private OutputStream out;
    private final int commandTimeoutMs;
    private final int dataTimeoutMs;
    private volatile boolean watchdogFired;
    private boolean tls;

    private SmtpClient(Socket s, int commandTimeoutMs, int dataTimeoutMs) throws IOException {
        this.socket = s;
        this.commandTimeoutMs = commandTimeoutMs;
        this.dataTimeoutMs = dataTimeoutMs;
        bind(s);
    }

    private void bind(Socket s) throws IOException {
        this.in = new BufferedInputStream(s.getInputStream(), 8192);
        this.out = new BufferedOutputStream(s.getOutputStream(), 16384);
    }

    static SmtpClient connect(InetAddress ip, int port, int connectTimeoutMs, int commandTimeoutMs, int dataTimeoutMs)
            throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), connectTimeoutMs);
            s.setSoTimeout(commandTimeoutMs);
            s.setTcpNoDelay(true);
            return new SmtpClient(s, commandTimeoutMs, dataTimeoutMs);
        } catch (IOException | RuntimeException e) {
            closeQuietly(s);
            throw e;
        }
    }

    boolean isTls() { return tls; }

    // ------------------------------------------------------------------ đọc / ghi lệnh

    private String readLine() throws IOException {
        byte[] buf = new byte[256];
        int n = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException("Máy chủ đóng kết nối khi đang chờ phản hồi");
            if (b == '\n') break;
            if (n >= MAX_LINE_BYTES) throw new ProtocolException("Dòng phản hồi SMTP quá dài");
            if (n == buf.length) buf = Arrays.copyOf(buf, n * 2);
            buf[n++] = (byte) b;
        }
        if (n > 0 && buf[n - 1] == '\r') n--;
        return new String(buf, 0, n, StandardCharsets.UTF_8);
    }

    /** Đọc một phản hồi đầy đủ; phản hồi nhiều dòng ("250-") được đọc hết. */
    Reply readReply() throws IOException {
        List<String> lines = new ArrayList<>();
        int code = -1;
        while (true) {
            String line = readLine();
            if (line.length() < 3 || !isDigit(line.charAt(0)) || !isDigit(line.charAt(1)) || !isDigit(line.charAt(2))) {
                throw new ProtocolException("Phản hồi SMTP không hợp lệ: " + abbreviate(line, 80));
            }
            int c = Integer.parseInt(line.substring(0, 3));
            if (code == -1) code = c;
            else if (c != code) throw new ProtocolException("Mã phản hồi nhiều dòng không nhất quán: " + abbreviate(line, 80));
            lines.add(line);
            if (line.length() == 3 || line.charAt(3) == ' ') break;
            if (line.charAt(3) != '-') throw new ProtocolException("Phản hồi SMTP không hợp lệ: " + abbreviate(line, 80));
            if (lines.size() >= MAX_REPLY_LINES) throw new ProtocolException("Phản hồi SMTP có quá nhiều dòng");
        }
        return new Reply(code, List.copyOf(lines));
    }

    void send(String line) throws IOException {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\r' || c == '\n' || c == 0) throw new IOException("Lệnh SMTP chứa ký tự điều khiển không hợp lệ");
        }
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.write('\r');
        out.write('\n');
        out.flush();
    }

    Reply command(String line) throws IOException {
        send(line);
        return readReply();
    }

    /** EHLO; nếu máy chủ trả 5xx thì lùi về HELO (khi đó không có phần mở rộng nào). */
    Reply ehlo(String name) throws IOException {
        Reply r = command("EHLO " + name);
        if (r.code() >= 500 && r.code() < 600) r = command("HELO " + name);
        return r;
    }

    void setSoTimeout(int ms) throws IOException { socket.setSoTimeout(ms); }

    // ------------------------------------------------------------------ TLS

    /**
     * Nâng cấp kết nối hiện tại lên TLS (TLS 1.2+). verifyHostname=true bật kiểm tra tên máy chủ theo chứng chỉ.
     * Lỗi: ném IOException (kết nối coi như hỏng, người gọi phải đóng).
     */
    void upgradeTls(SSLContext ctx, boolean verifyHostname, String sniHost, int port) throws IOException {
        SSLSocket ss = (SSLSocket) ctx.getSocketFactory().createSocket(socket, sniHost, port, true);
        try {
            List<String> protos = new ArrayList<>();
            List<String> supported = Arrays.asList(ss.getSupportedProtocols());
            if (supported.contains("TLSv1.3")) protos.add("TLSv1.3");
            if (supported.contains("TLSv1.2")) protos.add("TLSv1.2");
            if (protos.isEmpty()) throw new SSLException("JVM không hỗ trợ TLS 1.2 trở lên");
            ss.setEnabledProtocols(protos.toArray(new String[0]));
            ss.setUseClientMode(true);
            if (verifyHostname) {
                SSLParameters p = ss.getSSLParameters();
                p.setEndpointIdentificationAlgorithm("HTTPS");
                ss.setSSLParameters(p);
            }
            ss.setSoTimeout(commandTimeoutMs);
            ss.startHandshake();
            this.socket = ss;
            bind(ss);
            this.tls = true;
        } catch (IOException | RuntimeException e) {
            closeQuietly(ss);
            throw e;
        }
    }

    /** Ngữ cảnh TLS không kiểm chứng chứng chỉ (chỉ dùng cho STARTTLS cơ hội, tương tự các MTA phổ biến). */
    static SSLContext trustAllContext() throws GeneralSecurityException {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509ExtendedTrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) {}
            @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) {}
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());
        return ctx;
    }

    // ------------------------------------------------------------------ DATA

    /**
     * Gửi thân thư: dot-stuffing trên byte (thêm '.' ở đầu mỗi dòng bắt đầu bằng '.'; "đầu dòng" = đầu thư hoặc ngay sau CRLF),
     * bảo đảm kết thúc bằng CRLF (chỉ thêm khi thiếu) rồi ".\r\n". Không sửa bất kỳ byte nào khác.
     */
    void sendData(byte[] raw) throws IOException {
        byte[] buf = new byte[16384];
        int n = 0;
        boolean lineStart = true;
        for (int i = 0; i < raw.length; i++) {
            if (n > buf.length - 4) {
                out.write(buf, 0, n);
                n = 0;
            }
            byte b = raw[i];
            if (lineStart && b == '.') buf[n++] = '.';
            buf[n++] = b;
            lineStart = b == '\n' && i > 0 && raw[i - 1] == '\r';
        }
        if (raw.length > 0 && !lineStart) { // thư không kết thúc bằng CRLF
            buf[n++] = '\r';
            buf[n++] = '\n';
        }
        if (n > buf.length - 3) {
            out.write(buf, 0, n);
            n = 0;
        }
        buf[n++] = '.';
        buf[n++] = '\r';
        buf[n++] = '\n';
        out.write(buf, 0, n);
        out.flush();
    }

    private Runnable armWatchdog(long ms) {
        if (ms <= 0) return () -> {};
        ScheduledFuture<?> f = WATCHDOG.schedule(() -> {
            watchdogFired = true;
            closeQuietly(socket);
        }, ms, TimeUnit.MILLISECONDS);
        return () -> f.cancel(false);
    }

    // ------------------------------------------------------------------ giao dịch MAIL/RCPT/DATA

    /**
     * Chạy MAIL FROM, RCPT TO từng người, DATA và phân loại kết quả theo TỪNG người nhận.
     * Kết quả luôn cùng thứ tự/số lượng với rcpts (khi Outcome.finished()).
     */
    static Outcome transact(SmtpClient c, String mailFrom, List<String> rcpts, byte[] raw, long sizeLimit) throws IOException {
        int n = rcpts.size();
        if (sizeLimit > 0 && raw.length > sizeLimit) {
            c.quitQuietly();
            return Outcome.done(all(rcpts, RcptStatus.FAILED,
                    "Thư (" + raw.length + " byte) vượt giới hạn SIZE " + sizeLimit + " mà máy chủ nhận quảng cáo"));
        }

        Reply r = c.command("MAIL FROM:<" + mailFrom + ">");
        if (r.code() == 421) return Outcome.retry("MAIL FROM: " + r.text());
        if (!r.positive()) {
            c.quitQuietly();
            return Outcome.done(all(rcpts, r.failureStatus(), r.text()));
        }

        RcptResult[] res = new RcptResult[n];
        List<Integer> accepted = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            r = c.command("RCPT TO:<" + rcpts.get(i) + ">");
            if (r.code() == 421) return Outcome.retry("RCPT TO: " + r.text()); // chưa gửi DATA nên thử MX khác là an toàn
            if (r.positive()) accepted.add(i);
            else res[i] = new RcptResult(rcpts.get(i), r.failureStatus(), r.text());
        }
        if (accepted.isEmpty()) {
            c.quitQuietly();
            return Outcome.done(asList(res));
        }

        r = c.command("DATA");
        if (r.code() != 354) {
            if (r.code() == 421) return Outcome.retry("DATA: " + r.text());
            fill(res, rcpts, accepted, r.failureStatus(), r.text());
            c.quitQuietly();
            return Outcome.done(asList(res));
        }

        Runnable disarm = c.armWatchdog(c.dataTimeoutMs);
        Reply fin;
        try {
            c.setSoTimeout(c.dataTimeoutMs);
            c.sendData(raw);
            fin = c.readReply();
        } catch (IOException e) {
            // Đã (một phần) gửi DATA: không thử MX khác để tránh nhân đôi thư; hàng đợi sẽ thử lại sau.
            String why = c.watchdogFired
                    ? "quá hạn " + (c.dataTimeoutMs / 1000) + "s cho giai đoạn DATA"
                    : describe(e);
            fill(res, rcpts, accepted, RcptStatus.DEFERRED, "Mất kết nối khi gửi DATA hoặc chờ kết quả (" + why + "); kết quả không xác định");
            return Outcome.done(asList(res));
        } finally {
            disarm.run();
        }
        try { c.setSoTimeout(c.commandTimeoutMs); } catch (IOException ignored) {}

        fill(res, rcpts, accepted, fin.positive() ? RcptStatus.DELIVERED : fin.failureStatus(), fin.text());
        c.quitQuietly();
        return Outcome.done(asList(res));
    }

    void quitQuietly() {
        try {
            send("QUIT");
            socket.setSoTimeout(5000);
            readReply();
        } catch (IOException ignored) {
            // không quan trọng: thư đã có kết luận
        }
    }

    // ------------------------------------------------------------------ tiện ích

    private static void fill(RcptResult[] res, List<String> rcpts, List<Integer> idx, RcptStatus st, String diag) {
        for (int i : idx) res[i] = new RcptResult(rcpts.get(i), st, diag);
    }

    private static List<RcptResult> asList(RcptResult[] res) {
        List<RcptResult> l = new ArrayList<>(res.length);
        for (RcptResult r : res) l.add(r);
        return l;
    }

    /** Cùng một kết quả cho mọi người nhận. */
    static List<RcptResult> all(List<String> rcpts, RcptStatus st, String diag) {
        List<RcptResult> l = new ArrayList<>(rcpts.size());
        for (String r : rcpts) l.add(new RcptResult(r, st, diag));
        return l;
    }

    /** Địa chỉ phong bì an toàn để đưa vào lệnh SMTP (chặn chèn CRLF/lệnh, ký tự điều khiển, khoảng trắng, < >). */
    static boolean isSafeAddress(String a, boolean allowEmpty) {
        if (a == null) return false;
        if (a.isEmpty()) return allowEmpty;
        if (a.length() > 320 || a.indexOf('@') <= 0 || a.indexOf('@') == a.length() - 1) return false;
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            if (c <= 0x20 || c == 0x7f || c == '<' || c == '>') return false;
        }
        return true;
    }

    static String describe(IOException e) {
        if (e instanceof SocketTimeoutException) return "hết thời gian chờ (timeout)";
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

    static String abbreviate(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "..."; }

    private static void closeQuietly(Socket s) {
        try { if (s != null) s.close(); } catch (IOException ignored) {}
    }

    @Override
    public void close() { closeQuietly(socket); }

    void closeQuietly() { close(); }
}
