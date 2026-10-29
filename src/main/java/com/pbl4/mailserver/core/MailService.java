// FILE: src/main/java/com/pbl4/mailserver/core/MailService.java
package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.mime.MessageNormalizer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

public class MailService {

    public static class DeliveryResult {
        public final boolean success;
        public final int successCount;
        public final int failCount;
        public final String message;
        // true khi MỌI người nhận nội bộ đều thất bại do hòm thư đầy (OVER_QUOTA) -> tầng SMTP có thể trả 552
        public final boolean quotaExceeded;

        public DeliveryResult(boolean success, int successCount, int failCount, String message) {
            this(success, successCount, failCount, message, false);
        }

        public DeliveryResult(boolean success, int successCount, int failCount, String message, boolean quotaExceeded) {
            this.success = success;
            this.successCount = successCount;
            this.failCount = failCount;
            this.message = message;
            this.quotaExceeded = quotaExceeded;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Điểm móc phục vụ test (package-private): thay MessageNormalizer (9B-2) và OutboundQueue (A6) bằng bản giả
    // ------------------------------------------------------------------------------------------------

    interface Normalizer {
        byte[] normalize(byte[] raw, String envelopeFrom);
    }

    interface OutboundEnqueuer {
        boolean enqueue(String envelopeFrom, Set<String> externalRcpts, byte[] raw);
    }

    private static volatile Normalizer normalizer = MessageNormalizer::normalize;
    private static volatile OutboundEnqueuer outbound = OutboundQueue::enqueue;

    static void setHooksForTest(Normalizer n, OutboundEnqueuer o) {
        if (n != null) normalizer = n;
        if (o != null) outbound = o;
    }

    static void resetHooksForTest() {
        normalizer = MessageNormalizer::normalize;
        outbound = OutboundQueue::enqueue;
    }

    // ------------------------------------------------------------------------------------------------
    // Adapter cũ (String) - giữ để WebAPI và test hiện có chưa vỡ
    // ------------------------------------------------------------------------------------------------

    /** @deprecated Dùng {@link #submit(String, String, Set, byte[])} với thư dựng bởi MimeBuilder. */
    @Deprecated
    public static DeliveryResult deliver(String sender, String recipientsRaw, String subject, String body) {
        if (sender == null || recipientsRaw == null || subject == null || body == null) {
            return new DeliveryResult(false, 0, 0, "Thiếu thông tin gửi thư!");
        }

        String safeSubject = subject.replace('\r', ' ').replace('\n', ' ').trim();
        String[] recipientsArr = recipientsRaw.trim().split("[\\s,;]+");

        Set<String> allRecipients = new LinkedHashSet<>();
        for (String to : recipientsArr) {
            to = to.trim();
            if (!to.isEmpty()) {
                allRecipients.add(to);
            }
        }

        if (allRecipients.isEmpty()) {
            return new DeliveryResult(false, 0, 0, "Địa chỉ người nhận trống!");
        }

        String canonicalSender = UserStore.canonical(sender);
        if (canonicalSender == null) {
            canonicalSender = sender.toLowerCase(Locale.ROOT);
        }

        String dateStr = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH).format(new Date());
        String fullToHeader = String.join(", ", allRecipients);

        String rawEmail = "From: " + canonicalSender + "\n" +
                         "To: " + fullToHeader + "\n" +
                         "Subject: " + safeSubject + "\n" +
                         "Date: " + dateStr + "\n" +
                         "MIME-Version: 1.0\n" +
                         "Content-Type: text/plain; charset=UTF-8\n\n" +
                         body;

        return deliverRaw(canonicalSender, allRecipients, rawEmail);
    }

