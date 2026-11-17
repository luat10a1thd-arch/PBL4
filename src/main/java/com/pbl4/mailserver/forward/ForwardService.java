// FILE: src/main/java/com/pbl4/mailserver/forward/ForwardService.java
package com.pbl4.mailserver.forward;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.Folder;
import com.pbl4.mailserver.core.MailService;
import com.pbl4.mailserver.core.OutboundQueue;
import com.pbl4.mailserver.core.UserStore;
import com.pbl4.mailserver.mime.MessageNormalizer;
import com.pbl4.mailserver.mime.MimeBuilder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tự động chuyển tiếp thư (F2). Hàm tĩnh; mọi lỗi trong apply() bị nuốt để thư vẫn vào INBOX như cũ.
 *
 * <p>Bản chuyển tiếp = nguyên văn thư gốc, chỉ THÊM ở đầu: Resent-From, Resent-To, Resent-Date,
 * Resent-Message-ID, X-PBL4-Forwarded. Header gốc và thân giữ nguyên từng byte nên DKIM gốc vẫn đúng.
 * Phong bì MAIL FROM = địa chỉ của user (không dùng người gửi gốc).
 */
public final class ForwardService {

    // ---- hook (để test) ----
    interface Enqueuer {
        boolean enqueue(String envelopeFrom, Set<String> rcpts, byte[] raw);
    }

    interface LocalDeliverer {
        boolean deliver(String envelopeFrom, String rcpt, byte[] raw);
    }

    interface Mailer {
        boolean send(String to, String subject, String body);
    }

    private static final int CODE_MAX_TRIES = 5;
    private static final int CODE_SENDS_PER_HOUR = 3;
    private static final long CODE_TTL_MS = 30 * 60_000L;
    private static final long HOUR_MS = 60L * 60 * 1000;
    private static final int MAX_FILTER_CHARS = 100;
    private static final int MAX_HOP = 3;
    private static final int HEADER_SCAN_MAX = 256 * 1024;

    private static final Pattern ADDRESS = Pattern.compile(
            "^[a-z0-9._%+-]{1,64}@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}$");
    private static final Pattern FWD_HEADER = Pattern.compile("^\\s*([^;\\s]+)\\s*(?:;\\s*hop\\s*=\\s*(\\d+))?");
    private static final Pattern ENCODED_WORD = Pattern.compile("=\\?([^?\\s]+)\\?([bBqQ])\\?([^?\\s]*)\\?=");
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object[] LOCKS = new Object[64];
    static {
        for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object();
    }

    private static final Map<String, ArrayDeque<Long>> windows = new ConcurrentHashMap<>();

    private static volatile LongSupplier clock = System::currentTimeMillis;
    private static volatile Supplier<String> domainSupplier = () -> ServerConfig.MAIL_DOMAIN;
    private static volatile Function<String, String> canonicalLocal = UserStore::canonical;
    private static volatile Function<String, String> env = System::getenv;
    private static volatile Enqueuer enqueuer = OutboundQueue::enqueue;
    private static volatile LocalDeliverer localDeliverer =
            (from, rcpt, raw) -> MailService.deliverLocal(from, Set.of(rcpt), raw, Folder.INBOX).success;
    private static volatile Mailer mailer = ForwardService::sendSystemMail;

    private ForwardService() {
    }

    // =====================================================================================================
    //  API
    // =====================================================================================================

    public static List<ForwardRule> list(String user) {
        String u = userKey(user);
        if (u.isEmpty()) return List.of();
        synchronized (lockFor(u)) {
            ForwardStore.Data d = ForwardStore.load(u);
            List<ForwardRule> out = new ArrayList<>(d.rules.size());
            for (ForwardStore.Rec r : d.rules) out.add(r.toRule());
            return out;
        }
    }

