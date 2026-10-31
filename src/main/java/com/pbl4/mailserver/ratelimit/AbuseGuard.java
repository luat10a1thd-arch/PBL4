package com.pbl4.mailserver.ratelimit;

import com.pbl4.mailserver.config.ServerConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Chống lạm dụng theo IP cho cổng SMTP: kết nối đồng thời, số thư mỗi phút, RCPT lỗi liên tiếp
 * (chống dò tài khoản) và cấm tạm IP vi phạm. Tự dọn bộ nhớ.
 *
 * API tĩnh theo CONTRACT dùng một Engine mặc định (cấu hình từ ServerConfig). Lớp {@link Engine}
 * cho phép dựng bản riêng (test, đồng hồ giả). IP loopback (127.x, ::1) được MIỄN theo mặc định
 * để test/healthcheck nội bộ không bị chặn.
 *
 * Bổ sung ngoài CONTRACT: {@link #release(String)} (SmtpListener gọi khi đóng kết nối đã được
 * allowConnect cho qua) và {@link #recordGoodRcpt(String)}.
 * Env tùy chọn: PBL4_ABUSE_BAD_RCPT_LIMIT (mặc định 10), PBL4_ABUSE_BAN_MS (mặc định 600000).
 */
public final class AbuseGuard {

    private static final int DEFAULT_BAD_RCPT_LIMIT = 10;
    private static final long DEFAULT_BAN_MS = 10 * 60_000L;

    private static final Engine DEFAULT = new Engine(
            ServerConfig.INBOUND_MAX_CONN_PER_IP,
            ServerConfig.INBOUND_MSGS_PER_IP_PER_MIN,
            envInt("PBL4_ABUSE_BAD_RCPT_LIMIT", DEFAULT_BAD_RCPT_LIMIT),
            envLong("PBL4_ABUSE_BAN_MS", DEFAULT_BAN_MS),
            true,
            System::currentTimeMillis);

    private AbuseGuard() {}

    private static int envInt(String name, int def) {
        try {
            String v = System.getenv(name);
            if (v != null) {
                int n = Integer.parseInt(v.trim());
                if (n >= 1) return n;
            }
        } catch (NumberFormatException ignored) {}
        return def;
    }

    private static long envLong(String name, long def) {
        try {
            String v = System.getenv(name);
            if (v != null) {
                long n = Long.parseLong(v.trim());
                if (n >= 1) return n;
            }
        } catch (NumberFormatException ignored) {}
        return def;
    }

    public static Engine defaultEngine() {
        return DEFAULT;
    }

    /** SmtpListener tự gọi. false = từ chối kết nối. true = đã cộng 1 kết nối, phải {@link #release}. */
    public static boolean allowConnect(String ip) {
        return DEFAULT.allowConnect(ip);
    }

    /** Gọi trước DATA. false = IP vượt số thư/phút hoặc đang bị cấm tạm. */
    public static boolean allowMessage(String ip) {
        return DEFAULT.allowMessage(ip);
    }

    /** Ghi nhận RCPT bị từ chối (user không tồn tại...). false = IP vừa bị cấm tạm, handler phải ngắt phiên. */
    public static boolean recordBadRcpt(String ip) {
        return DEFAULT.recordBadRcpt(ip);
    }

    public static void recordGoodRcpt(String ip) {
        DEFAULT.recordGoodRcpt(ip);
    }

    public static void release(String ip) {
        DEFAULT.release(ip);
    }

    // ==================================================================

    public static final class Engine {
        private static final long IDLE_FORGET_MS = 10 * 60_000L;
        private static final long SWEEP_INTERVAL_MS = 60_000L;
        private static final int MAX_TRACKED_IPS = 500_000;

        private final int maxConnPerIp;
        private final int msgsPerMin;
        private final int badRcptLimit;
        private final long banMs;
        private final boolean exemptLoopback;
        private final LongSupplier clock;
        private final ConcurrentHashMap<String, State> map = new ConcurrentHashMap<>();
        private final AtomicLong nextSweep;

        private static final class State {
            int conns;
            long windowStart;
            int msgs;
            int badRcpt;
            int strikes;
            long bannedUntil;
            long touch;
            boolean dead;
        }

        private interface Op<T> {
            T apply(State s, long now);
        }

        /**
         * @param maxConnPerIp   kết nối đồng thời tối đa mỗi IP (0 = không giới hạn)
         * @param msgsPerMin     số thư tối đa mỗi IP mỗi phút (0 = không giới hạn)
         * @param badRcptLimit   số RCPT lỗi liên tiếp thì cấm tạm
         * @param banMs          thời gian cấm cơ bản (lần vi phạm lặp lại nhân lên, tối đa 6 lần)
         * @param exemptLoopback miễn mọi giới hạn cho 127.x / ::1
         * @param clock          đồng hồ ms (test dùng đồng hồ giả)
         */
        public Engine(int maxConnPerIp, int msgsPerMin, int badRcptLimit, long banMs,
                      boolean exemptLoopback, LongSupplier clock) {
            this.maxConnPerIp = maxConnPerIp;
            this.msgsPerMin = msgsPerMin;
            this.badRcptLimit = Math.max(1, badRcptLimit);
            this.banMs = Math.max(1L, banMs);
            this.exemptLoopback = exemptLoopback;
            this.clock = clock;
            this.nextSweep = new AtomicLong(clock.getAsLong() + SWEEP_INTERVAL_MS);
        }

        private boolean skip(String ip) {
            if (ip == null || ip.isEmpty()) return true;
            return exemptLoopback && (ip.startsWith("127.") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1"));
        }

        private <T> T with(String ip, Op<T> op) {
            long now = clock.getAsLong();
            maybeSweep(now);
            for (;;) {
                State s = map.computeIfAbsent(ip, k -> new State());
                synchronized (s) {
                    if (s.dead) {
                        continue; // vừa bị dọn: tạo lại
                    }
                    s.touch = now;
                    return op.apply(s, now);
                }
            }
        }

        public boolean allowConnect(String ip) {
            if (skip(ip)) return true;
            return with(ip, (s, now) -> {
                if (s.bannedUntil > now) return false;
                if (maxConnPerIp > 0 && s.conns >= maxConnPerIp) return false;
                s.conns++;
                return true;
            });
        }

        public void release(String ip) {
            if (skip(ip)) return;
            State s = map.get(ip);
            if (s == null) return;
            synchronized (s) {
                if (!s.dead && s.conns > 0) s.conns--;
            }
        }

        public boolean allowMessage(String ip) {
            if (skip(ip)) return true;
            return with(ip, (s, now) -> {
                if (s.bannedUntil > now) return false;
                if (msgsPerMin > 0) {
                    if (now - s.windowStart >= 60_000L) {
                        s.windowStart = now;
                        s.msgs = 0;
                    }
                    if (s.msgs >= msgsPerMin) return false;
                    s.msgs++;
                }
                s.badRcpt = 0; // có thư hợp lệ đi qua: chuỗi RCPT lỗi bị ngắt
                return true;
            });
        }

        public boolean recordBadRcpt(String ip) {
            if (skip(ip)) return true;
            return with(ip, (s, now) -> {
                if (s.bannedUntil > now) return false;
                s.badRcpt++;
                if (s.badRcpt >= badRcptLimit) {
                    s.badRcpt = 0;
                    s.strikes++;
                    s.bannedUntil = now + banMs * Math.min(s.strikes, 6);
                    return false;
                }
                return true;
            });
        }

        public void recordGoodRcpt(String ip) {
            if (skip(ip)) return;
            State s = map.get(ip);
            if (s == null) return;
            synchronized (s) {
                if (!s.dead) s.badRcpt = 0;
            }
        }

        /** Cấm tạm thủ công (quản trị/test). */
        public void ban(String ip, long ms) {
            if (ip == null || ip.isEmpty()) return;
            with(ip, (s, now) -> {
                s.bannedUntil = Math.max(s.bannedUntil, now + ms);
                return Boolean.TRUE;
            });
        }

        public boolean isBanned(String ip) {
            if (skip(ip)) return false;
            State s = map.get(ip);
            if (s == null) return false;
            synchronized (s) {
                return !s.dead && s.bannedUntil > clock.getAsLong();
            }
        }

        /** Số IP đang được theo dõi (test/thống kê). */
        public int trackedIps() {
            return map.size();
        }

        private void maybeSweep(long now) {
            long due = nextSweep.get();
            if (now >= due && nextSweep.compareAndSet(due, now + SWEEP_INTERVAL_MS)) {
                sweep();
            }
        }

        /** Dọn các IP nhàn rỗi (không kết nối, hết cấm, lâu không hoạt động). */
        public void sweep() {
            long now = clock.getAsLong();
            long idle = map.size() > MAX_TRACKED_IPS ? 30_000L : IDLE_FORGET_MS;
            for (Map.Entry<String, State> e : map.entrySet()) {
                State s = e.getValue();
                synchronized (s) {
                    if (!s.dead && s.conns == 0 && s.bannedUntil <= now && now - s.touch > idle) {
                        s.dead = true;
                        map.remove(e.getKey(), s);
                    }
                }
            }
        }
    }
}