    /** @deprecated Dùng {@link #submit(String, String, Set, byte[])}. Adapter: String -> UTF-8 byte rồi gọi submit. */
    @Deprecated
    public static DeliveryResult deliverRaw(String sender, Set<String> recipients, String rawMessage) {
        if (sender == null || recipients == null || recipients.isEmpty() || rawMessage == null) {
            return new DeliveryResult(false, 0, 0, "Thiếu thông tin gửi thư thô!");
        }
        return submit(sender, sender, recipients, rawMessage.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------------------------------------
    // CONTRACT v2: submit / deliverLocal (byte[] xuyên suốt)
    // ------------------------------------------------------------------------------------------------

    /**
     * Gửi thư từ người dùng đã xác thực (submission / web).
     * Ép From = authUser, normalize, lưu Sent, nội bộ -> hòm INBOX, ngoại -> OutboundQueue.
     */
    public static DeliveryResult submit(String authUser, String envelopeFrom, Set<String> rcpts, byte[] raw) {
        if (authUser == null || rcpts == null || rcpts.isEmpty() || raw == null) {
            return new DeliveryResult(false, 0, 0, "Thiếu thông tin gửi thư!");
        }

        String canonicalSender = UserStore.canonical(authUser);
        if (canonicalSender == null) {
            canonicalSender = authUser.trim().toLowerCase(Locale.ROOT);
        }
        // Địa chỉ người gửi sẽ được ghi vào header: tuyệt đối không cho chứa khoảng trắng/ngoặc nhọn/ký tự điều khiển
        if (canonicalSender.isEmpty() || SENDER_UNSAFE.matcher(canonicalSender).find()) {
            return new DeliveryResult(false, 0, 0, "Địa chỉ người gửi không hợp lệ!");
        }

        // 1) Chống giả mạo người gửi, 2) chuẩn hóa thư
        byte[] forced = forceFromHeader(raw, canonicalSender);
        byte[] message;
        try {
            message = normalizer.normalize(forced, canonicalSender);
        } catch (RuntimeException e) {
            System.err.println("[MAIL SERVICE] Lỗi chuẩn hóa thư: " + e.getMessage());
            return new DeliveryResult(false, 0, 0, "Không chuẩn hóa được nội dung thư!");
        }
        if (message == null) {
            return new DeliveryResult(false, 0, 0, "Không chuẩn hóa được nội dung thư!");
        }

        // 3) Tách người nhận nội bộ / ngoại, loại trùng
        Set<String> localRecipients = new LinkedHashSet<>();
        Set<String> externalRecipients = new LinkedHashSet<>();
        int invalidLocal = 0;

        Set<String> seen = new HashSet<>();
        for (String rcpt : rcpts) {
            if (rcpt == null || rcpt.trim().isEmpty()) continue;
            String cleanRcpt = rcpt.trim().toLowerCase(Locale.ROOT);
            if (!seen.add(cleanRcpt)) continue;

            if (isLocalDomain(cleanRcpt)) {
                String canonicalRcpt = UserStore.canonical(cleanRcpt);
                if (canonicalRcpt != null && UserStore.exists(canonicalRcpt)) {
                    localRecipients.add(canonicalRcpt); // alias (postmaster@...) cùng về admin thì chỉ giao một lần
                } else {
                    invalidLocal++;
                }
            } else {
                externalRecipients.add(cleanRcpt);
            }
        }

        if (localRecipients.isEmpty() && externalRecipients.isEmpty() && invalidLocal == 0) {
            return new DeliveryResult(false, 0, 0, "Địa chỉ người nhận trống!");
        }

        int successTotal = 0;
        int failTotal = invalidLocal;
        int quotaFailCount = 0;
        boolean quotaExceeded = false;
        List<String> messages = new ArrayList<>();

        // 4) Giao nội bộ (cùng logic lõi với deliverLocal, nhưng lấy được số hòm thư đầy để báo chi tiết)
        if (!localRecipients.isEmpty()) {
            LocalOutcome lo = storeLocal(localRecipients, message, Folder.INBOX);
            successTotal += lo.ok;
            failTotal += lo.fail;
            quotaFailCount += lo.quotaFail;
            // Mọi người nhận nội bộ đều thất bại do quota (và không có địa chỉ nội bộ nào không tồn tại)
            quotaExceeded = (invalidLocal == 0 && lo.quotaFail == localRecipients.size());
            if (lo.ok > 0) {
                messages.add("Đã lưu nội bộ cho " + lo.ok + " người nhận");
            }
        }

        // 5) Lưu bản sao thư đã gửi (đồng bộ, ngay lập tức)
        MailStorageEngine.SaveResult sentResult = MailStorageEngine.saveEmailResult(canonicalSender + "_sent", message);
        if (sentResult != MailStorageEngine.SaveResult.OK) {
            System.err.println("[MAIL SERVICE] Không lưu được bản Sent của " + canonicalSender + ": " + sentResult);
        }

        // 6) Người nhận bên ngoài -> hàng đợi gửi ra
        if (!externalRecipients.isEmpty()) {
            boolean enqueued;
            try {
                enqueued = outbound.enqueue(canonicalSender, externalRecipients, message);
            } catch (RuntimeException e) {
                System.err.println("[MAIL SERVICE] Lỗi đưa thư vào hàng đợi: " + e.getMessage());
                enqueued = false;
            }
            if (enqueued) {
                successTotal += externalRecipients.size();
                messages.add("Thư gửi ra ngoài đã được xếp hàng (" + externalRecipients.size() + " người nhận)");
            } else {
                failTotal += externalRecipients.size();
                messages.add("Hàng đợi gửi thư ra ngoài đã đầy, vui lòng thử lại sau!");
            }
        }

        // Nêu rõ số người nhận thất bại (và bao nhiêu trong số đó do hòm thư đầy)
        if (failTotal > 0) {
            String failMsg = "Thất bại " + failTotal + " người nhận";
            if (quotaFailCount > 0) {
                failMsg += " (" + quotaFailCount + " hòm thư đã đầy dung lượng)";
            }
            messages.add(failMsg);
        }

        if (successTotal > 0) {
            return new DeliveryResult(true, successTotal, failTotal, String.join(" | ", messages), quotaExceeded);
        }
        return new DeliveryResult(false, 0, failTotal,
                messages.isEmpty() ? "Gửi thư thất bại!" : String.join(" | ", messages), quotaExceeded);
    }

    /**
     * Giao thư tới các hòm thư nội bộ (thư đến từ MX). envelopeFrom "" = null sender (hợp lệ).
     * INBOX lưu vào "u", SPAM lưu vào "u_spam". Không lưu bản Sent, không đẩy ra hàng đợi ngoài.
     * quotaExceeded chỉ true khi MỌI người nhận thất bại vì OVER_QUOTA.
     */
    public static DeliveryResult deliverLocal(String envelopeFrom, Set<String> localRcpts, byte[] raw, Folder folder) {
        if (localRcpts == null || localRcpts.isEmpty() || raw == null) {
            return new DeliveryResult(false, 0, 0, "Thiếu thông tin giao thư nội bộ!");
        }
        Folder target = (folder == null) ? Folder.INBOX : folder;

        LocalOutcome lo = storeLocal(localRcpts, raw, target);

        List<String> messages = new ArrayList<>();
        if (lo.ok > 0) messages.add("Đã lưu nội bộ cho " + lo.ok + " người nhận");
        if (lo.fail > 0) {
            String failMsg = "Thất bại " + lo.fail + " người nhận";
            if (lo.quotaFail > 0) failMsg += " (" + lo.quotaFail + " hòm thư đã đầy dung lượng)";
            messages.add(failMsg);
        }
        // Mọi người nhận đều thất bại và tất cả đều do vượt quota
        boolean quotaExceeded = (lo.ok == 0 && lo.fail > 0 && lo.quotaFail == lo.fail);

        if (lo.ok > 0) {
            return new DeliveryResult(true, lo.ok, lo.fail, String.join(" | ", messages), quotaExceeded);
        }
        return new DeliveryResult(false, 0, lo.fail,
                messages.isEmpty() ? "Giao thư nội bộ thất bại!" : String.join(" | ", messages), quotaExceeded);
    }

    // Kết quả đếm khi lưu vào các hòm thư nội bộ
    private static final class LocalOutcome {
        int ok;
        int fail;
        int quotaFail;
    }

    // Lõi chung của deliverLocal và submit: lưu raw vào từng hòm (INBOX hoặc SPAM). Người nhận không tồn tại = thất bại.
    private static LocalOutcome storeLocal(Collection<String> rcpts, byte[] raw, Folder folder) {
        LocalOutcome out = new LocalOutcome();
        Set<String> done = new HashSet<>(); // alias và tên thật cùng về một hòm thì chỉ giao một lần
        for (String rcpt : rcpts) {
            if (rcpt == null || rcpt.trim().isEmpty()) continue;
            String canonical = UserStore.canonical(rcpt.trim().toLowerCase(Locale.ROOT));
            if (canonical == null || !UserStore.exists(canonical)) {
                out.fail++;
                continue;
            }
            if (!done.add(canonical)) continue;

            String mailbox = (folder == Folder.SPAM) ? canonical + "_spam" : canonical;
            MailStorageEngine.SaveResult r = MailStorageEngine.saveEmailResult(mailbox, raw);
            if (r == MailStorageEngine.SaveResult.OK) {
                out.ok++;
            } else {
                out.fail++;
                if (r == MailStorageEngine.SaveResult.OVER_QUOTA) out.quotaFail++;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------
    // Ép header From (chống giả mạo người gửi) - làm việc trực tiếp trên byte[], không String hóa cả thư
    // ------------------------------------------------------------------------------------------------

    private static final Pattern SENDER_UNSAFE = Pattern.compile("[\\s<>\"\\x00-\\x1f\\x7f]");

    /**
     * Thay header From ĐẦU TIÊN (kể cả khi gấp dòng) bằng địa chỉ {@code address}; chưa có From thì thêm vào đầu header.
     * Giữ tên hiển thị nếu parse được (đúng một địa chỉ dạng "Tên &lt;addr&gt;"), giữ nguyên thân thư và kiểu xuống dòng.
     */
    static byte[] forceFromHeader(byte[] raw, String address) {
        final int n = raw.length;
        int pos = 0;
        int fromStart = -1;
        int fromEnd = -1;        // exclusive, đã gồm ký tự xuống dòng cuối của header From
        int fromColon = -1;
        byte[] firstEol = null;

        while (pos < n) {
            int nl = indexOfLf(raw, pos);
            int lineEnd = (nl < 0) ? n : nl + 1;                                   // gồm EOL
            int contentEnd = (nl < 0) ? n : ((nl > pos && raw[nl - 1] == '\r') ? nl - 1 : nl); // không gồm EOL
            if (contentEnd == pos) break; // dòng trống: hết phần header

            if (firstEol == null) firstEol = Arrays.copyOfRange(raw, contentEnd, lineEnd);

            if (fromStart >= 0) {
                if (raw[pos] == ' ' || raw[pos] == '\t') { // dòng gấp của header From
                    fromEnd = lineEnd;
                    pos = lineEnd;
                    continue;
                }
                break; // đã gặp hết header From đầu tiên
            }
            int colon = fromFieldColon(raw, pos, contentEnd);
            if (colon >= 0) {
                fromStart = pos;
                fromEnd = lineEnd;
                fromColon = colon;
            }
            pos = lineEnd;
        }

        byte[] crlf = {'\r', '\n'};
        byte[] eol = (firstEol == null || firstEol.length == 0) ? crlf : firstEol;
        ByteArrayOutputStream out = new ByteArrayOutputStream(n + 64);

        if (fromStart < 0) {
            // Chưa có From: thêm vào đầu (header rỗng thì sau đó vẫn là dòng trống phân cách thân thư)
            writeFromLine(out, "", address, eol);
            out.write(raw, 0, n);
            return out.toByteArray();
        }

        // Kiểu xuống dòng của chính header From (dòng cuối của nó)
        byte[] fromEol = eolAt(raw, fromEnd);
        if (fromEol.length == 0) fromEol = eol;

        String value = new String(raw, fromColon + 1, trimEol(raw, fromEnd) - (fromColon + 1), StandardCharsets.ISO_8859_1)
                .replace("\r", "").replace("\n", ""); // bỏ gấp dòng (dòng gấp bắt đầu bằng SP/HT nên vẫn còn khoảng trắng)
        String display = parseDisplayName(value);

        out.write(raw, 0, fromStart);
        writeFromLine(out, display, address, fromEol);
        out.write(raw, fromEnd, n - fromEnd);
        return out.toByteArray();
    }

    private static void writeFromLine(ByteArrayOutputStream out, String display, String address, byte[] eol) {
        byte[] line = ((display.isEmpty() ? "From: " + address : "From: " + display + " <" + address + ">"))
                .getBytes(StandardCharsets.ISO_8859_1); // tên hiển thị được giữ nguyên từng byte (đọc bằng ISO_8859_1)
        out.write(line, 0, line.length);
        out.write(eol, 0, eol.length);
    }

    private static int indexOfLf(byte[] b, int from) {
        for (int i = from; i < b.length; i++) {
            if (b[i] == '\n') return i;
        }
        return -1;
    }

    // Vị trí dấu ':' nếu dòng [start,end) bắt đầu bằng tên header "From" (không phân biệt hoa thường), ngược lại -1
    private static int fromFieldColon(byte[] b, int start, int end) {
        if (end - start < 5) return -1;
        if ((b[start] | 0x20) != 'f' || (b[start + 1] | 0x20) != 'r'
                || (b[start + 2] | 0x20) != 'o' || (b[start + 3] | 0x20) != 'm') return -1;
        int i = start + 4;
        while (i < end && (b[i] == ' ' || b[i] == '\t')) i++;
        return (i < end && b[i] == ':') ? i : -1;
    }

    // Chỉ số ngay sau nội dung, trước EOL, của vùng kết thúc ở "end" (end đã gồm EOL)
    private static int trimEol(byte[] b, int end) {
        int e = end;
        if (e > 0 && b[e - 1] == '\n') e--;
        if (e > 0 && b[e - 1] == '\r') e--;
        return e;
    }

    // EOL nằm ở cuối vùng kết thúc tại "end" (rỗng nếu hết thư mà không có xuống dòng)
    private static byte[] eolAt(byte[] b, int end) {
        int contentEnd = trimEol(b, end);
        return Arrays.copyOfRange(b, contentEnd, end);
    }

    /**
     * Lấy tên hiển thị từ giá trị header From đã bỏ gấp dòng. Chỉ chấp nhận đúng MỘT địa chỉ dạng "Tên <addr>";
     * nhiều địa chỉ, nhóm, ngoặc/nháy không cân bằng... đều bỏ tên hiển thị (trả ""), chỉ dùng địa chỉ trần.
     */
    static String parseDisplayName(String value) {
        int depth = 0;
        boolean inQuote = false;
        int lt = -1, gt = -1, ltCount = 0, gtCount = 0;
        boolean bad = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (inQuote) {
                if (c == '\\') i++;
                else if (c == '"') inQuote = false;
                continue;
            }
            if (depth > 0) {
                if (c == '\\') i++;
                else if (c == '(') depth++;
                else if (c == ')') depth--;
                continue;
            }
            switch (c) {
                case '"': inQuote = true; break;
                case '(': depth++; break;
                case '<': ltCount++; if (lt < 0) lt = i; break;
                case '>': gtCount++; if (gt < 0) gt = i; break;
                case ',': case ';': case ':': bad = true; break;
                default: break;
            }
        }
        if (inQuote || depth > 0 || bad || ltCount != 1 || gtCount != 1 || gt < lt) return "";
        return value.substring(0, lt).replaceAll("[\\r\\n\\x00]", "").trim();
    }

    private static boolean isLocalDomain(String email) {
        if (!email.contains("@")) return true;
        String domain = email.substring(email.indexOf('@') + 1).trim();
        return domain.equalsIgnoreCase(ServerConfig.MAIL_DOMAIN) || domain.equalsIgnoreCase("localhost");
    }

    public static List<String> listInbox(String username) {
        String canonicalUser = UserStore.canonical(username);
        return MailStorageEngine.readAllEmails(canonicalUser != null ? canonicalUser : username);
    }

    public static List<String> listSent(String username) {
        String canonicalUser = UserStore.canonical(username);
        String target = canonicalUser != null ? canonicalUser + "_sent" : username + "_sent";
        return MailStorageEngine.readAllEmails(target);
    }
}