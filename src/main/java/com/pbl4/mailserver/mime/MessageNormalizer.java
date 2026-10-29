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

/**
 * Chuẩn hóa thư thô (byte[] RFC 5322) trước khi lưu/gửi đi. Hàm tĩnh thuần túy, không đụng lưu trữ hay mạng.
 *
 * <p>Việc làm:
 * <ul>
 *   <li>Bổ sung Message-ID, Date, MIME-Version nếu thiếu (thêm vào CUỐI khối header, không đổi header sẵn có).</li>
 *   <li>LF trần -> CRLF ở cả header lẫn thân; CRLF sẵn có giữ nguyên; CR trần và byte nhị phân khác không đụng.</li>
 *   <li>Gấp dòng header dài hơn 998 byte (chỉ tại khoảng trắng).</li>
 *   <li>Bảo đảm đúng một dòng trống giữa header và thân.</li>
 * </ul>
 * Không bao giờ ném exception: lỗi bất ngờ thì trả lại raw nguyên vẹn.
 *
 * <p>Làm việc hoàn toàn trên byte, không String hóa thân thư.
 */
public final class MessageNormalizer {

    private static final Logger LOG = Logger.getLogger(MessageNormalizer.class.getName());

    /** Độ dài tối đa của một dòng header (không tính CRLF) theo RFC 5322 §2.1.1. */
    static final int MAX_LINE = 998;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private MessageNormalizer() {
    }

    /**
     * @param raw          thư thô
     * @param envelopeFrom người gửi phong bì (hiện chưa dùng; giữ để khớp CONTRACT)
     * @return thư đã chuẩn hóa; nếu raw null/rỗng hoặc có lỗi thì trả chính raw
     */
    public static byte[] normalize(byte[] raw, String envelopeFrom) {
        try {
            return normalize(raw, envelopeFrom, ServerConfig.MAIL_HOSTNAME);
        } catch (Throwable t) { // cố ý bắt rộng: hợp đồng là "không bao giờ ném"
            LOG.log(Level.WARNING, "Không chuẩn hóa được thư, giữ nguyên bản gốc", t);
            return raw;
        }
    }

    /** Bản có tham số hostname để test không phụ thuộc biến môi trường. */
    static byte[] normalize(byte[] raw, String envelopeFrom, String hostname) {
        if (raw == null || raw.length == 0) {
            return raw;
        }
        final int n = raw.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(n + 256);

        boolean hasMessageId = false;
        boolean hasDate = false;
        boolean hasMime = false;
        boolean haveField = false; // đã gặp ít nhất một field (để chấp nhận dòng tiếp nối)
        int bodyStart = n;

        int pos = 0;
        while (pos < n) {
            int eol = pos;
            while (eol < n && raw[eol] != '\n') {
                eol++;
            }
            int contentEnd = eol;
            if (eol < n && contentEnd > pos && raw[contentEnd - 1] == '\r') {
                contentEnd--; // CRLF
            }
            int next = (eol < n) ? eol + 1 : n;

            if (contentEnd == pos) { // dòng trống: hết header
                bodyStart = next;
                break;
            }

            if (isWsp(raw[pos])) {
                if (!haveField) { // dòng tiếp nối mà chưa có field nào -> đây là thân
                    bodyStart = pos;
                    break;
                }
            } else {
                int nameEnd = fieldNameEnd(raw, pos, contentEnd);
                if (nameEnd < 0) { // không phải "Tên: ..." -> thiếu dòng trống, đây là thân
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
        out.write('\r'); // đúng một dòng trống ngăn header và thân
        out.write('\n');

        copyBodyAsCrlf(out, raw, bodyStart, n);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------------------------------
    // Tiện ích dùng chung với MimeBuilder (package-private)
    // ------------------------------------------------------------------------------------------

    /** Message-ID dạng {@code <uuid@hostname>}. */
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

    /** Date theo RFC 5322 với múi giờ số, ví dụ {@code Tue, 27 Oct 2026 14:03:05 +0700}. */
    static String formatDate(ZonedDateTime t) {
        return DATE_FMT.format(t);
    }

    /**
     * Ghi đoạn [s, e) (một dòng, không gồm CRLF) và gấp bằng CRLF tại khoảng trắng nếu dài hơn {@code limit}.
     * Dấu cách/tab tại điểm gấp được giữ ở đầu dòng tiếp theo. Dòng không có chỗ gấp thì ghi nguyên.
     */
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

    // ------------------------------------------------------------------------------------------
    // Nội bộ
    // ------------------------------------------------------------------------------------------

    /**
     * Tìm vị trí khoảng trắng để gấp: phần trước và phần sau đều phải còn ký tự không-trắng
     * (RFC 5322 cấm dòng chỉ toàn khoảng trắng). Ưu tiên điểm cuối cùng nằm trong hạn mức,
     * nếu không có thì lấy điểm đầu tiên sau hạn mức. -1 nếu không gấp được.
     */
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

    /** Vị trí dấu ':' nếu [s, e) bắt đầu bằng tên field hợp lệ (ký tự in được 33..126, không có ':'), ngược lại -1. */
    private static int fieldNameEnd(byte[] b, int s, int e) {
        int i = s;
        while (i < e && b[i] >= 33 && b[i] <= 126 && b[i] != ':') {
            i++;
        }
        return (i > s && i < e && b[i] == ':') ? i : -1;
    }

    /** Chép thân, chỉ đổi LF trần thành CRLF; mọi byte khác (kể cả CR trần, nhị phân) giữ nguyên. */
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