    /**
     * Thêm luật. Đích nội bộ (cùng MAIL_DOMAIN, user tồn tại): verified ngay. Đích ngoài: gửi mã 6 số tới đích,
     * verified=false cho tới khi verify() đúng.
     *
     * @throws IllegalArgumentException khi dữ liệu sai / vượt giới hạn / không gửi được mã xác minh
     */
    public static ForwardRule add(String user, String target, boolean keepCopy, String fromContains, String subjectContains) {
        if (!enabled()) throw new IllegalArgumentException("Tính năng chuyển tiếp thư đang tắt!");
        String u = userKey(user);
        if (u.isEmpty()) throw new IllegalArgumentException("Thiếu tài khoản!");

        String t = normalizeTarget(target);
        String from = cleanFilter(fromContains, "Điều kiện người gửi");
        String subj = cleanFilter(subjectContains, "Điều kiện tiêu đề");

        String dom = domain();
        String self = userAddr(u);
        boolean local = isLocalAddress(t, dom);
        String stored = t;
        if (local) {
            String canon = null;
            try {
                canon = canonicalLocal.apply(t);
            } catch (RuntimeException ignored) {
                // coi như không tồn tại
            }
            if (canon == null || canon.isBlank()) {
                throw new IllegalArgumentException("Tài khoản nhận nội bộ không tồn tại!");
            }
            stored = canon.trim().toLowerCase(Locale.ROOT);
        }
        if (t.equals(self) || stored.equals(self)) {
            throw new IllegalArgumentException("Không thể chuyển tiếp thư tới chính tài khoản của bạn!");
        }

        int maxRules = envInt("PBL4_FORWARD_MAX_RULES", 5, 1, 50);

        synchronized (lockFor(u)) {
            ForwardStore.Data d = ForwardStore.load(u);
            if (d.rules.size() >= maxRules) {
                throw new IllegalArgumentException("Mỗi tài khoản chỉ được tối đa " + maxRules + " luật chuyển tiếp!");
            }
            for (ForwardStore.Rec r : d.rules) {
                if (r.target.equals(stored)) throw new IllegalArgumentException("Địa chỉ này đã có trong danh sách chuyển tiếp!");
            }

            long now = now();
            ForwardStore.Rec rec = new ForwardStore.Rec();
            rec.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            rec.target = stored;
            rec.fromContains = from;
            rec.subjectContains = subj;
            rec.enabled = true;
            rec.keepCopy = keepCopy;
            rec.createdAt = now;

            String code = null;
            if (local) {
                rec.verified = true;
            } else {
                pruneSends(d, now);
                if (d.codeSends.size() >= CODE_SENDS_PER_HOUR) {
                    throw new IllegalArgumentException("Bạn đã yêu cầu gửi mã xác minh quá " + CODE_SENDS_PER_HOUR + " lần trong 1 giờ. Vui lòng thử lại sau!");
                }
                code = issueCode(rec, u, now);
                d.codeSends.add(now);
            }
            d.rules.add(rec);
            ForwardStore.save(u, d);

            if (code != null && !deliverCode(u, stored, code)) {
                d.rules.remove(rec);
                if (!d.codeSends.isEmpty()) d.codeSends.remove(d.codeSends.size() - 1);
                ForwardStore.save(u, d);
                throw new IllegalArgumentException("Không gửi được thư xác minh lúc này, vui lòng thử lại sau!");
            }
            return rec.toRule();
        }
    }

    /** Gửi lại mã cho luật ngoài chưa xác minh (tính vào hạn mức 3 lần/giờ). false nếu không có luật phù hợp. */
    public static boolean resendCode(String user, String ruleId) {
        String u = userKey(user);
        if (u.isEmpty() || ruleId == null) return false;
        synchronized (lockFor(u)) {
            ForwardStore.Data d = ForwardStore.load(u);
            ForwardStore.Rec r = find(d, ruleId);
            if (r == null || r.verified) return false;
            long now = now();
            pruneSends(d, now);
            if (d.codeSends.size() >= CODE_SENDS_PER_HOUR) {
                throw new IllegalArgumentException("Bạn đã yêu cầu gửi mã xác minh quá " + CODE_SENDS_PER_HOUR + " lần trong 1 giờ. Vui lòng thử lại sau!");
            }
            String code = issueCode(r, u, now);
            d.codeSends.add(now);
            ForwardStore.save(u, d);
            return deliverCode(u, r.target, code);
        }
    }

