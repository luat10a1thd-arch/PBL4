// FILE: src/main/java/com/pbl4/mailserver/spam/SpamReports.java
package com.pbl4.mailserver.spam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Báo cáo "thư rác" về người gửi nội bộ + bộ đếm internalSpam, lưu ở data/spam/reports.json (ghi nguyên tử, quyền 600).
 * Chỉ lưu BĂM có muối của người báo cáo và người gửi cùng thời điểm; không lưu nội dung thư hay địa chỉ thật.
 * Cache trong RAM (classify không đọc đĩa); ghi xuống đĩa theo chu kỳ 30 giây và khi tắt server.
 * Chỉ báo cáo hợp lệ mới được lưu (tài khoản báo cáo > 24 giờ tuổi, không tự báo cáo, tối đa 20 báo cáo/ngày/tài khoản,
 * mỗi cặp (người báo cáo, người gửi) một lần). Báo cáo quá 7 ngày tự xóa. Tối đa 20.000 bản ghi.
 */
final class SpamReports {
    private SpamReports() {
    }

    static final long KEEP_MS = 7L * 24 * 3600 * 1000;
    static final long DAY_MS = 24L * 3600 * 1000;
    static final int MAX_RECORDS = 20_000;
    static final int MAX_PER_REPORTER_PER_DAY = 20;
    private static final long FLUSH_MS = 30_000L;

    private record Rec(String reporter, String sender, long t) {
    }

    private static final Object LOCK = new Object();
    private static final Object WRITE_LOCK = new Object();
    private static final ArrayDeque<Rec> RECS = new ArrayDeque<>();
    private static final HashSet<String> KEYS = new HashSet<>();
    private static final HashMap<String, Integer> PER_SENDER = new HashMap<>();
    private static long internalSpam;
    private static boolean dirty;
    private static long lastWrite;
    private static Path loadedFor;

