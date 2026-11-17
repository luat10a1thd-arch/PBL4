// FILE: src/main/java/com/pbl4/mailserver/core/MailService.java
package com.pbl4.mailserver.core;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.forward.ForwardService;
import com.pbl4.mailserver.mime.MessageNormalizer;
import com.pbl4.mailserver.mime.MimeParser;
import com.pbl4.mailserver.spam.SpamFilter;
import com.pbl4.mailserver.spam.SpamVerdict;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

public class MailService {

    public static class DeliveryResult {
        public final boolean success;
        public final int successCount;
        public final int failCount;
        public final String message;
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

    interface Normalizer {
        byte[] normalize(byte[] raw, String envelopeFrom);
    }

    interface OutboundEnqueuer {
        boolean enqueue(String envelopeFrom, Set<String> externalRcpts, byte[] raw);
    }

    /** Điểm móc phân loại thư rác (mặc định: SpamFilter.classify). */
    interface InboundClassifier {
        SpamVerdict classify(String user, byte[] raw, String envelopeFrom);
    }

    /** Điểm móc chuyển tiếp (mặc định: ForwardService.apply). true = vẫn lưu INBOX. */
    interface InboundForwarder {
        boolean apply(String user, byte[] raw, String envelopeFrom);
    }

    private static volatile Normalizer normalizer = MessageNormalizer::normalize;
    private static volatile OutboundEnqueuer outbound = OutboundQueue::enqueue;
    private static volatile InboundClassifier classifier = SpamFilter::classify;
    private static volatile InboundForwarder forwarder = ForwardService::apply;

    static void setHooksForTest(Normalizer n, OutboundEnqueuer o) {
        if (n != null) normalizer = n;
        if (o != null) outbound = o;
    }

    static void setInboundHooksForTest(InboundClassifier c, InboundForwarder f) {
        if (c != null) classifier = c;
        if (f != null) forwarder = f;
    }

    static void resetHooksForTest() {
        normalizer = MessageNormalizer::normalize;
        outbound = OutboundQueue::enqueue;
        classifier = SpamFilter::classify;
        forwarder = ForwardService::apply;
    }

    // ---------- Giới hạn thư gửi RA NGOÀI theo tài khoản (cửa sổ trượt 1 giờ) ----------
    // Tính theo số người nhận ngoài hệ thống (mỗi người nhận = 1 thư thực sự rời server).
    // Admin và SEND_LIMIT_PER_HOUR=0 được miễn.

    private static final long SEND_WINDOW_MS = 60L * 60 * 1000;
    private static final Map<String, ArrayDeque<Long>> sendWindows = new ConcurrentHashMap<>();
    private static volatile LongSupplier sendClock = System::currentTimeMillis;

    static void resetSendLimitForTest(LongSupplier clock) {
        sendWindows.clear();
        sendClock = (clock == null) ? System::currentTimeMillis : clock;
    }

    private static boolean isSendLimitExempt(String user) {
        if (ServerConfig.SEND_LIMIT_PER_HOUR <= 0) return true;
        if (ServerConfig.ADMIN_EMAIL.equalsIgnoreCase(user)) return true;
        User u = UserStore.getUser(user);
        return u != null && "ADMIN".equalsIgnoreCase(u.getRole());
    }

    /** Giữ chỗ n lượt gửi (tất cả hoặc không). Trả false nếu vượt hạn mức. */
    static boolean reserveSend(String user, int n) {
        int max = ServerConfig.SEND_LIMIT_PER_HOUR;
        if (n > max) return false;
        long now = sendClock.getAsLong();
        ArrayDeque<Long> dq = sendWindows.computeIfAbsent(user, k -> new ArrayDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && now - dq.peekFirst() >= SEND_WINDOW_MS) dq.pollFirst();
            if (dq.size() + n > max) return false;
            for (int i = 0; i < n; i++) dq.addLast(now);
            return true;
        }
    }

    /** Hoàn lại lượt đã giữ khi thư không được xếp hàng. */
    static void refundSend(String user, int n) {
        ArrayDeque<Long> dq = sendWindows.get(user);
        if (dq == null) return;
        synchronized (dq) {
            for (int i = 0; i < n && !dq.isEmpty(); i++) dq.pollLast();
        }
    }

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