    /** Mã đúng => verified. Sai quá 5 lần hoặc quá 30 phút thì mã bị hủy (phải gửi mã mới). */
    public static boolean verify(String user, String ruleId, String code) {
        String u = userKey(user);
        if (u.isEmpty() || ruleId == null || code == null) return false;
        synchronized (lockFor(u)) {
            ForwardStore.Data d = ForwardStore.load(u);
            ForwardStore.Rec r = find(d, ruleId);
            if (r == null) return false;
            if (r.verified) return true;
            if (r.codeHash.isEmpty()) return false;
            if (now() > r.codeExp) {
                clearCode(r);
                ForwardStore.save(u, d);
                return false;
            }
            String candidate = codeHash(r.codeSalt, u, r.id, code.replaceAll("\\s+", ""));
            if (MessageDigest.isEqual(r.codeHash.getBytes(StandardCharsets.US_ASCII),
                    candidate.getBytes(StandardCharsets.US_ASCII))) {
                r.verified = true;
                clearCode(r);
                ForwardStore.save(u, d);
                return true;
            }
            r.codeTries++;
            if (r.codeTries >= CODE_MAX_TRIES) clearCode(r);
            ForwardStore.save(u, d);
            return false;
        }
    }

    public static boolean update(String user, String ruleId, Boolean enabled, Boolean keepCopy) {
        String u = userKey(user);
        if (u.isEmpty() || ruleId == null) return false;
        synchronized (lockFor(u)) {
            ForwardStore.Data d = ForwardStore.load(u);
            ForwardStore.Rec r = find(d, ruleId);
            if (r == null) return false;
            boolean changed = false;
            if (enabled != null && r.enabled != enabled) { r.enabled = enabled; changed = true; }
            if (keepCopy != null && r.keepCopy != keepCopy) { r.keepCopy = keepCopy; changed = true; }
            if (changed) ForwardStore.save(u, d);
            return true;
        }
    }

    public static boolean remove(String user, String ruleId) {
        String u = userKey(user);
        if (u.isEmpty() || ruleId == null) return false;
        synchronized (lockFor(u)) {
            ForwardStore.Data d = ForwardStore.load(u);
            ForwardStore.Rec r = find(d, ruleId);
            if (r == null) return false;
            d.rules.remove(r);
            ForwardStore.save(u, d);
            return true;
        }
    }

    /**
     * Gọi với thư SẮP lưu vào INBOX của user. Tự xếp hàng/giao các bản chuyển tiếp.
     *
     * @return false CHỈ khi có luật khớp với keepCopy=false VÀ bản chuyển tiếp đó đã được xếp hàng/giao thành công
     *         (MailService khi đó lưu thư vào &lt;user&gt;_trash). Mọi trường hợp khác, kể cả lỗi: true.
     */
    public static boolean apply(String user, byte[] raw, String envelopeFrom) {
        try {
            return applyInner(user, raw, envelopeFrom);
        } catch (Throwable t) {
            // Không log nội dung/đích: chỉ tên lỗi. Thư vẫn vào INBOX.
            log("Lỗi chuyển tiếp, giữ thư trong INBOX: " + t.getClass().getSimpleName());
            return true;
        }
    }

    // =====================================================================================================
    //  apply
    // =====================================================================================================

