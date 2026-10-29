package com.pbl4.mailserver.core;

import com.pbl4.mailserver.auth.DkimSigner;
import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.outbound.BounceBuilder;
import com.pbl4.mailserver.outbound.Deliverer;
import com.pbl4.mailserver.outbound.Deliverers;
import com.pbl4.mailserver.outbound.QueueEntry;
import com.pbl4.mailserver.outbound.RcptResult;
import com.pbl4.mailserver.outbound.RcptStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Hàng đợi gửi thư ra ngoài (A6). Lớp static như cũ; toàn bộ logic nằm trong {@link Engine}
 * để test được bằng đồng hồ giả / Deliverer giả.
 *
 * Mô hình: mỗi (thư, domain đích) là một job. Thư lưu 1 file {@code <msgId>.msg} (byte nguyên vẹn, dùng chung
 * cho mọi job của thư), mỗi job lưu 1 file {@code <msgId>.<n>.job}. RAM chỉ giữ lịch (id + domain + thời điểm).
 */
public class OutboundQueue {

    private OutboundQueue() {}

    /** Lịch thử lại (phút) sau lần thất bại thứ 1, 2, ...; sau mục cuối lặp lại mỗi 12 giờ. */
    static final long[] RETRY_MINUTES = {5, 15, 30, 60, 120, 240, 480, 720};
    /** Jitter: cộng thêm ngẫu nhiên 0..10% (không bao giờ ngắn hơn lịch gốc). */
    static final double JITTER = 0.10;

    private static final String DIR_PATH = "data/outbound";

    private static Engine defaultEngine;

    private static synchronized Engine engine() {
        if (defaultEngine == null) {
            int workers = Math.max(1, ServerConfig.OUTBOUND_WORKERS);
            AtomicInteger n = new AtomicInteger();
            ThreadPoolExecutor pool = new ThreadPoolExecutor(workers, workers, 60, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(), r -> {
                        Thread t = new Thread(r, "outbound-worker-" + n.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    });
            pool.allowCoreThreadTimeOut(true);
            Engine e = new Engine(Path.of(DIR_PATH), System::currentTimeMillis, Deliverers::get,
                    raw -> DkimSigner.sign(raw, ServerConfig.MAIL_DOMAIN),
                    (sender, bytes) -> MailService.deliverLocal("", Set.of(sender), bytes, Folder.INBOX).success,
                    pool, Math.max(1, ServerConfig.OUTBOUND_PER_DOMAIN_CONN),
                    ServerConfig.OUTBOUND_MAX_AGE_HOURS * 3_600_000L,
                    ServerConfig.MAIL_DOMAIN, ServerConfig.MAIL_HOSTNAME);
            e.start();
            defaultEngine = e;
        }
        return defaultEngine;
    }

    /** Khởi động hàng đợi: nạp lại job còn dang dở. MainApplication nên gọi khi khởi động. */
    public static void start() {
        engine();
    }

    /** false CHỈ khi không ghi được xuống đĩa. */
    public static boolean enqueue(String envelopeFrom, Set<String> externalRcpts, byte[] raw) {
        return engine().enqueue(envelopeFrom, externalRcpts, raw);
    }

    public static List<QueueEntry> snapshot() {
        return engine().snapshot();
    }

    /** Thử lại ngay. false nếu không có job, hoặc job đang được giao. */
    public static boolean retryNow(String id) {
        return engine().retryNow(id);
    }

    /** Xóa job (không sinh bounce). Job đang giao sẽ bị bỏ ngay khi lần giao hiện tại kết thúc. */
    public static boolean delete(String id) {
        return engine().delete(id);
    }

    /** Thời gian chờ cơ sở (chưa jitter) sau lần thất bại thứ {@code attempts} (>=1). */
    static long baseDelayMs(int attempts) {
        int idx = Math.min(Math.max(attempts, 1), RETRY_MINUTES.length) - 1;
        return RETRY_MINUTES[idx] * 60_000L;
    }

    // =====================================================================================================
    //  ENGINE
    // =====================================================================================================

    /** Nơi gửi bounce; trả true nếu đã giao được vào hộp thư. */
    interface BounceSink {
        boolean send(String sender, byte[] bounceBytes);
    }

    private record Sched(long at, String id, String domain) implements Comparable<Sched> {
        @Override
        public int compareTo(Sched o) {
            int c = Long.compare(at, o.at);
            return c != 0 ? c : id.compareTo(o.id);
        }
    }

    /** Trạng thái bền vững của một job. */
    private static final class Job {
        String id = "", msgId = "", from = "", domain = "", lastError = "";
        long created, next;
        int attempts;
        List<String> rcpts = new ArrayList<>();

        byte[] toBytes() throws IOException {
            Properties p = new Properties();
            p.setProperty("id", id);
            p.setProperty("msgId", msgId);
            p.setProperty("from", from);
            p.setProperty("domain", domain);
            p.setProperty("created", Long.toString(created));
            p.setProperty("next", Long.toString(next));
            p.setProperty("attempts", Integer.toString(attempts));
            p.setProperty("lastError", lastError);
            p.setProperty("rcpt.count", Integer.toString(rcpts.size()));
            for (int i = 0; i < rcpts.size(); i++) p.setProperty("rcpt." + i, rcpts.get(i));
            StringWriter sw = new StringWriter();
            p.store(sw, "Outbound job");
            return sw.toString().getBytes(StandardCharsets.UTF_8);
        }

        static Job parse(byte[] data) throws IOException {
            Properties p = new Properties();
            p.load(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8));
            try {
                Job j = new Job();
                j.id = req(p, "id");
                j.msgId = req(p, "msgId");
                j.from = p.getProperty("from", "");
                j.domain = p.getProperty("domain", "");
                j.created = Long.parseLong(req(p, "created"));
                j.next = Long.parseLong(req(p, "next"));
                j.attempts = Integer.parseInt(p.getProperty("attempts", "0"));
                j.lastError = p.getProperty("lastError", "");
                int n = Integer.parseInt(req(p, "rcpt.count"));
                for (int i = 0; i < n; i++) j.rcpts.add(req(p, "rcpt." + i));
                return j;
            } catch (NumberFormatException e) {
                throw new IOException("File job hỏng: " + e.getMessage(), e);
            }
        }

        private static String req(Properties p, String k) throws IOException {
            String v = p.getProperty(k);
            if (v == null) throw new IOException("File job thiếu trường " + k);
            return v;
        }
    }

