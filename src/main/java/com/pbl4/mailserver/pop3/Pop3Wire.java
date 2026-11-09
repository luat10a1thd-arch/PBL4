package com.pbl4.mailserver.pop3;

import java.io.IOException;
import java.io.OutputStream;

final class Pop3Wire {

    private static final byte[] CRLF = {'\r', '\n'};

    private Pop3Wire() {}

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

    static void writeMessage(OutputStream out, byte[] raw) throws IOException {
        writeLines(out, raw, -1);
    }

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

    private static int contentEnd(byte[] b, int start, int nl, int n) {
        int end = (nl < 0) ? n : nl;
        if (nl >= 0 && end > start && b[end - 1] == '\r') end--;
        return end;
    }
}