    private static boolean applyInner(String user, byte[] raw, String envelopeFrom) {
        if (!enabled() || raw == null || raw.length == 0) return true;
        String u = userKey(user);
        if (u.isEmpty()) return true;
        if (envelopeFrom == null || envelopeFrom.trim().isEmpty() || envelopeFrom.trim().equals("<>")) return true;
        if (raw.length > envLong("PBL4_FORWARD_MAX_SIZE_BYTES", 10L * 1024 * 1024, 1, Long.MAX_VALUE)) return true;

        List<ForwardStore.Rec> candidates = new ArrayList<>();
        synchronized (lockFor(u)) {
            for (ForwardStore.Rec r : ForwardStore.load(u).rules) {
                if (r.enabled && r.verified) candidates.add(r);
            }
        }
        if (candidates.isEmpty()) return true;

        List<String[]> headers = parseHeaders(raw);
        String fromHdr = "";
        String subjHdr = "";
        boolean haveFrom = false;
        boolean haveSubj = false;
        int maxHop = 0;
        Set<String> chain = new LinkedHashSet<>();
        for (String[] h : headers) {
            switch (h[0]) {
                case "auto-submitted": {
                    String v = h[1].trim().toLowerCase(Locale.ROOT);
                    int sc = v.indexOf(';');
                    if (sc >= 0) v = v.substring(0, sc).trim();
                    if (!v.equals("no")) return true;
                    break;
                }
                case "x-pbl4-spam-status":
                    if (h[1].trim().toLowerCase(Locale.ROOT).startsWith("yes")) return true;
                    break;
                case "x-pbl4-forwarded": {
                    Matcher m = FWD_HEADER.matcher(h[1]);
                    int hop = 1;
                    if (m.find()) {
                        chain.add(m.group(1).toLowerCase(Locale.ROOT));
                        if (m.group(2) != null) hop = m.group(2).length() > 6 ? 99 : Integer.parseInt(m.group(2));
                    }
                    maxHop = Math.max(maxHop, hop);
                    break;
                }
                case "from":
                    if (!haveFrom) { fromHdr = decodeWords(h[1]); haveFrom = true; }
                    break;
                case "subject":
                    if (!haveSubj) { subjHdr = decodeWords(h[1]); haveSubj = true; }
                    break;
                default:
                    break;
            }
        }
        if (maxHop >= MAX_HOP) return true;
        String self = userAddr(u);
        if (chain.contains(self)) return true;

        String fromText = fold(fromHdr + " " + envelopeFrom);
        String subjText = fold(subjHdr);

        // target -> có luật keepCopy=false khớp không (nhiều luật cùng đích chỉ gửi một bản)
        Map<String, Boolean> targets = new LinkedHashMap<>();
        for (ForwardStore.Rec r : candidates) {
            if (!r.fromContains.isEmpty() && !fromText.contains(fold(r.fromContains))) continue;
            if (!r.subjectContains.isEmpty() && !subjText.contains(fold(r.subjectContains))) continue;
            if (chain.contains(r.target) || r.target.equals(self)) continue; // chống vòng
            targets.merge(r.target, !r.keepCopy, (a, b) -> a || b);
        }
        if (targets.isEmpty()) return true;

        int maxPerHour = envInt("PBL4_FORWARD_MAX_PER_HOUR", 50, 1, 1_000_000);
        String dom = domain();
        int hop = maxHop + 1;
        boolean dropInbox = false;
        for (Map.Entry<String, Boolean> e : targets.entrySet()) {
            String target = e.getKey();
            if (!reserveHour(u, maxPerHour)) continue;
            boolean ok;
            try {
                byte[] fwd = buildForward(raw, self, target, hop, dom);
                ok = isLocalAddress(target, dom)
                        ? localDeliverer.deliver(self, target, fwd)
                        : enqueuer.enqueue(self, new LinkedHashSet<>(List.of(target)), fwd);
            } catch (Throwable t) {
                log("Không chuyển tiếp được một bản: " + t.getClass().getSimpleName());
                ok = false;
            }
            if (!ok) {
                refundHour(u);
            } else if (e.getValue()) {
                dropInbox = true;
            }
        }
        return !dropInbox;
    }