    static final class Engine {
        private static final Pattern JOB_ID = Pattern.compile("^[A-Za-z0-9_\\-]+\\.\\d+$");
        private static final Pattern MSG_STEM = Pattern.compile("^m\\d+_[0-9a-f]{8}$");

        private final Path dir;
        private final LongSupplier clock;
        private final Supplier<Deliverer> deliverers;
        private final UnaryOperator<byte[]> signer;
        private final BounceSink bounceSink;
        private final Executor workers;
        private final int perDomain;
        private final long maxAgeMs;
        private final String mailDomain;
        private final String hostname;

        private final Object lock = new Object();
        private final TreeSet<Sched> schedule = new TreeSet<>();
        private final Map<String, Sched> byId = new HashMap<>();
        private final Set<String> running = new HashSet<>();
        private final Set<String> cancelled = new HashSet<>();
        private final Map<String, Integer> domainActive = new HashMap<>();
        private final Map<String, Integer> msgRefs = new HashMap<>();
        private final Random rnd = new Random();
        private ScheduledExecutorService ticker;

        Engine(Path dir, LongSupplier clock, Supplier<Deliverer> deliverers, UnaryOperator<byte[]> signer,
               BounceSink bounceSink, Executor workers, int perDomain, long maxAgeMs,
               String mailDomain, String hostname) {
            this.dir = dir;
            this.clock = clock;
            this.deliverers = deliverers;
            this.signer = signer;
            this.bounceSink = bounceSink;
            this.workers = workers;
            this.perDomain = Math.max(1, perDomain);
            this.maxAgeMs = maxAgeMs;
            this.mailDomain = mailDomain;
            this.hostname = hostname;
        }

