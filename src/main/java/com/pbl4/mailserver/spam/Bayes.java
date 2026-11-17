// FILE: src/main/java/com/pbl4/mailserver/spam/Bayes.java
package com.pbl4.mailserver.spam;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bộ lọc Bayes riêng cho từng user.
 * - Token được băm SHA-1(muối-riêng-user + token) cắt 8 byte: không lưu chữ thật.
 * - p(token) theo Graham, làm mịn Laplace; f(w) Robinson (độ mạnh 1, tiên nghiệm 0.5); gộp bằng tổng log-odds
 *   của tối đa 20 token "đáng chú ý" nhất, quy về điểm bằng 4*tanh(tổng/8) (khoảng -4..+4).
 * - Chỉ dùng khi đã học >= 20 spam và >= 20 ham.
 * - Lưu băm nội dung thư đã học (tối đa 5.000) để không đếm đôi và để gán lại nhãn khi user đổi ý.
 * - Tối đa 50.000 token; vượt thì tỉa token ít dùng/cũ nhất xuống 45.000.
 */
final class Bayes {
    static final int MAX_TOKENS = 50_000;
    static final int PRUNE_TO = 45_000;
    static final int MAX_DOCS = 5_000;
    static final int MIN_EACH = 20;
    static final int TOP = 20;
    static final int MAX_PER_MAIL = 4000;
    private static final int MAGIC = 0x50424231; // "PBB1"
    private static final long WRITE_INTERVAL_MS = 30_000L;