    private static byte[] buildForward(byte[] raw, String self, String target, int hop, String dom) {
        String eol = detectEol(raw);
        String host = dom.replaceAll("[^A-Za-z0-9.-]", "");
        if (host.isEmpty()) host = "localhost";
        String date = DATE_FMT.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(now()), ZoneId.systemDefault()));
        String block = "Resent-From: " + self + eol
                + "Resent-To: " + target + eol
                + "Resent-Date: " + date + eol
                + "Resent-Message-ID: <" + UUID.randomUUID() + "@" + host + ">" + eol
                + "X-PBL4-Forwarded: " + self + "; hop=" + hop + eol;
        byte[] head = block.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream(head.length + raw.length);
        out.write(head, 0, head.length);
        out.write(raw, 0, raw.length);
        return out.toByteArray();
    }

    private static String detectEol(byte[] raw) {
        int lim = Math.min(raw.length, 4096);
        for (int i = 0; i < lim; i++) {
            if (raw[i] == '\n') return (i > 0 && raw[i - 1] == '\r') ? "\r\n" : "\n";
        }
        return "\r\n";
    }

    // =====================================================================================================
    //  Header
    // =====================================================================================================

    /** Đọc khối header (đã gỡ gấp dòng): danh sách {tên-thường, giá-trị}. Chỉ quét tối đa 256 KB đầu. */
    static List<String[]> parseHeaders(byte[] raw) {
        List<String[]> out = new ArrayList<>();
        int n = Math.min(raw.length, HEADER_SCAN_MAX);
        int pos = 0;
        String curName = null;
        StringBuilder curVal = null;
        while (pos < n) {
            int nl = pos;
            while (nl < n && raw[nl] != '\n') nl++;
            int next = (nl < n) ? nl + 1 : n;
            int contentEnd = nl;
            if (contentEnd > pos && raw[contentEnd - 1] == '\r') contentEnd--;
            if (contentEnd == pos) break; // dòng trống: hết header

            byte b = raw[pos];
            if (b == ' ' || b == '\t') {
                if (curVal != null) {
                    curVal.append(' ').append(new String(raw, pos, contentEnd - pos, StandardCharsets.UTF_8).trim());
                }
            } else {
                int colon = pos;
                while (colon < contentEnd && raw[colon] >= 33 && raw[colon] <= 126 && raw[colon] != ':') colon++;
                if (colon == pos || colon >= contentEnd || raw[colon] != ':') break; // không phải header: là thân
                if (curName != null) out.add(new String[]{curName, curVal.toString()});
                curName = new String(raw, pos, colon - pos, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
                curVal = new StringBuilder(new String(raw, colon + 1, contentEnd - colon - 1, StandardCharsets.UTF_8).trim());
            }
            pos = next;
        }
        if (curName != null) out.add(new String[]{curName, curVal.toString()});
        return out;
    }

    /** Giải mã encoded-word RFC 2047 (B và Q) để so khớp tiêu đề/tên người gửi tiếng Việt. */
    static String decodeWords(String s) {
        if (s == null || s.indexOf("=?") < 0) return s == null ? "" : s;
        String joined = s.replaceAll("(\\?=)\\s+(=\\?)", "$1$2");
        Matcher m = ENCODED_WORD.matcher(joined);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String dec;
            try {
                String cs = m.group(1);
                int star = cs.indexOf('*');
                if (star >= 0) cs = cs.substring(0, star);
                Charset charset = Charset.forName(cs);
                byte[] bytes = m.group(2).equalsIgnoreCase("B")
                        ? Base64.getMimeDecoder().decode(m.group(3))
                        : qDecode(m.group(3));
                dec = new String(bytes, charset);
            } catch (Exception e) {
                dec = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(dec));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static byte[] qDecode(String t) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '_') {
                out.write(' ');
            } else if (c == '=' && i + 2 < t.length() + 0 && i + 2 <= t.length() - 1 + 0) {
                out.write(Integer.parseInt(t.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.write(c);
            }
        }
        return out.toByteArray();
    }

    /** Chữ thường + bỏ dấu tiếng Việt, để so khớp không phân biệt hoa thường/dấu. */
    static String fold(String in) {
        if (in == null) return "";
        String s = in.toLowerCase(Locale.ROOT).replace('đ', 'd');
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    // =====================================================================================================
    //  Kiểm tra dữ liệu, mã xác minh, hạn mức
    // =====================================================================================================

    private static String normalizeTarget(String raw) {
        if (raw == null) throw new IllegalArgumentException("Vui lòng nhập địa chỉ chuyển tiếp!");
        String t = raw.trim();
        if (t.isEmpty() || t.length() > 254) throw new IllegalArgumentException("Địa chỉ chuyển tiếp không hợp lệ!");
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 0x20 || c == 0x7F || Character.isWhitespace(c)) {
                throw new IllegalArgumentException("Địa chỉ chuyển tiếp không hợp lệ!");
            }
        }
        t = t.toLowerCase(Locale.ROOT);
        if (!ADDRESS.matcher(t).matches()) throw new IllegalArgumentException("Địa chỉ chuyển tiếp không hợp lệ!");
        return t;
    }

    private static String cleanFilter(String raw, String label) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() > MAX_FILTER_CHARS) {
            throw new IllegalArgumentException(label + " tối đa " + MAX_FILTER_CHARS + " ký tự!");
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) throw new IllegalArgumentException(label + " chứa ký tự không hợp lệ!");
        }
        return s;
    }

    private static String issueCode(ForwardStore.Rec r, String userKey, long now) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : salt) hex.append(String.format("%02x", b));
        r.codeSalt = hex.toString();
        r.codeHash = codeHash(r.codeSalt, userKey, r.id, code);
        r.codeExp = now + CODE_TTL_MS;
        r.codeTries = 0;
        return code;
    }

    private static void clearCode(ForwardStore.Rec r) {
        r.codeHash = "";
        r.codeSalt = "";
        r.codeExp = 0;
        r.codeTries = 0;
    }

    private static String codeHash(String salt, String userKey, String ruleId, String code) {
        return ForwardStore.sha256Hex(salt + "|" + userKey + "|" + ruleId + "|" + code);
    }

    private static boolean deliverCode(String userKey, String target, String code) {
        String subject = "[DUT Mail] Mã xác minh chuyển tiếp thư";
        String body = "Xin chào,\n\n"
                + "Tài khoản " + userAddr(userKey) + " vừa yêu cầu tự động chuyển tiếp thư tới địa chỉ này.\n\n"
                + "Mã xác minh (6 số): " + code + "\n\n"
                + "- Mã có hiệu lực 30 phút và chỉ nhập sai được tối đa 5 lần.\n"
                + "- Nếu không phải bạn yêu cầu, hãy bỏ qua thư này.\n";
        try {
            return mailer.send(target, subject, body);
        } catch (RuntimeException e) {
            log("Lỗi gửi thư xác minh chuyển tiếp: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /** Gửi thư hệ thống từ no-reply@MAIL_DOMAIN qua OutboundQueue (giống thư OTP khôi phục mật khẩu). */
    private static boolean sendSystemMail(String to, String subject, String body) {
        try {
            String from = "no-reply@" + domain();
            byte[] raw = MimeBuilder.fromWebForm(from, to, subject, body);
            try {
                byte[] normalized = MessageNormalizer.normalize(raw, from);
                if (normalized != null) raw = normalized;
            } catch (RuntimeException e) {
                log("Không chuẩn hóa được thư xác minh: " + e.getClass().getSimpleName());
            }
            Set<String> rcpts = new LinkedHashSet<>();
            rcpts.add(to);
            return OutboundQueue.enqueue(from, rcpts, raw);
        } catch (RuntimeException e) {
            log("Lỗi gửi thư hệ thống: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private static void pruneSends(ForwardStore.Data d, long now) {
        d.codeSends.removeIf(t -> now - t >= HOUR_MS || t > now + HOUR_MS);
    }

    private static boolean reserveHour(String u, int max) {
        long now = now();
        ArrayDeque<Long> dq = windows.computeIfAbsent(u, k -> new ArrayDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && now - dq.peekFirst() >= HOUR_MS) dq.pollFirst();
            if (dq.size() >= max) return false;
            dq.addLast(now);
            return true;
        }
    }

    private static void refundHour(String u) {
        ArrayDeque<Long> dq = windows.get(u);
        if (dq == null) return;
        synchronized (dq) {
            dq.pollLast();
        }
    }

    // =====================================================================================================
    //  Tiện ích / cấu hình
    // =====================================================================================================

    private static ForwardStore.Rec find(ForwardStore.Data d, String id) {
        for (ForwardStore.Rec r : d.rules) {
            if (r.id.equals(id)) return r;
        }
        return null;
    }

    private static String userKey(String user) {
        return user == null ? "" : user.trim().toLowerCase(Locale.ROOT);
    }

    private static String userAddr(String userKey) {
        String k = userKey.replaceAll("[\\x00-\\x1f\\x7f\\s]", "");
        return k.indexOf('@') >= 0 ? k : k + "@" + domain();
    }

    private static boolean isLocalAddress(String addr, String dom) {
        int at = addr.lastIndexOf('@');
        return at >= 0 && addr.substring(at + 1).equalsIgnoreCase(dom);
    }

    private static String domain() {
        String d = domainSupplier.get();
        return d == null ? "" : d.trim().toLowerCase(Locale.ROOT);
    }

    private static Object lockFor(String userKey) {
        return LOCKS[(userKey.hashCode() & 0x7fffffff) % LOCKS.length];
    }

    private static long now() {
        return clock.getAsLong();
    }

    private static boolean enabled() {
        try {
            String v = env.apply("PBL4_FORWARD_ENABLED");
            if (v == null || v.trim().isEmpty()) return true;
            return Boolean.parseBoolean(v.trim());
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static long envLong(String name, long def, long min, long max) {
        try {
            String v = env.apply(name);
            if (v != null && !v.trim().isEmpty()) {
                long x = Long.parseLong(v.trim());
                if (x >= min && x <= max) return x;
            }
        } catch (RuntimeException ignored) {
            // dùng mặc định
        }
        return def;
    }

    private static int envInt(String name, int def, int min, int max) {
        return (int) envLong(name, def, min, max);
    }

    private static void log(String msg) {
        System.err.println("[FORWARD] " + msg);
    }

    // =====================================================================================================
    //  Phục vụ test
    // =====================================================================================================

    static void setHooksForTest(Path dir, LongSupplier testClock, String domain, Function<String, String> canonical,
                                Function<String, String> envFn, Enqueuer enq, LocalDeliverer local, Mailer mail) {
        resetHooksForTest();
        if (dir != null) ForwardStore.baseDir = dir;
        if (testClock != null) clock = testClock;
        if (domain != null) domainSupplier = () -> domain;
        if (canonical != null) canonicalLocal = canonical;
        if (envFn != null) env = envFn;
        if (enq != null) enqueuer = enq;
        if (local != null) localDeliverer = local;
        if (mail != null) mailer = mail;
    }

    static void resetHooksForTest() {
        ForwardStore.baseDir = Path.of("data", "forward");
        clock = System::currentTimeMillis;
        domainSupplier = () -> ServerConfig.MAIL_DOMAIN;
        canonicalLocal = UserStore::canonical;
        env = System::getenv;
        enqueuer = OutboundQueue::enqueue;
        localDeliverer = (from, rcpt, raw) -> MailService.deliverLocal(from, Set.of(rcpt), raw, Folder.INBOX).success;
        mailer = ForwardService::sendSystemMail;
        windows.clear();
    }
}