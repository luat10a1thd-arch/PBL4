package com.pbl4.mailserver.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Bộ đọc dòng có GIỚI HẠN độ dài, dùng thay BufferedReader.readLine() ở SMTP/POP3.
 *
 * Vì sao cần: BufferedReader.readLine() không giới hạn độ dài dòng, một client độc hại
 * chỉ cần gửi vài trăm MB không có ký tự xuống dòng là làm server hết bộ nhớ (OOM).
 *
 * Đặc điểm:
 *  - Kết thúc dòng là LF; CR đứng ngay trước LF bị loại bỏ (chấp nhận cả CRLF lẫn LF
 *    để tương thích client thường và các test dùng println trên Linux).
 *  - Độ dài tính theo BYTE, không tính CR/LF cuối dòng.
 *  - Vượt giới hạn: ném {@link LineTooLongException}; người gọi trả lỗi (SMTP 500 /
 *    POP3 -ERR) rồi đóng kết nối. Sau lỗi này reader không còn dùng được nữa.
 *  - Giới hạn đổi được lúc chạy (SMTP: lệnh ~1024, dòng DATA ~1000).
 *  - Có thể đặt "hạn chót" cho cả phiên: quá hạn sẽ ném SocketTimeoutException ở lần
 *    đọc kế tiếp (chống kiểu tấn công gửi từng byte một cách chậm rãi).
 *  - Không thread-safe (mỗi kết nối dùng một reader, trong một thread).
 */
public class LimitedLineReader {

    /** Dòng dài hơn giới hạn cho phép. */
    public static class LineTooLongException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int limit;

        public LineTooLongException(int limit) {
            super("Line exceeds " + limit + " bytes");
            this.limit = limit;
        }

        public int getLimit() {
            return limit;
        }
    }

    private final InputStream in;
    private final byte[] buf = new byte[4096];
    private int pos = 0;
    private int lim = 0;

    private byte[] lineBuf = new byte[128];
    private int maxLineBytes;

    private boolean hasDeadline = false;
    private long deadlineNanos = 0L;

    public LimitedLineReader(InputStream in, int maxLineBytes) {
        if (in == null) throw new IllegalArgumentException("in == null");
        if (maxLineBytes < 1) throw new IllegalArgumentException("maxLineBytes < 1");
        this.in = in;
        this.maxLineBytes = maxLineBytes;
    }

    /** Đổi giới hạn độ dài dòng (byte, không tính CRLF) cho các lần đọc tiếp theo. */
    public void setMaxLineBytes(int maxLineBytes) {
        if (maxLineBytes < 1) throw new IllegalArgumentException("maxLineBytes < 1");
        this.maxLineBytes = maxLineBytes;
    }

    public int getMaxLineBytes() {
        return maxLineBytes;
    }

    /** Đặt hạn chót tuyệt đối (theo System.nanoTime()) cho cả phiên đọc. */
    public void setDeadlineNanos(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
        this.hasDeadline = true;
    }

    /**
     * true nếu reader còn byte đã đọc sẵn nhưng chưa trả ra. Dùng trước STARTTLS/STLS:
     * nếu còn dữ liệu thô thì client đã gửi chen lệnh vào trước khi bắt tay TLS
     * (tấn công "command injection") nên phải đóng kết nối.
     */
    public boolean hasBufferedData() {
        return pos < lim;
    }

    /**
     * Đọc một dòng (đã bỏ CRLF/LF cuối), giải mã UTF-8.
     *
     * @return dòng đọc được, hoặc null nếu đã hết luồng (EOF) mà chưa có byte nào.
     *         Nếu EOF khi dòng cuối chưa có LF thì vẫn trả phần đã đọc (giống BufferedReader).
     * @throws LineTooLongException dòng vượt giới hạn
     * @throws SocketTimeoutException hết hạn socket hoặc quá hạn chót của phiên
     */
    public String readLine() throws IOException {
        int len = 0;
        boolean any = false;

        for (;;) {
            if (pos >= lim) {
                if (!fill()) {
                    return any ? finish(len) : null;
                }
            }
            byte b = buf[pos++];
            any = true;

            if (b == '\n') {
                return finish(len);
            }

            if (len >= maxLineBytes) {
                // Đã đủ số byte tối đa: chỉ cho phép thêm đúng một CR (đầu của CRLF).
                if (!(len == maxLineBytes && b == '\r')) {
                    throw new LineTooLongException(maxLineBytes);
                }
            }

            if (len == lineBuf.length) {
                lineBuf = java.util.Arrays.copyOf(lineBuf, Math.min(lineBuf.length * 2, maxLineBytes + 1));
            }
            lineBuf[len++] = b;
        }
    }

    private String finish(int len) {
        if (len > 0 && lineBuf[len - 1] == '\r') {
            len--;
        }
        String s = new String(lineBuf, 0, len, StandardCharsets.UTF_8);
        // Đừng giữ mãi bộ đệm lớn nếu giới hạn từng được nới rất cao.
        if (lineBuf.length > 16384) {
            lineBuf = new byte[128];
        }
        return s;
    }

    /** Nạp thêm dữ liệu vào bộ đệm. false nếu EOF. */
    private boolean fill() throws IOException {
        if (hasDeadline && System.nanoTime() - deadlineNanos > 0) {
            throw new SocketTimeoutException("Session time limit exceeded");
        }
        int n = in.read(buf, 0, buf.length);
        if (n <= 0) {
            pos = 0;
            lim = 0;
            return false;
        }
        pos = 0;
        lim = n;
        return true;
    }
}
