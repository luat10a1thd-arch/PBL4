package com.pbl4.mailserver.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public class LimitedLineReader {

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

    public void setMaxLineBytes(int maxLineBytes) {
        if (maxLineBytes < 1) throw new IllegalArgumentException("maxLineBytes < 1");
        this.maxLineBytes = maxLineBytes;
    }

    public void setDeadlineNanos(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
        this.hasDeadline = true;
    }

    public boolean hasBufferedData() {
        return pos < lim;
    }

    public int readByte() throws IOException {
        if (pos >= lim && !fill()) {
            return -1;
        }
        return buf[pos++] & 0xFF;
    }

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
        if (lineBuf.length > 16384) {
            lineBuf = new byte[128];
        }
        return s;
    }

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