    @Deprecated
    public static DeliveryResult deliverRaw(String sender, Set<String> recipients, String rawMessage) {
        if (sender == null || recipients == null || recipients.isEmpty() || rawMessage == null) {
            return new DeliveryResult(false, 0, 0, "Thiếu thông tin gửi thư thô!");
        }
        return submit(sender, sender, recipients, rawMessage.getBytes(StandardCharsets.UTF_8));
    }

    public static DeliveryResult submit(String authUser, String envelopeFrom, Set<String> rcpts, byte[] raw) {
        if (authUser == null || rcpts == null || rcpts.isEmpty() || raw == null) {
            return new DeliveryResult(false, 0, 0, "Thiếu thông tin gửi thư!");
        }

        String canonicalSender = UserStore.canonical(authUser);
        if (canonicalSender == null) {
            canonicalSender = authUser.trim().toLowerCase(Locale.ROOT);
        }
        if (canonicalSender.isEmpty() || SENDER_UNSAFE.matcher(canonicalSender).find()) {
            return new DeliveryResult(false, 0, 0, "Địa chỉ người gửi không hợp lệ!");
        }

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
                    localRecipients.add(canonicalRcpt);
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

        // Giới hạn thư gửi ra ngoài mỗi giờ: kiểm tra TRƯỚC khi lưu bất cứ thứ gì (từ chối cả thư, không gửi nửa chừng)
        int reservedExternal = 0;
        if (!externalRecipients.isEmpty() && !isSendLimitExempt(canonicalSender)) {
            if (!reserveSend(canonicalSender, externalRecipients.size())) {
                int total = localRecipients.size() + externalRecipients.size() + invalidLocal;
                return new DeliveryResult(false, 0, total,
                        "Bạn đã vượt giới hạn " + ServerConfig.SEND_LIMIT_PER_HOUR
                                + " thư gửi ra ngoài mỗi giờ. Vui lòng thử lại sau!");
            }
            reservedExternal = externalRecipients.size();
        }

        int successTotal = 0;
        int failTotal = invalidLocal;
        int quotaFailCount = 0;
        boolean quotaExceeded = false;
        List<String> messages = new ArrayList<>();

        if (!localRecipients.isEmpty()) {
            LocalOutcome lo = storeLocal(localRecipients, message, Folder.INBOX, canonicalSender);
            successTotal += lo.ok;
            failTotal += lo.fail;
            quotaFailCount += lo.quotaFail;
            quotaExceeded = (invalidLocal == 0 && lo.quotaFail == localRecipients.size());
            if (lo.ok > 0) {
                messages.add("Đã lưu nội bộ cho " + lo.ok + " người nhận");
            }
        }

        MailStorageEngine.SaveResult sentResult = MailStorageEngine.saveEmailResult(canonicalSender + "_sent", message);
        if (sentResult != MailStorageEngine.SaveResult.OK) {
            System.err.println("[MAIL SERVICE] Không lưu được bản Sent của " + canonicalSender + ": " + sentResult);
        }

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
                if (reservedExternal > 0) refundSend(canonicalSender, reservedExternal);
                failTotal += externalRecipients.size();
                messages.add("Hàng đợi gửi thư ra ngoài đã đầy, vui lòng thử lại sau!");
            }
        }

        if (failTotal > 0) {
            String failMsg = "Thất bại " + failTotal + " người nhận";
            if (quotaFailCount > 0) {
                failMsg += " (" + quotaFailCount + " hòm thư đã đầy dung lượng)";
            }
            messages.add(failMsg);
        }

        if (successTotal > 0) {
            noteContacts(canonicalSender, localRecipients, externalRecipients);
            return new DeliveryResult(true, successTotal, failTotal, String.join(" | ", messages), quotaExceeded);
        }
        return new DeliveryResult(false, 0, failTotal,
                messages.isEmpty() ? "Gửi thư thất bại!" : String.join(" | ", messages), quotaExceeded);
    }

    /** Người user từng gửi thư tới = liên hệ tin cậy của bộ lọc rác. Lỗi thì bỏ qua. */
    private static void noteContacts(String sender, Collection<String> local, Collection<String> external) {
        if (!ServerConfig.SPAM_ENABLED) return;
        try {
            Set<String> all = new LinkedHashSet<>(local);
            all.addAll(external);
            SpamFilter.noteRecipients(sender, all);
        } catch (RuntimeException e) {
            System.err.println("[MAIL SERVICE] Không ghi nhận liên hệ tin cậy: " + e.getClass().getSimpleName());
        }
    }

    public static DeliveryResult deliverLocal(String envelopeFrom, Set<String> localRcpts, byte[] raw, Folder folder) {
        if (localRcpts == null || localRcpts.isEmpty() || raw == null) {
            return new DeliveryResult(false, 0, 0, "Thiếu thông tin giao thư nội bộ!");
        }
        Folder target = (folder == null) ? Folder.INBOX : folder;

        // Người nhận đã chặn người gửi: thư vào _spam (vẫn tính là giao thành công, người gửi không dò được)
        LocalOutcome lo = storeLocal(localRcpts, raw, target, envelopeFrom);

        List<String> messages = new ArrayList<>();
        if (lo.ok > 0) messages.add("Đã lưu nội bộ cho " + lo.ok + " người nhận");
        if (lo.fail > 0) {
            String failMsg = "Thất bại " + lo.fail + " người nhận";
            if (lo.quotaFail > 0) failMsg += " (" + lo.quotaFail + " hòm thư đã đầy dung lượng)";
            messages.add(failMsg);
        }
        boolean quotaExceeded = (lo.ok == 0 && lo.fail > 0 && lo.quotaFail == lo.fail);

        if (lo.ok > 0) {
            return new DeliveryResult(true, lo.ok, lo.fail, String.join(" | ", messages), quotaExceeded);
        }
        return new DeliveryResult(false, 0, lo.fail,
                messages.isEmpty() ? "Giao thư nội bộ thất bại!" : String.join(" | ", messages), quotaExceeded);
    }

    private static final class LocalOutcome {
        int ok;
        int fail;
        int quotaFail;
    }

    /** Kết quả xử lý thư sắp vào INBOX của một người nhận: hòm đích và nội dung sẽ lưu. */
    static final class InboxPlan {
        final String mailbox;
        final byte[] data;

        InboxPlan(String mailbox, byte[] data) {
            this.mailbox = mailbox;
            this.data = data;
        }
    }

    /**
     * Quyết định nơi lưu thư vào INBOX của một người nhận: lọc rác (rác => _spam, không chuyển tiếp),
     * gắn điểm, rồi chuyển tiếp (keepCopy=false => _trash). KHÔNG BAO GIỜ ném lỗi: lỗi => lưu INBOX như cũ.
     */
    static InboxPlan planInbox(String canonical, byte[] raw, String envelopeFrom) {
        try {
            byte[] data = raw;
            if (ServerConfig.SPAM_ENABLED) {
                try {
                    SpamVerdict v = classifier.classify(canonical, raw, envelopeFrom);
                    if (v != null) {
                        byte[] tagged = SpamFilter.tag(raw, v);
                        if (tagged != null) data = tagged;
                        if (v.spam()) return new InboxPlan(canonical + "_spam", data);
                    }
                } catch (RuntimeException e) {
                    data = raw;
                    System.err.println("[MAIL SERVICE] Bỏ qua lọc rác do lỗi: " + e.getClass().getSimpleName());
                }
            }
            if (ServerConfig.FORWARD_ENABLED) {
                try {
                    boolean keepInbox = forwarder.apply(canonical, data, envelopeFrom);
                    if (!keepInbox) return new InboxPlan(canonical + "_trash", data);
                } catch (RuntimeException e) {
                    System.err.println("[MAIL SERVICE] Bỏ qua chuyển tiếp do lỗi: " + e.getClass().getSimpleName());
                }
            }
            return new InboxPlan(canonical, data);
        } catch (RuntimeException e) {
            return new InboxPlan(canonical, raw);
        }
    }

    private static LocalOutcome storeLocal(Collection<String> rcpts, byte[] raw, Folder folder) {
        return storeLocal(rcpts, raw, folder, null);
    }

    private static LocalOutcome storeLocal(Collection<String> rcpts, byte[] raw, Folder folder, String envelopeFrom) {
        LocalOutcome out = new LocalOutcome();
        Set<String> done = new HashSet<>();
        String headerFrom = null;
        boolean headerRead = false;
        for (String rcpt : rcpts) {
            if (rcpt == null || rcpt.trim().isEmpty()) continue;
            String canonical = UserStore.canonical(rcpt.trim().toLowerCase(Locale.ROOT));
            if (canonical == null || !UserStore.exists(canonical)) {
                out.fail++;
                continue;
            }
            if (!done.add(canonical)) continue;

            Folder target = folder;
            if (folder == Folder.INBOX) {
                if (!headerRead) {
                    headerFrom = readFromHeader(raw);
                    headerRead = true;
                }
                if (isBlockedFor(canonical, envelopeFrom, headerFrom)) {
                    target = Folder.SPAM;
                }
            }

            String mailbox;
            byte[] data = raw;
            if (target == Folder.SPAM) {
                mailbox = canonical + "_spam";
            } else if (folder == Folder.INBOX) {
                // Mỗi người nhận xử lý riêng: bản lưu có thể khác nhau theo user (điểm rác, chuyển tiếp)
                InboxPlan plan = planInbox(canonical, raw, envelopeFrom);
                mailbox = plan.mailbox;
                data = plan.data;
            } else {
                mailbox = canonical;
            }
            MailStorageEngine.SaveResult r = MailStorageEngine.saveEmailResult(mailbox, data);
            if (r == MailStorageEngine.SaveResult.OK) {
                out.ok++;
            } else {
                out.fail++;
                if (r == MailStorageEngine.SaveResult.OVER_QUOTA) out.quotaFail++;
            }
        }
        return out;
    }

    // ---------- Thông báo hệ thống (không đi qua lọc rác/chuyển tiếp) ----------

    /**
     * Gửi thông báo từ no-reply@MAIL_DOMAIN vào INBOX của user; nếu extraAddress không rỗng thì gửi thêm bản tới đó
     * (nội bộ thì lưu thẳng, ngoài hệ thống thì xếp hàng gửi). Không ném lỗi. Trả true nếu đã lưu vào hộp thư của user.
     */
    public static boolean sendSystemNotice(String user, String subject, String body, String extraAddress) {
        try {
            if (user == null || subject == null || body == null) return false;
            String canonical = UserStore.canonical(user);
            if (canonical == null || !UserStore.exists(canonical)) return false;

            String domain = ServerConfig.MAIL_DOMAIN.toLowerCase(Locale.ROOT);
            String noReply = "no-reply@" + domain;
            String userAddr = canonical.indexOf('@') >= 0 ? canonical : canonical + "@" + domain;

            boolean saved = MailStorageEngine.saveEmailResult(canonical,
                    buildNotice(noReply, userAddr, subject, body, domain)) == MailStorageEngine.SaveResult.OK;

            if (extraAddress != null) {
                String extra = extraAddress.trim().toLowerCase(Locale.ROOT);
                if (!extra.isEmpty() && !extra.equals(userAddr) && extra.matches("[^\\s<>\"]{1,64}@[^\\s<>\"]{1,253}")) {
                    byte[] copy = buildNotice(noReply, extra, subject, body, domain);
                    try {
                        if (isLocalDomain(extra)) {
                            String c2 = UserStore.canonical(extra);
                            if (c2 != null && UserStore.exists(c2) && !c2.equals(canonical)) {
                                MailStorageEngine.saveEmailResult(c2, copy);
                            }
                        } else {
                            outbound.enqueue(noReply, new LinkedHashSet<>(List.of(extra)), copy);
                        }
                    } catch (RuntimeException e) {
                        System.err.println("[MAIL SERVICE] Không gửi được bản thông báo tới email khôi phục.");
                    }
                }
            }
            return saved;
        } catch (RuntimeException e) {
            System.err.println("[MAIL SERVICE] Lỗi gửi thông báo hệ thống: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private static byte[] buildNotice(String from, String to, String subject, String body, String domain) {
        String crlf = "\r\n";
        String head = "From: " + from + crlf
                + "To: " + to + crlf
                + "Subject: " + encodeHeader(subject.replace('\r', ' ').replace('\n', ' ')) + crlf
                + "Date: " + ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME) + crlf
                + "Message-ID: <" + UUID.randomUUID() + "@" + domain + ">" + crlf
                + "MIME-Version: 1.0" + crlf
                + "Content-Type: text/plain; charset=UTF-8" + crlf
                + "Content-Transfer-Encoding: base64" + crlf
                + "Auto-Submitted: auto-generated" + crlf
                + crlf;
        String b64 = Base64.getMimeEncoder(76, crlf.getBytes(StandardCharsets.US_ASCII))
                .encodeToString(body.getBytes(StandardCharsets.UTF_8));
        return (head + b64 + crlf).getBytes(StandardCharsets.US_ASCII);
    }

    /** RFC 2047 (B), tách theo ký tự để mỗi encoded-word ngắn hơn 75 ký tự. */
    static String encodeHeader(String s) {
        boolean ascii = true;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7e || s.charAt(i) < 0x20) { ascii = false; break; }
        }
        if (ascii) return s;
        List<String> words = new ArrayList<>();
        ByteArrayOutputStream cur = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            byte[] b = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            if (cur.size() + b.length > 36) {
                words.add(wordOf(cur.toByteArray()));
                cur.reset();
            }
            cur.write(b, 0, b.length);
        }
        if (cur.size() > 0) words.add(wordOf(cur.toByteArray()));
        return String.join("\r\n ", words);
    }

    private static String wordOf(byte[] utf8) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(utf8) + "?=";
    }

    private static String readFromHeader(byte[] raw) {
        try {
            return MimeParser.rawHeader(raw, "from");
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Kiểm tra cả địa chỉ envelope lẫn header From (envelope có thể là địa chỉ bounce/SRS khác header). */
    private static boolean isBlockedFor(String mailboxUser, String envelopeFrom, String headerFrom) {
        try {
            return MailStorageEngine.isSenderBlocked(mailboxUser, envelopeFrom)
                    || MailStorageEngine.isSenderBlocked(mailboxUser, headerFrom);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static final Pattern SENDER_UNSAFE = Pattern.compile("[\\s<>\"\\x00-\\x1f\\x7f]");

    static byte[] forceFromHeader(byte[] raw, String address) {
        final int n = raw.length;
        int pos = 0;
        int fromStart = -1;
        int fromEnd = -1;
        int fromColon = -1;
        byte[] firstEol = null;

        while (pos < n) {
            int nl = indexOfLf(raw, pos);
            int lineEnd = (nl < 0) ? n : nl + 1;
            int contentEnd = (nl < 0) ? n : ((nl > pos && raw[nl - 1] == '\r') ? nl - 1 : nl);
            if (contentEnd == pos) break;

            if (firstEol == null) firstEol = Arrays.copyOfRange(raw, contentEnd, lineEnd);

            if (fromStart >= 0) {
                if (raw[pos] == ' ' || raw[pos] == '\t') {
                    fromEnd = lineEnd;
                    pos = lineEnd;
                    continue;
                }
                break;
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
            writeFromLine(out, "", address, eol);
            out.write(raw, 0, n);
            return out.toByteArray();
        }

        byte[] fromEol = eolAt(raw, fromEnd);
        if (fromEol.length == 0) fromEol = eol;

        String value = new String(raw, fromColon + 1, trimEol(raw, fromEnd) - (fromColon + 1), StandardCharsets.ISO_8859_1)
                .replace("\r", "").replace("\n", "");
        String display = parseDisplayName(value);

        out.write(raw, 0, fromStart);
        writeFromLine(out, display, address, fromEol);
        out.write(raw, fromEnd, n - fromEnd);
        return out.toByteArray();
    }

    private static void writeFromLine(ByteArrayOutputStream out, String display, String address, byte[] eol) {
        byte[] line = ((display.isEmpty() ? "From: " + address : "From: " + display + " <" + address + ">"))
                .getBytes(StandardCharsets.ISO_8859_1);
        out.write(line, 0, line.length);
        out.write(eol, 0, eol.length);
    }

    private static int indexOfLf(byte[] b, int from) {
        for (int i = from; i < b.length; i++) {
            if (b[i] == '\n') return i;
        }
        return -1;
    }

    private static int fromFieldColon(byte[] b, int start, int end) {
        if (end - start < 5) return -1;
        if ((b[start] | 0x20) != 'f' || (b[start + 1] | 0x20) != 'r'
                || (b[start + 2] | 0x20) != 'o' || (b[start + 3] | 0x20) != 'm') return -1;
        int i = start + 4;
        while (i < end && (b[i] == ' ' || b[i] == '\t')) i++;
        return (i < end && b[i] == ':') ? i : -1;
    }

    private static int trimEol(byte[] b, int end) {
        int e = end;
        if (e > 0 && b[e - 1] == '\n') e--;
        if (e > 0 && b[e - 1] == '\r') e--;
        return e;
    }

    private static byte[] eolAt(byte[] b, int end) {
        int contentEnd = trimEol(b, end);
        return Arrays.copyOfRange(b, contentEnd, end);
    }

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
}