    private static final ThreadLocal<MessageDigest> SHA1 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });

    private final byte[] salt;
    private final HashMap<Long, int[]> tokens = new HashMap<>();
    private final LinkedHashMap<Long, Boolean> docs = new LinkedHashMap<>();
    private int nSpam;
    private int nHam;
    private boolean dirty;
    private long lastWrite;

    Bayes() {
        this.salt = new byte[16];
        new SecureRandom().nextBytes(salt);
    }

    private Bayes(byte[] salt) {
        this.salt = salt;
    }

    // ------------------------------------------------------------------ token hóa

    static Set<String> tokenize(MailView v) {
        Set<String> out = new LinkedHashSet<>();
        addWords(out, v.subject, "s:", 100);
        addWords(out, v.text, "", 4000);
        String fa = SpamAddr.extract(v.from);
        if (!fa.isEmpty()) out.add("h:from:" + SpamAddr.baseDomain(SpamAddr.domainOf(fa)));
        if (!v.html.isEmpty()) out.add("h:html");
        int c = 0;
        for (String n : v.fileNames) {
            int dot = n.lastIndexOf('.');
            String e = dot < 0 ? "" : n.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
            if (e.length() <= 8) out.add("h:ext:" + e);
            if (++c >= 10) break;
        }
        c = 0;
        for (HtmlLinks.Link l : v.links) {
            String h = HtmlLinks.host(l.href());
            if (!h.isEmpty()) out.add("h:host:" + SpamAddr.baseDomain(h));
            if (++c >= 10) break;
        }
        return out;
    }

    static String contentKey(MailView v) {
        return RuleEngine.fold(v.subject + "\n" + v.text);
    }

    private static boolean allDigits(String t) {
        for (int i = 0; i < t.length(); i++) {
            if (!Character.isDigit(t.charAt(i))) return false;
        }
        return true;
    }

    private static void addWords(Set<String> out, String s, String prefix, int maxWords) {
        if (s == null || s.isEmpty()) return;
        int n = Math.min(s.length(), 64 * 1024);
        StringBuilder w = new StringBuilder();
        String prev = null;
        int words = 0;
        for (int i = 0; i <= n && words < maxWords && out.size() < MAX_PER_MAIL; i++) {
            char c = i < n ? s.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c)) {
                if (w.length() < 40) w.append(Character.toLowerCase(c));
            } else if (w.length() > 0) {
                String t = w.toString();
                w.setLength(0);
                if (t.length() >= 2 && t.length() <= 30 && !allDigits(t)) {
                    out.add(prefix + t);
                    words++;
                    if (prev != null) out.add(prefix + prev + " " + t);
                    prev = t;
                } else {
                    prev = null;
                }
            }
        }
    }

    // ------------------------------------------------------------------ băm

    private long hash(String token) {
        MessageDigest md = SHA1.get();
        md.reset();
        md.update(salt);
        md.update(token.getBytes(StandardCharsets.UTF_8));
        byte[] d = md.digest();
        long r = 0;
        for (int i = 0; i < 8; i++) r = (r << 8) | (d[i] & 0xFFL);
        return r;
    }

    private static int today() {
        return (int) (SpamEnv.nowMillis() / 86_400_000L);
    }

    // ------------------------------------------------------------------ chấm điểm

    synchronized boolean ready() {
        return nSpam >= MIN_EACH && nHam >= MIN_EACH;
    }

    /** Điểm trong khoảng -4..+4; 0 nếu chưa đủ dữ liệu học. */
    synchronized double score(Collection<String> toks) {
        if (!ready()) return 0.0;
        List<double[]> cand = new ArrayList<>();
        for (String t : toks) {
            int[] e = tokens.get(hash(t));
            if (e == null) continue;
            int n = e[0] + e[1];
            if (n <= 0) continue;
            double ps = (e[0] + 1.0) / (nSpam + 2.0);
            double ph = (e[1] + 1.0) / (nHam + 2.0);
            double p = ps / (ps + ph);
            double f = (0.5 + n * p) / (1.0 + n);
            f = Math.max(0.01, Math.min(0.99, f));
            double dist = Math.abs(f - 0.5);
            if (dist >= 0.1) cand.add(new double[]{dist, Math.log(f / (1.0 - f))});
        }
        cand.sort((a, b) -> Double.compare(b[0], a[0]));
        double sum = 0;
        for (int i = 0; i < cand.size() && i < TOP; i++) sum += cand.get(i)[1];
        return 4.0 * Math.tanh(sum / 8.0);
    }

    // ------------------------------------------------------------------ học

    /** 0 = không đổi, 1 = spam mới, 2 = ham mới, 3 = ham->spam, 4 = spam->ham. */
    synchronized int learn(String content, Collection<String> toks, boolean spam) {
        long key = hash("\u0000doc\u0000" + content);
        int day = today();
        Boolean prev = docs.get(key);
        if (prev == null) {
            apply(toks, spam, +1, day);
            if (spam) nSpam++;
            else nHam++;
            docs.put(key, spam);
            trimDocs();
            dirty = true;
            return spam ? 1 : 2;
        }
        if (prev == spam) return 0;
        apply(toks, prev, -1, day);
        if (prev) nSpam = Math.max(0, nSpam - 1);
        else nHam = Math.max(0, nHam - 1);
        apply(toks, spam, +1, day);
        if (spam) nSpam++;
        else nHam++;
        docs.put(key, spam);
        dirty = true;
        return spam ? 3 : 4;
    }

    private void trimDocs() {
        while (docs.size() > MAX_DOCS) {
            Iterator<Long> it = docs.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    private void apply(Collection<String> toks, boolean spam, int delta, int day) {
        int idx = spam ? 0 : 1;
        for (String t : toks) {
            long k = hash(t);
            int[] e = tokens.get(k);
            if (e == null) {
                if (delta < 0) continue;
                e = new int[3];
                tokens.put(k, e);
            }
            e[idx] = Math.max(0, e[idx] + delta);
            e[2] = day;
            if (delta < 0 && e[0] == 0 && e[1] == 0) tokens.remove(k);
        }
        if (tokens.size() > MAX_TOKENS) prune();
    }

    private void prune() {
        List<Map.Entry<Long, int[]>> es = new ArrayList<>(tokens.entrySet());
        es.sort(Comparator.comparingInt((Map.Entry<Long, int[]> e) -> e.getValue()[0] + e.getValue()[1])
                .thenComparingInt(e -> e.getValue()[2]));
        int remove = tokens.size() - PRUNE_TO;
        for (int i = 0; i < remove && i < es.size(); i++) tokens.remove(es.get(i).getKey());
    }

    // ------------------------------------------------------------------ lưu/nạp

    private byte[] toBytes() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(tokens.size() * 20 + 1024);
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(MAGIC);
        out.write(salt);
        out.writeInt(nSpam);
        out.writeInt(nHam);
        out.writeInt(tokens.size());
        for (Map.Entry<Long, int[]> e : tokens.entrySet()) {
            out.writeLong(e.getKey());
            out.writeInt(e.getValue()[0]);
            out.writeInt(e.getValue()[1]);
            out.writeInt(e.getValue()[2]);
        }
        out.writeInt(docs.size());
        for (Map.Entry<Long, Boolean> e : docs.entrySet()) {
            out.writeLong(e.getKey());
            out.writeBoolean(e.getValue());
        }
        out.flush();
        return bos.toByteArray();
    }

    /** Ghi nguyên tử (file tạm 600 rồi rename), không quá 1 lần/30 giây trừ khi force. */
    synchronized void flushTo(Path file, boolean force) {
        if (!dirty) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastWrite < WRITE_INTERVAL_MS) return;
        try {
            SpamStore.writeAtomic(file, toBytes());
            dirty = false;
            lastWrite = now;
        } catch (IOException | RuntimeException e) {
            // thử lại ở lượt sau
        }
    }

    /** null nếu không có file hoặc file hỏng/không hợp lệ. */
    static Bayes load(Path file) throws IOException {
        byte[] b = SpamStore.readLimited(file);
        if (b == null) return null;
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
        if (in.readInt() != MAGIC) return null;
        byte[] salt = new byte[16];
        in.readFully(salt);
        Bayes r = new Bayes(salt);
        r.nSpam = in.readInt();
        r.nHam = in.readInt();
        if (r.nSpam < 0 || r.nHam < 0) return null;
        int n = in.readInt();
        if (n < 0 || n > MAX_TOKENS + 1000) return null;
        for (int i = 0; i < n; i++) {
            long k = in.readLong();
            int s = in.readInt();
            int h = in.readInt();
            int d = in.readInt();
            r.tokens.put(k, new int[]{Math.max(0, s), Math.max(0, h), d});
        }
        int dn = in.readInt();
        if (dn < 0 || dn > MAX_DOCS + 10) return null;
        for (int i = 0; i < dn; i++) {
            long k = in.readLong();
            r.docs.put(k, in.readBoolean());
        }
        return r;
    }
}