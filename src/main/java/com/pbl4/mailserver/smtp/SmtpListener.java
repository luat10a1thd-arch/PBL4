package com.pbl4.mailserver.smtp;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.ratelimit.AbuseGuard;
import com.pbl4.mailserver.smtp.wire.SmtpConnection;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Máy chủ lắng nghe SMTP dùng pool thật sự theo cấu hình (SMTP_POOL_SIZE, SMTP_QUEUE_SIZE,
 * SOCKET_BACKLOG, SMTP_MAX_CONN_PER_IP). Mỗi kết nối: bọc thành SmtpConnection rồi giao cho
 * SessionHandler mới (factory.get()) trong một worker.
 *
 * Từ chối bằng "421 ..." (rồi đóng): quá số kết nối/IP, IP bị AbuseGuard chặn, hàng đợi đầy
 * ("421 4.3.2 Service busy"). Với implicitTls, các trường hợp từ chối chỉ đóng kết nối
 * (không thể gửi văn bản thường qua kênh TLS).
 * start() KHÔNG ném exception khi bind lỗi: ghi log và isRunning() = false.
 */
public final class SmtpListener {

    public interface SessionHandler {
        void handle(SmtpConnection c) throws Exception;
    }

    /** Tham số vận hành; mặc định lấy từ ServerConfig, test truyền giá trị riêng. */
    public static final class Options {
        public final int poolSize;
        public final int queueSize;
        public final int backlog;
        public final int maxConnPerIp;      // 0 = không giới hạn
        public final AbuseGuard.Engine guard; // null = không dùng AbuseGuard

        public Options(int poolSize, int queueSize, int backlog, int maxConnPerIp, AbuseGuard.Engine guard) {
            this.poolSize = Math.max(1, poolSize);
            this.queueSize = Math.max(1, queueSize);
            this.backlog = Math.max(1, backlog);
            this.maxConnPerIp = Math.max(0, maxConnPerIp);
            this.guard = guard;
        }

        public static Options fromConfig() {
            return new Options(ServerConfig.SMTP_POOL_SIZE, ServerConfig.SMTP_QUEUE_SIZE,
                    ServerConfig.SOCKET_BACKLOG, ServerConfig.SMTP_MAX_CONN_PER_IP, AbuseGuard.defaultEngine());
        }
    }

    private static final String MSG_BUSY = "421 4.3.2 Service busy, try again later";
    private static final String MSG_TOO_MANY = "421 4.7.0 Too many connections from your IP";
    private static final String MSG_BLOCKED = "421 4.7.0 Connection refused, try again later";

    private final String name;
    private final int port;
    private final boolean implicitTls;
    private final Supplier<SessionHandler> factory;
    private final Options opt;

    private volatile boolean running = false;
    private ServerSocket server;
    private ThreadPoolExecutor pool;
    private Thread acceptThread;

    private final ConcurrentHashMap<String, Integer> perIp = new ConcurrentHashMap<>();
    private final Set<Socket> active = ConcurrentHashMap.newKeySet();

    public SmtpListener(String name, int port, boolean implicitTls, Supplier<SessionHandler> factory) {
        this(name, port, implicitTls, factory, Options.fromConfig());
    }

    public SmtpListener(String name, int port, boolean implicitTls, Supplier<SessionHandler> factory, Options options) {
        this.name = name;
        this.port = port;
        this.implicitTls = implicitTls;
        this.factory = factory;
        this.opt = options;
    }

