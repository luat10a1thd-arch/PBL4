package com.pbl4.mailserver.mime;

import com.pbl4.mailserver.config.ServerConfig;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MessageNormalizer {

    private static final Logger LOG = Logger.getLogger(MessageNormalizer.class.getName());

    static final int MAX_LINE = 998;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private MessageNormalizer() {
    }

    public static byte[] normalize(byte[] raw, String envelopeFrom) {
        try {
            return normalize(raw, envelopeFrom, ServerConfig.MAIL_HOSTNAME);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Không chuẩn hóa được thư, giữ nguyên bản gốc", t);
            return raw;
        }
    }

    static byte[] normalize(byte[] raw, String envelopeFrom, String hostname) {
        if (raw == null || raw.length == 0) {
            return raw;
        }
        final int n = raw.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(n + 256);

        boolean hasMessageId = false;
        boolean hasDate = false;
        boolean hasMime = false;
        boolean haveField = false;
        int bodyStart = n;

        int pos = 0;
        while (pos < n) {
            int eol = pos;
            while (eol < n && raw[eol] != '\n') {
                eol++;
            }
            int contentEnd = eol;
            if (eol < n && contentEnd > pos && raw[contentEnd - 1] == '\r') {
                contentEnd--;
            }
            int next = (eol < n) ? eol + 1 : n;

            if (contentEnd == pos) {
                bodyStart = next;
                break;
            }

            if (isWsp(raw[pos])) {
                if (!haveField) {
                    bodyStart = pos;
                    break;
                }
            } else {
                int nameEnd = fieldNameEnd(raw, pos, contentEnd);
                if (nameEnd < 0) {
                    bodyStart = pos;
                    break;
                }
                haveField = true;
                String name = new String(raw, pos, nameEnd - pos, StandardCharsets.ISO_8859_1)
                        .toLowerCase(Locale.ROOT);
                switch (name) {
                    case "message-id" -> hasMessageId = true;
                    case "date" -> hasDate = true;
                    case "mime-version" -> hasMime = true;
                    default -> { }
                }
            }

            writeFolded(out, raw, pos, contentEnd, MAX_LINE);
            out.write('\r');
            out.write('\n');
            pos = next;
        }

        if (!hasMessageId) {
            writeAscii(out, "Message-ID: " + newMessageId(hostname) + "\r\n");
        }
        if (!hasDate) {
            writeAscii(out, "Date: " + formatDate(ZonedDateTime.now()) + "\r\n");
        }
        if (!hasMime) {
            writeAscii(out, "MIME-Version: 1.0\r\n");
        }
        out.write('\r');
        out.write('\n');

        copyBodyAsCrlf(out, raw, bodyStart, n);
        return out.toByteArray();
    }

    static String newMessageId(String hostname) {
        StringBuilder host = new StringBuilder();
        if (hostname != null) {
            for (int i = 0; i < hostname.length(); i++) {
                char c = hostname.charAt(i);
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-') {
                    host.append(c);
                }
            }
        }
        if (host.length() == 0) {
            host.append("localhost");
        }
        return "<" + UUID.randomUUID() + "@" + host + ">";
    }

    static String formatDate(ZonedDateTime t) {
        return DATE_FMT.format(t);
    }

    static void writeFolded(ByteArrayOutputStream out, byte[] b, int s, int e, int limit) {
        while (e - s > limit) {
            int cut = findFoldPoint(b, s, e, limit);
            if (cut < 0) {
                break;
            }
            out.write(b, s, cut - s);
            out.write('\r');
            out.write('\n');
            s = cut;
        }
        out.write(b, s, e - s);
    }

    private static int findFoldPoint(byte[] b, int s, int e, int limit) {
        int first = s;
        while (first < e && isWsp(b[first])) {
            first++;
        }
        int last = e - 1;
        while (last >= s && isWsp(b[last])) {
            last--;
        }
        if (first >= last) {
            return -1;
        }
        for (int i = Math.min(s + limit, last - 1); i > first; i--) {
            if (isWsp(b[i])) {
                return i;
            }
        }
        for (int i = s + limit + 1; i < last; i++) {
            if (isWsp(b[i])) {
                return i;
            }
        }
        return -1;
    }

    private static int fieldNameEnd(byte[] b, int s, int e) {
        int i = s;
        while (i < e && b[i] >= 33 && b[i] <= 126 && b[i] != ':') {
            i++;
        }
        return (i > s && i < e && b[i] == ':') ? i : -1;
    }

    private static void copyBodyAsCrlf(ByteArrayOutputStream out, byte[] raw, int from, int to) {
        int runStart = from;
        for (int i = from; i < to; i++) {
            if (raw[i] == '\n' && !(i > from && raw[i - 1] == '\r')) {
                out.write(raw, runStart, i - runStart);
                out.write('\r');
                out.write('\n');
                runStart = i + 1;
            }
        }
        out.write(raw, runStart, to - runStart);
    }

    private static boolean isWsp(byte c) {
        return c == ' ' || c == '\t';
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        out.write(b, 0, b.length);
    }
}