    static {
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "spam-reports-flusher");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(() -> {
            try {
                flush(false);
            } catch (Throwable ignored) {
                // không bao giờ được làm chết luồng nền
            }
        }, 30, 30, TimeUnit.SECONDS);
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    flush(true);
                } catch (Throwable ignored) {
                    // đang tắt
                }
            }, "spam-reports-hook"));
        } catch (RuntimeException ignored) {
            // không đăng ký được hook thì thôi
        }
    }

    // ------------------------------------------------------------------ API

    /** true nếu báo cáo được ghi nhận. Hai tham số là băm hex 32 ký tự (SpamHash.hex). */
    static boolean addReport(String reporter, String sender, long now) {
        if (!SpamHash.isHex32(reporter) || !SpamHash.isHex32(sender) || reporter.equals(sender)) return false;
        synchronized (LOCK) {
            ensureLoadedLocked();
            purgeLocked(now);
            String key = reporter + sender;
            if (KEYS.contains(key)) return false;
            int today = 0;
            for (Rec r : RECS) {
                if (now - r.t() < DAY_MS && r.reporter().equals(reporter)) today++;
            }
            if (today >= MAX_PER_REPORTER_PER_DAY) return false;
            RECS.addLast(new Rec(reporter, sender, now));
            KEYS.add(key);
            PER_SENDER.merge(sender, 1, Integer::sum);
            while (RECS.size() > MAX_RECORDS) removeHeadLocked();
            dirty = true;
            return true;
        }
    }

    /** Số tài khoản khác nhau đã báo cáo người gửi này trong 7 ngày gần nhất. */
    static int reporters(String sender, long now) {
        synchronized (LOCK) {
            ensureLoadedLocked();
            purgeLocked(now);
            return PER_SENDER.getOrDefault(sender, 0);
        }
    }

    static void countInternalSpam() {
        synchronized (LOCK) {
            ensureLoadedLocked();
            internalSpam++;
            dirty = true;
        }
    }

    static long internalSpam() {
        synchronized (LOCK) {
            ensureLoadedLocked();
            return internalSpam;
        }
    }

    static int size() {
        synchronized (LOCK) {
            ensureLoadedLocked();
            return RECS.size();
        }
    }

    // ------------------------------------------------------------------ nội bộ (gọi khi đang giữ LOCK)

    private static void removeHeadLocked() {
        Rec r = RECS.pollFirst();
        if (r == null) return;
        KEYS.remove(r.reporter() + r.sender());
        PER_SENDER.computeIfPresent(r.sender(), (k, v) -> v <= 1 ? null : v - 1);
    }

    private static void purgeLocked(long now) {
        boolean any = false;
        while (!RECS.isEmpty() && now - RECS.peekFirst().t() >= KEEP_MS) {
            removeHeadLocked();
            any = true;
        }
        if (any) dirty = true;
    }

    private static void ensureLoadedLocked() {
        Path base = SpamEnv.baseDir().toAbsolutePath().normalize();
        if (base.equals(loadedFor)) return;
        RECS.clear();
        KEYS.clear();
        PER_SENDER.clear();
        internalSpam = 0;
        dirty = false;
        loadedFor = base;
        try {
            byte[] b = SpamStore.readLimited(base.resolve("reports.json"));
            if (b == null) return;
            Object o = MiniJson.parse(new String(b, StandardCharsets.UTF_8));
            if (!(o instanceof Map<?, ?> m)) return;
            if (m.get("internalSpam") instanceof Number n) internalSpam = Math.max(0, n.longValue());
            if (m.get("reports") instanceof List<?> l) {
                List<Rec> tmp = new ArrayList<>();
                for (Object x : l) {
                    if (tmp.size() >= MAX_RECORDS) break;
                    if (!(x instanceof Map<?, ?> rm)) continue;
                    Object r = rm.get("r");
                    Object s = rm.get("s");
                    Object t = rm.get("t");
                    if (r instanceof String rs && s instanceof String ss && t instanceof Number tn
                            && SpamHash.isHex32(rs) && SpamHash.isHex32(ss) && !rs.equals(ss)) {
                        tmp.add(new Rec(rs, ss, tn.longValue()));
                    }
                }
                tmp.sort((a, c) -> Long.compare(a.t(), c.t()));
                for (Rec r : tmp) {
                    String key = r.reporter() + r.sender();
                    if (KEYS.add(key)) {
                        RECS.addLast(r);
                        PER_SENDER.merge(r.sender(), 1, Integer::sum);
                    }
                }
            }
            purgeLocked(SpamEnv.nowMillis());
        } catch (Throwable t) {
            // file hỏng: bắt đầu lại từ trống
            RECS.clear();
            KEYS.clear();
            PER_SENDER.clear();
            internalSpam = 0;
        }
    }

    private static byte[] serializeLocked() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("v", 1);
        root.put("internalSpam", internalSpam);
        List<Object> list = new ArrayList<>(RECS.size());
        Iterator<Rec> it = RECS.iterator();
        while (it.hasNext()) {
            Rec r = it.next();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("r", r.reporter());
            m.put("s", r.sender());
            m.put("t", r.t());
            list.add(m);
        }
        root.put("reports", list);
        return MiniJson.write(root).getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ ghi đĩa

    /** Ghi nguyên tử nếu có thay đổi; không quá 1 lần/30 giây trừ khi force. Không ném lỗi. */
    static void flush(boolean force) {
        synchronized (WRITE_LOCK) {
            byte[] data;
            Path file;
            synchronized (LOCK) {
                if (!dirty || loadedFor == null) return;
                long now = System.currentTimeMillis();
                if (!force && now - lastWrite < FLUSH_MS) return;
                data = serializeLocked();
                file = loadedFor.resolve("reports.json");
                dirty = false;
                lastWrite = now;
            }
            try {
                SpamStore.writeAtomic(file, data);
            } catch (IOException | RuntimeException e) {
                synchronized (LOCK) {
                    dirty = true; // thử lại lượt sau
                }
            }
        }
    }

    /** Chỉ cho test: bỏ dữ liệu trong RAM; lần dùng sau sẽ nạp lại từ data/spam/reports.json (nếu có). */
    static void resetForTest() {
        synchronized (LOCK) {
            RECS.clear();
            KEYS.clear();
            PER_SENDER.clear();
            internalSpam = 0;
            dirty = false;
            loadedFor = null;
        }
    }
}