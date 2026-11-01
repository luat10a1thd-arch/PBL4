package com.pbl4.mailserver.smtp.wire;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.LimitedLineReader;
import com.pbl4.mailserver.core.SslContextFactory;

import javax.net.ssl.SSLSocket;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Một kết nối SMTP ở mức BYTE. Không xử lý lệnh SMTP (việc của MxHandler/SubmissionHandler).
 *
 * Quy ước:
 *  - readCommand(): dòng quá dài -> tự gửi "500 5.5.2 Line too long" rồi ném
 *    LimitedLineReader.LineTooLongException; handler chỉ cần kết thúc phiên (không trả lời thêm).
 *  - startTls(): handler gửi "220 ..." TRƯỚC khi gọi. Trả false nghĩa là kết nối ĐÃ bị đóng
 *    (bắt tay lỗi, thiếu chứng chỉ, hoặc client gửi chen lệnh trước khi bắt tay) -> handler thoát.
 *    Ngoại lệ: đã ở TLS thì trả false mà KHÔNG đóng. Dùng {@link #tlsAvailable()} để kiểm tra
 *    trước khi trả 220 (nếu không có chứng chỉ thì trả 454).
 *  - Timeout đọc mặc định PREAUTH_TIMEOUT_MS; gọi {@link #setAuthenticated(boolean)} sau AUTH để
 *    chuyển sang POSTAUTH_TIMEOUT_MS (hoặc setTimeoutMs tùy ý). readData() tạm nâng timeout lên
 *    ít nhất POSTAUTH_TIMEOUT_MS. Cả phiên bị chặn bởi SESSION_MAX_MS.
 *  - Không thread-safe: một kết nối, một thread.
 */
public final class SmtpConnection implements AutoCloseable {

    /** Dòng AUTH base64 (RFC 4954 gợi ý 12288 byte). */
    private static final int AUTH_LINE_MAX_BYTES = 12288;
    private static final int HANDSHAKE_MIN_TIMEOUT_MS = 10_000;
    private static final long SWALLOW_EXTRA_BYTES = 1L << 20;

    private Socket socket;
    private final String remoteIp;
    private InputStream in;
    private OutputStream out;
    private LimitedLineReader reader;
    private boolean tls = false;
    private boolean closed = false;
    private int timeoutMs;
    private final long deadlineNanos;
    private int maxDataLineBytes = ServerConfig.SMTP_MAX_DATA_LINE_BYTES;

    public SmtpConnection(Socket socket) throws IOException {
        this(socket, false);
    }

    /**
     * @param implicitTls true: bắt tay TLS ngay (cổng 465). Ném IOException nếu bắt tay lỗi
     *                    (socket đã được đóng).
     */
    public SmtpConnection(Socket socket, boolean implicitTls) throws IOException {
        this.socket = socket;
        this.remoteIp = extractIp(socket);
        this.deadlineNanos = System.nanoTime() + ServerConfig.SESSION_MAX_MS * 1_000_000L;
        try {
            socket.setTcpNoDelay(true);
        } catch (SocketException ignored) {}
        setTimeoutMs(ServerConfig.PREAUTH_TIMEOUT_MS);
        if (implicitTls) {
            if (!upgradeToTls()) {
                throw new IOException("Bắt tay TLS (implicit) thất bại");
            }
        } else {
            attachStreams(socket);
        }
    }

    private static String extractIp(Socket s) {
        InetAddress a = s.getInetAddress();
        if (a == null) return "unknown";
        String h = a.getHostAddress();
        int pct = h.indexOf('%');
        return pct >= 0 ? h.substring(0, pct) : h;
    }

    private void attachStreams(Socket s) throws IOException {
        this.in = s.getInputStream();
        this.out = new BufferedOutputStream(s.getOutputStream(), 8192);
        this.reader = new LimitedLineReader(in, ServerConfig.MAX_COMMAND_LINE_BYTES);
        this.reader.setDeadlineNanos(deadlineNanos);
    }

    // ------------------------------------------------------------------ thông tin

    public String remoteIp() {
        return remoteIp;
    }

    public boolean isTls() {
        return tls;
    }

    public boolean isClosed() {
        return closed;
    }

    /** true nếu máy chủ có chứng chỉ để làm STARTTLS. */
    public boolean tlsAvailable() {
        return SslContextFactory.createSSLContext() != null;
    }

    public void setTimeoutMs(int ms) {
        if (ms < 1) ms = 1;
        this.timeoutMs = ms;
        try {
            socket.setSoTimeout(ms);
        } catch (SocketException ignored) {}
    }

    /** Đã xác thực: dùng timeout POSTAUTH; chưa: PREAUTH. */
    public void setAuthenticated(boolean authenticated) {
        setTimeoutMs(authenticated ? ServerConfig.POSTAUTH_TIMEOUT_MS : ServerConfig.PREAUTH_TIMEOUT_MS);
    }

    /** Đổi giới hạn độ dài dòng trong DATA (mặc định SMTP_MAX_DATA_LINE_BYTES). */
    public void setMaxDataLineBytes(int bytes) {
        if (bytes < 1) throw new IllegalArgumentException("bytes < 1");
        this.maxDataLineBytes = bytes;
    }

    // ------------------------------------------------------------------ đọc

    /** Một dòng lệnh (đã bỏ CRLF/LF); null nếu client đóng kết nối. */
    public String readCommand() throws IOException {
        reader.setMaxLineBytes(ServerConfig.MAX_COMMAND_LINE_BYTES);
        return readLineChecked();
    }

    /** Dòng thô (ví dụ phản hồi base64 của AUTH), giới hạn lớn hơn lệnh thường; null nếu client đóng. */
    public String readRawLine() throws IOException {
        reader.setMaxLineBytes(AUTH_LINE_MAX_BYTES);
        try {
            return readLineChecked();
        } finally {
            reader.setMaxLineBytes(ServerConfig.MAX_COMMAND_LINE_BYTES);
        }
    }

    private String readLineChecked() throws IOException {
        try {
            return reader.readLine();
        } catch (LimitedLineReader.LineTooLongException e) {
            try {
                reply("500 5.5.2 Line too long");
            } catch (IOException ignored) {}
            throw e;
        }
    }

    /**
     * Đọc nội dung thư tới dòng chỉ có ".". Dot-unstuff trên byte, mọi dòng kết thúc bằng CRLF
     * (LF trần được chuẩn hóa thành CRLF; CR lẻ giữa dòng giữ nguyên), byte nhị phân giữ nguyên.
     * Kết quả KHÔNG gồm dòng "." kết thúc.
     *
     * Vượt maxBytes hoặc có dòng quá dài: nuốt hết phần còn lại tới dòng "." rồi ném
     * {@link DataTooLargeException} / {@link DataLineTooLongException} (phiên vẫn đồng bộ).
     * Client đóng giữa chừng: ném EOFException. Gửi quá nhiều dữ liệu sau khi đã vượt hạn mức
     * (> 4 x maxBytes + 1MB): ném IOException thường (nên đóng kết nối).
     */
    public byte[] readData(long maxBytes) throws IOException {
        int saved = timeoutMs;
        int dataTimeout = Math.max(saved, ServerConfig.POSTAUTH_TIMEOUT_MS);
        if (dataTimeout != saved) setTimeoutMs(dataTimeout);
        try {
            return readDataInternal(maxBytes);
        } finally {
            if (dataTimeout != saved) setTimeoutMs(saved);
        }
    }

    private byte[] readDataInternal(long maxBytes) throws IOException {
        final int lineLimit = maxDataLineBytes;
        final int cap = lineLimit + 2; // chứa tối đa: dấu chấm "stuffing" + nội dung + CR
        final long limit = Math.min(maxBytes, Integer.MAX_VALUE - 16L);
        final long swallowCap = Math.max(limit, 0L) * 4 + SWALLOW_EXTRA_BYTES;

        byte[] line = new byte[Math.min(cap, 1024)];
        byte[] data = new byte[(int) Math.max(256L, Math.min(limit, 16384L))];
        int dataLen = 0;
        boolean tooLarge = false;
        boolean lineTooLong = false;
        long consumed = 0;

        for (;;) {
            int len = 0;
            boolean overflow = false;
            int b;
            for (;;) {
                b = reader.readByte();
                if (b < 0) {
                    throw new EOFException("Client đóng kết nối giữa chừng khi đang gửi DATA");
                }
                consumed++;
                if (b == '\n') break;
                if (len < cap) {
                    if (len == line.length) {
                        line = Arrays.copyOf(line, Math.min(line.length * 2, cap));
                    }
                    line[len++] = (byte) b;
                } else {
                    overflow = true;
                    if (consumed > swallowCap) {
                        throw new IOException("Client gửi quá nhiều dữ liệu sau khi vượt giới hạn");
                    }
                }
            }
            if ((tooLarge || lineTooLong) && consumed > swallowCap) {
                throw new IOException("Client gửi quá nhiều dữ liệu sau khi vượt giới hạn");
            }
            if (overflow) {
                lineTooLong = true;
                continue;
            }
            if (len > 0 && line[len - 1] == '\r') {
                len--;
            }
            if (len == 1 && line[0] == '.') {
                break; // dòng kết thúc
            }
            int start = (len > 0 && line[0] == '.') ? 1 : 0; // bỏ một dấu chấm stuffing
            int content = len - start;
            if (content > lineLimit) {
                lineTooLong = true;
                continue;
            }
            if (tooLarge || lineTooLong) {
                continue;
            }
            int need = dataLen + content + 2;
            if (need > limit) {
                tooLarge = true;
                data = null; // giải phóng bộ nhớ, chỉ nuốt tiếp
                continue;
            }
            if (need > data.length) {
                data = Arrays.copyOf(data, (int) Math.min(Math.max(data.length * 2L, need), Integer.MAX_VALUE - 8L));
            }
            System.arraycopy(line, start, data, dataLen, content);
            dataLen += content;
            data[dataLen++] = '\r';
            data[dataLen++] = '\n';
        }

        if (tooLarge) {
            throw new DataTooLargeException("Thư vượt quá " + limit + " byte");
        }
        if (lineTooLong) {
            throw new DataLineTooLongException(lineLimit);
        }
        return dataLen == data.length ? data : Arrays.copyOf(data, dataLen);
    }

    // ------------------------------------------------------------------ ghi

    /** Gửi một dòng trả lời (tự thêm CRLF, flush). CR/LF trong nội dung bị thay bằng dấu cách. */
    public void reply(String fullLine) throws IOException {
        writeLine(fullLine);
        out.flush();
    }

    /** Trả lời nhiều dòng: "code-text" ... "code text". Danh sách rỗng gửi "code ". */
    public void replyMulti(int code, List<String> lines) throws IOException {
        if (lines == null || lines.isEmpty()) {
            writeLine(code + " ");
        } else {
            int last = lines.size() - 1;
            for (int i = 0; i < last; i++) {
                writeLine(code + "-" + lines.get(i));
            }
            writeLine(code + " " + lines.get(last));
        }
        out.flush();
    }

    private void writeLine(String s) throws IOException {
        if (s == null) s = "";
        if (s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0) {
            s = s.replace('\r', ' ').replace('\n', ' ');
        }
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.write('\r');
        out.write('\n');
    }

    // ------------------------------------------------------------------ TLS

    /** Nâng cấp tại chỗ lên TLS (server mode). Xem ghi chú đầu lớp về ý nghĩa giá trị false. */
    public boolean startTls() {
        if (closed || tls) {
            return false;
        }
        if (reader.hasBufferedData()) {
            // Client gửi chen dữ liệu trước khi bắt tay: nguy cơ command injection.
            System.err.println("[SMTP] STARTTLS bị từ chối: có dữ liệu chen trước bắt tay từ " + remoteIp);
            close();
            return false;
        }
        return upgradeToTls();
    }

    private boolean upgradeToTls() {
        SSLSocket ssl = null;
        try {
            ssl = SslContextFactory.wrapServerSocket(socket);
            ssl.setSoTimeout(Math.max(timeoutMs, HANDSHAKE_MIN_TIMEOUT_MS));
            ssl.startHandshake();
            ssl.setSoTimeout(timeoutMs);
            this.socket = ssl;
            attachStreams(ssl);
            this.tls = true;
            return true;
        } catch (Exception e) {
            if (ssl != null) {
                try {
                    ssl.close();
                } catch (IOException ignored) {}
            }
            close();
            return false;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {}
    }
}