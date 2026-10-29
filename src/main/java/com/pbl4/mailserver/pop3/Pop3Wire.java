package com.pbl4.mailserver.pop3;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Ghi thư POP3 theo BYTE (không đi qua String).
 *
 * Quy tắc:
 *  - Dòng kết thúc bằng CRLF. LF trần được chuẩn hóa thành CRLF CHỈ khi gửi; CRLF có sẵn giữ nguyên (không làm đôi).
 *  - Dot-stuffing: dòng bắt đầu bằng '.' thì thêm '.' ở đầu.
 *  - Thư không kết thúc bằng xuống dòng thì tự thêm CRLF để dòng kết thúc "." nằm riêng một dòng.
 *  - Hàm KHÔNG ghi dòng kết thúc ".\r\n"; nơi gọi tự ghi.
 */
final class Pop3Wire {

    private static final byte[] CRLF = {'\r', '\n'};

    private Pop3Wire() {}

    /** Số octet của thư khi gửi: nội dung đã chuẩn hóa CRLF, CHƯA tính dấu '.' dot-stuffing và dòng kết thúc ".". */
    static long octetCount(byte[] raw) {
        long total = 0;
        int n = raw.length;
        int start = 0;
        while (start < n) {
            int nl = indexOfLf(raw, start, n);
            int end = contentEnd(raw, start, nl, n);
            total += (end - start) + 2L;
            start = (nl < 0) ? n : nl + 1;
        }
        return total;
    }

    /** Gửi toàn bộ thư (RETR). */
    static void writeMessage(OutputStream out, byte[] raw) throws IOException {
        writeLines(out, raw, -1);
    }

    /** Gửi header + dòng trống + đúng bodyLines dòng đầu của thân (TOP). */
    static void writeTop(OutputStream out, byte[] raw, int bodyLines) throws IOException {
        writeLines(out, raw, Math.max(0, bodyLines));
    }

    private static void writeLines(OutputStream out, byte[] raw, int maxBodyLines) throws IOException {
        int n = raw.length;
        int start = 0;
        boolean inBody = false;
        int bodyCount = 0;
        while (start < n) {
            int nl = indexOfLf(raw, start, n);
            int end = contentEnd(raw, start, nl, n);
            boolean empty = (end == start);

            if (maxBodyLines >= 0 && inBody) {
                if (bodyCount >= maxBodyLines) break;
                bodyCount++;
            }

            if (end > start && raw[start] == '.') {
                out.write('.');
            }
            out.write(raw, start, end - start);
            out.write(CRLF);

            if (!inBody && empty) inBody = true;
            start = (nl < 0) ? n : nl + 1;
        }
    }

    private static int indexOfLf(byte[] b, int from, int limit) {
        for (int i = from; i < limit; i++) {
            if (b[i] == '\n') return i;
        }
        return -1;
    }

    // Vị trí kết thúc nội dung dòng (không gồm CR của CRLF); nl = vị trí LF hoặc -1 nếu dòng cuối không có LF
    private static int contentEnd(byte[] b, int start, int nl, int n) {
        int end = (nl < 0) ? n : nl;
        if (nl >= 0 && end > start && b[end - 1] == '\r') end--;
        return end;
    }
}