    public synchronized void start() {
        if (running) return;
        ServerSocket ss = null;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port), opt.backlog);
        } catch (IOException e) {
            System.err.println("[SMTP:" + name + "] KHÔNG mở được cổng " + port + ": " + e.getMessage());
            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException ignored) {}
            }
            return;
        }
        this.server = ss;

        final AtomicInteger seq = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "smtp-" + name + "-w" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        pool = new ThreadPoolExecutor(opt.poolSize, opt.poolSize, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(opt.queueSize), tf, new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);

        running = true;
        acceptThread = new Thread(this::acceptLoop, "smtp-" + name + "-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        System.out.println("=== SMTP listener '" + name + "' chạy trên cổng " + ss.getLocalPort()
                + (implicitTls ? " (TLS ngay)" : "") + " | pool=" + opt.poolSize + " queue=" + opt.queueSize + " ===");
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {}
        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                for (Socket s : active) {
                    try {
                        s.close();
                    } catch (IOException ignored) {}
                }
                pool.shutdownNow();
                pool.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** Cổng thực sự đang lắng nghe (hữu ích khi port = 0); -1 nếu chưa chạy. */
    public int getLocalPort() {
        ServerSocket ss = server;
        return (ss != null && running) ? ss.getLocalPort() : -1;
    }

    // ------------------------------------------------------------------ accept

    private void acceptLoop() {
        while (running) {
            Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (!running) break;
                System.err.println("[SMTP:" + name + "] accept lỗi: " + e.getMessage());
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            try {
                dispatch(s);
            } catch (Throwable t) {
                System.err.println("[SMTP:" + name + "] dispatch lỗi: " + t);
                closeQuietly(s);
            }
        }
    }

    private void dispatch(Socket s) {
        String ip = ipOf(s);

        if (!acquireIp(ip)) {
            reject(s, MSG_TOO_MANY);
            return;
        }
        if (opt.guard != null && !opt.guard.allowConnect(ip)) {
            releaseIp(ip);
            reject(s, MSG_BLOCKED);
            return;
        }
        active.add(s);
        try {
            pool.execute(new Session(s, ip));
        } catch (RejectedExecutionException e) {
            active.remove(s);
            releaseAll(ip);
            reject(s, MSG_BUSY);
        }
    }

    private static String ipOf(Socket s) {
        if (s.getInetAddress() == null) return "unknown";
        String h = s.getInetAddress().getHostAddress();
        int pct = h.indexOf('%');
        return pct >= 0 ? h.substring(0, pct) : h;
    }

    private boolean acquireIp(String ip) {
        if (opt.maxConnPerIp <= 0) return true;
        final boolean[] ok = {false};
        perIp.compute(ip, (k, v) -> {
            int c = (v == null) ? 0 : v;
            if (c >= opt.maxConnPerIp) return v;
            ok[0] = true;
            return c + 1;
        });
        return ok[0];
    }

    private void releaseIp(String ip) {
        if (opt.maxConnPerIp <= 0) return;
        perIp.computeIfPresent(ip, (k, v) -> v <= 1 ? null : v - 1);
    }

    private void releaseAll(String ip) {
        releaseIp(ip);
        if (opt.guard != null) opt.guard.release(ip);
    }

    private void reject(Socket s, String line) {
        try {
            if (!implicitTls) {
                s.setSoTimeout(2000);
                OutputStream o = s.getOutputStream();
                o.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
                o.flush();
            }
        } catch (IOException ignored) {
            // client đã đi: không sao
        } finally {
            closeQuietly(s);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {}
    }

    // ------------------------------------------------------------------ phiên

    private final class Session implements Runnable {
        private final Socket socket;
        private final String ip;

        Session(Socket socket, String ip) {
            this.socket = socket;
            this.ip = ip;
        }

        @Override
        public void run() {
            SmtpConnection conn = null;
            try {
                conn = new SmtpConnection(socket, implicitTls);
                SessionHandler handler = factory.get();
                handler.handle(conn);
            } catch (SocketTimeoutException e) {
                if (conn != null) {
                    try {
                        conn.reply("421 4.4.2 " + ServerConfig.MAIL_HOSTNAME + " Timeout, closing connection");
                    } catch (Exception ignored) {}
                }
            } catch (IOException e) {
                // Client ngắt, TLS lỗi, dòng quá dài... là chuyện bình thường, không log.
            } catch (Throwable t) {
                System.err.println("[SMTP:" + name + "] Lỗi handler (client " + ip + "): " + t);
                t.printStackTrace();
            } finally {
                if (conn != null) {
                    conn.close();
                } else {
                    closeQuietly(socket);
                }
                active.remove(socket);
                releaseAll(ip);
            }
        }
    }
}