        /** Nạp job cũ và bật bộ định thời nền (1 luồng daemon, quét mỗi giây). */
        void start() {
            loadPersisted();
            ticker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "outbound-scheduler");
                t.setDaemon(true);
                return t;
            });
            ticker.scheduleWithFixedDelay(() -> {
                try {
                    tick();
                } catch (Throwable t) {
                    log("Bộ định thời lỗi: " + t);
                }
            }, 1, 1, TimeUnit.SECONDS);
        }

        // ---------------------------------------------------------------- API

        boolean enqueue(String from, Set<String> rcpts, byte[] raw) {
            if (raw == null || rcpts == null || rcpts.isEmpty()) return false;
            String envFrom = from == null ? "" : from.trim();

            Map<String, List<String>> byDomain = new LinkedHashMap<>();
            for (String r : rcpts) {
                if (r == null || r.isBlank()) continue;
                String rc = r.trim();
                byDomain.computeIfAbsent(domainOf(rc), d -> new ArrayList<>()).add(rc);
            }
            if (byDomain.isEmpty()) return false;

            long now = clock.getAsLong();
            String msgId = "m" + now + "_" + UUID.randomUUID().toString().substring(0, 8).toLowerCase();
            List<Path> written = new ArrayList<>();
            List<Job> jobs = new ArrayList<>();
            try {
                Files.createDirectories(dir);
                Path msgPath = dir.resolve(msgId + ".msg");
                writeAtomic(msgPath, raw);
                written.add(msgPath);

                int n = 0;
                for (Map.Entry<String, List<String>> e : byDomain.entrySet()) {
                    Job j = new Job();
                    j.id = msgId + "." + (n++);
                    j.msgId = msgId;
                    j.from = envFrom;
                    j.domain = e.getKey();
                    j.created = now;
                    j.next = now;
                    j.attempts = 0;
                    j.rcpts = new ArrayList<>(e.getValue());
                    Path jp = jobPath(j.id);
                    writeAtomic(jp, j.toBytes());
                    written.add(jp);
                    jobs.add(j);
                }
            } catch (IOException | RuntimeException e) {
                log("Không ghi được job xuống đĩa: " + e.getMessage());
                for (Path p : written) {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                }
                return false;
            }

            synchronized (lock) {
                msgRefs.put(msgId, jobs.size());
                for (Job j : jobs) addSched(new Sched(j.next, j.id, j.domain));
            }
            tick();
            return true;
        }

        List<QueueEntry> snapshot() {
            List<QueueEntry> list = new ArrayList<>();
            if (!Files.isDirectory(dir)) return list;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.job")) {
                for (Path p : ds) {
                    try {
                        Job j = Job.parse(Files.readAllBytes(p));
                        long next = j.next;
                        synchronized (lock) {
                            Sched s = byId.get(j.id);
                            if (s != null) next = s.at();
                        }
                        list.add(new QueueEntry(j.id, j.from, j.domain, j.rcpts, j.attempts, next, j.lastError));
                    } catch (IOException | RuntimeException ignored) {
                        // job đang được ghi lại hoặc hỏng: bỏ qua trong snapshot
                    }
                }
            } catch (IOException e) {
                log("Không liệt kê được hàng đợi: " + e.getMessage());
            }
            list.sort(Comparator.comparingLong(QueueEntry::nextAttemptEpochMs).thenComparing(QueueEntry::id));
            return list;
        }

        boolean retryNow(String id) {
            if (id == null || !JOB_ID.matcher(id).matches()) return false;
            synchronized (lock) {
                if (running.contains(id)) return false;
                Sched s = byId.get(id);
                if (s == null) return false;
                schedule.remove(s);
                byId.remove(id);
                addSched(new Sched(clock.getAsLong(), id, s.domain()));
            }
            tick();
            return true;
        }

        boolean delete(String id) {
            if (id == null || !JOB_ID.matcher(id).matches()) return false;
            synchronized (lock) {
                Sched s = byId.remove(id);
                if (s != null) schedule.remove(s);
                if (running.contains(id)) {
                    cancelled.add(id); // sẽ dọn khi lần giao hiện tại kết thúc
                    return true;
                }
                try {
                    boolean existed = Files.deleteIfExists(jobPath(id));
                    if (existed) releaseMsg(msgIdOf(id));
                    return existed;
                } catch (IOException e) {
                    log("Không xóa được job " + id + ": " + e.getMessage());
                    return false;
                }
            }
        }

        // ---------------------------------------------------------------- lập lịch

        /** Đưa các job đến hạn (và domain còn chỗ) cho worker. Gọi được từ mọi luồng. */
        void tick() {
            List<Sched> toRun = new ArrayList<>();
            synchronized (lock) {
                long now = clock.getAsLong();
                Iterator<Sched> it = schedule.iterator();
                while (it.hasNext()) {
                    Sched s = it.next();
                    if (s.at() > now) break;
                    if (domainActive.getOrDefault(s.domain(), 0) >= perDomain) continue;
                    it.remove();
                    byId.remove(s.id());
                    running.add(s.id());
                    domainActive.merge(s.domain(), 1, Integer::sum);
                    toRun.add(s);
                }
            }
            for (Sched s : toRun) {
                try {
                    workers.execute(() -> runJob(s));
                } catch (RejectedExecutionException e) {
                    synchronized (lock) {
                        finishRun(s);
                        addSched(new Sched(clock.getAsLong() + 1000, s.id(), s.domain()));
                    }
                }
            }
        }

        private void addSched(Sched s) {
            schedule.add(s);
            byId.put(s.id(), s);
        }

        private void finishRun(Sched s) {
            running.remove(s.id());
            domainActive.merge(s.domain(), -1, Integer::sum);
            domainActive.remove(s.domain(), 0);
        }

        // ---------------------------------------------------------------- giao thư

        private void runJob(Sched s) {
            try {
                process(s);
            } catch (Throwable t) {
                log("Lỗi xử lý job " + s.id() + ": " + t);
                synchronized (lock) {
                    if (cancelled.remove(s.id())) {
                        try {
                            if (Files.deleteIfExists(jobPath(s.id()))) releaseMsg(msgIdOf(s.id()));
                        } catch (IOException ignored) { }
                    } else if (Files.exists(jobPath(s.id()))) {
                        addSched(new Sched(clock.getAsLong() + 600_000L, s.id(), s.domain()));
                    }
                }
            } finally {
                synchronized (lock) {
                    finishRun(s);
                }
                tick(); // domain vừa rảnh chỗ
            }
        }

        private void process(Sched s) throws IOException {
            String id = s.id();
            Path jp = jobPath(id);
            if (!Files.exists(jp)) return; // đã bị xóa

            Job job = Job.parse(Files.readAllBytes(jp));
            Path mp = dir.resolve(job.msgId + ".msg");
            if (!Files.exists(mp)) {
                log("Job " + id + " mất file thư, bỏ job.");
                Files.deleteIfExists(jp);
                releaseMsg(job.msgId);
                return;
            }
            byte[] raw = Files.readAllBytes(mp);

            byte[] signed;
            try {
                signed = signer.apply(raw);
                if (signed == null) signed = raw;
            } catch (Throwable t) {
                log("Ký DKIM lỗi, gửi bản chưa ký: " + t);
                signed = raw;
            }

            Map<String, RcptResult> results = new HashMap<>();
            String failNote = null;
            try {
                List<RcptResult> rs = deliverers.get().deliver(job.from, job.domain, List.copyOf(job.rcpts), signed);
                if (rs != null) for (RcptResult r : rs) if (r != null) results.putIfAbsent(r.rcpt(), r);
            } catch (Throwable t) { // hợp đồng cấm ném, nhưng phòng thủ
                failNote = "Deliverer ném ngoại lệ: " + t;
                log(failNote);
            }

            List<String> pending = new ArrayList<>();
            Map<String, String> deferDiag = new HashMap<>();
            List<BounceBuilder.Failure> failures = new ArrayList<>();
            String lastErr = job.lastError;
            for (String rcpt : job.rcpts) {
                RcptResult r = results.get(rcpt);
                if (r == null) {
                    String d = failNote != null ? failNote : "Không nhận được kết quả giao thư cho người nhận này";
                    pending.add(rcpt);
                    deferDiag.put(rcpt, d);
                    lastErr = d;
                    continue;
                }
                RcptStatus st = r.status();
                if (st == RcptStatus.DELIVERED) {
                    log("Đã giao " + rcpt + " (job " + id + ")");
                } else if (st == RcptStatus.FAILED) {
                    failures.add(new BounceBuilder.Failure(rcpt, r.diagnostic(), false));
                } else {
                    pending.add(rcpt);
                    deferDiag.put(rcpt, r.diagnostic());
                    lastErr = r.diagnostic();
                }
            }

            job.attempts++;
            long now = clock.getAsLong();
            if (!pending.isEmpty() && now >= job.created + maxAgeMs) {
                for (String rcpt : pending) {
                    failures.add(new BounceBuilder.Failure(rcpt, deferDiag.getOrDefault(rcpt, lastErr), true));
                }
                pending.clear();
            }

            // Bounce TRƯỚC khi ghi trạng thái: nếu sập giữa chừng thì tệ nhất là bounce trùng, không mất bounce.
            if (!failures.isEmpty()) sendBounce(job, failures, raw, now);

            boolean dropped;
            synchronized (lock) {
                dropped = cancelled.remove(id);
            }
            if (dropped || pending.isEmpty()) {
                Files.deleteIfExists(jp);
                releaseMsg(job.msgId);
                return;
            }

            long delay = jitter(baseDelayMs(job.attempts));
            long next = Math.min(now + delay, job.created + maxAgeMs); // lần cuối đúng hạn để hết hạn kịp thời
            job.rcpts = pending;
            job.lastError = lastErr == null ? "" : lastErr;
            job.next = next;
            writeAtomic(jp, job.toBytes());
            synchronized (lock) {
                addSched(new Sched(next, id, s.domain()));
            }
        }

        private void sendBounce(Job job, List<BounceBuilder.Failure> failures, byte[] raw, long now) {
            if (!BounceBuilder.shouldBounce(job.from)) {
                log("Không bounce cho người gửi '" + job.from + "' (null sender/MAILER-DAEMON).");
                return;
            }
            try {
                byte[] dsn = BounceBuilder.build(mailDomain, hostname, job.from, job.domain, failures, raw, now);
                boolean ok = bounceSink.send(job.from, dsn);
                if (!ok) log("Không giao được bounce về " + job.from);
            } catch (Throwable t) {
                log("Lỗi tạo/gửi bounce: " + t);
            }
        }

        private long jitter(long baseMs) {
            return baseMs + (long) (baseMs * JITTER * rnd.nextDouble());
        }

        // ---------------------------------------------------------------- nạp lại sau khởi động

        void loadPersisted() {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                log("Không tạo được thư mục hàng đợi: " + e.getMessage());
                return;
            }
            deleteGlob("*.tmp");

            int loaded = 0;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.job")) {
                for (Path p : ds) {
                    try {
                        Job j = Job.parse(Files.readAllBytes(p));
                        if (!Files.exists(dir.resolve(j.msgId + ".msg"))) {
                            log("Job " + j.id + " mất file thư khi nạp lại, xóa job.");
                            Files.deleteIfExists(p);
                            continue;
                        }
                        synchronized (lock) {
                            msgRefs.merge(j.msgId, 1, Integer::sum);
                            addSched(new Sched(j.next, j.id, j.domain));
                        }
                        loaded++;
                    } catch (IOException | RuntimeException e) {
                        log("Bỏ qua file job hỏng " + p.getFileName() + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                log("Không liệt kê được job khi nạp lại: " + e.getMessage());
            }

            // Thư mồ côi (không còn job nào) -> xóa; chỉ đụng file đặt tên theo định dạng mới.
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.msg")) {
                for (Path p : ds) {
                    String name = p.getFileName().toString();
                    String stem = name.substring(0, name.length() - 4);
                    boolean referenced;
                    synchronized (lock) {
                        referenced = msgRefs.containsKey(stem);
                    }
                    if (!referenced && MSG_STEM.matcher(stem).matches()) Files.deleteIfExists(p);
                }
            } catch (IOException e) {
                log("Không dọn được thư mồ côi: " + e.getMessage());
            }
            log("Đã nạp lại " + loaded + " job.");
            tick();
        }

        private void deleteGlob(String glob) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
                for (Path p : ds) Files.deleteIfExists(p);
            } catch (IOException ignored) { }
        }

        // ---------------------------------------------------------------- tiện ích

        private Path jobPath(String id) {
            return dir.resolve(id + ".job");
        }

        private static String msgIdOf(String jobId) {
            return jobId.substring(0, jobId.lastIndexOf('.'));
        }

        private static String domainOf(String rcpt) {
            int at = rcpt.lastIndexOf('@');
            return at < 0 ? "" : rcpt.substring(at + 1).toLowerCase();
        }

        /** Giảm tham chiếu thư; hết job thì xóa file thư. */
        private void releaseMsg(String msgId) {
            synchronized (lock) {
                Integer c = msgRefs.merge(msgId, -1, Integer::sum);
                if (c == null || c <= 0) {
                    msgRefs.remove(msgId);
                    try {
                        Files.deleteIfExists(dir.resolve(msgId + ".msg"));
                    } catch (IOException e) {
                        log("Không xóa được file thư " + msgId + ": " + e.getMessage());
                    }
                }
            }
        }

        /** Ghi file tạm + fsync rồi rename nguyên tử. */
        static void writeAtomic(Path target, byte[] data) throws IOException {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.wrap(data);
                while (buf.hasRemaining()) ch.write(buf);
                ch.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private static void log(String msg) {
            System.err.println("[OUTBOUND QUEUE] " + msg);
        }
    }
}