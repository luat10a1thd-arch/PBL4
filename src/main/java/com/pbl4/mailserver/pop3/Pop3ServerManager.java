package com.pbl4.mailserver.pop3;

import com.pbl4.mailserver.config.ServerConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Pop3ServerManager {

    private static final int POP3_PORT = ServerConfig.POP3_PORT;

    private static final AtomicInteger workerSeq = new AtomicInteger(0);

    // Pool có giới hạn: core = max, hàng đợi nhỏ có giới hạn. Đầy thì từ chối bằng "-ERR Server busy"
    // (thay cho newFixedThreadPool(20) với hàng đợi vô hạn).
    private static final ThreadPoolExecutor threadPool = createPool();

    // Số kết nối đang mở theo IP (tự xóa khi về 0)
    private static final ConcurrentHashMap<String, Integer> connectionsPerIp = new ConcurrentHashMap<>();

    // Socket đang mở (kể cả đang chờ trong hàng đợi) để đóng khi dừng server
    private static final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();

    private static final AtomicLong rejectedCount = new AtomicLong(0);
    private static volatile long lastRejectLogMs = 0L;

    private static volatile ServerSocket serverSocket;
    private static volatile boolean isRunning = true;

    private static ThreadPoolExecutor createPool() {
        int size = ServerConfig.POP3_POOL_SIZE;
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                size, size,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(ServerConfig.POP3_QUEUE_SIZE),
                r -> {
                    Thread t = new Thread(r, "pop3-worker-" + workerSeq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
        );
        // Worker rảnh quá 60s thì thoát, không giữ sẵn hàng trăm thread khi server vắng
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    public static void startServer() {
        Thread acceptor = new Thread(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(POP3_PORT), ServerConfig.SOCKET_BACKLOG);
                System.out.println("=== POP3 Server running on port: " + POP3_PORT + " ===");

                while (isRunning && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        dispatch(clientSocket);
                    } catch (IOException e) {
                        if (!isRunning || serverSocket.isClosed()) break;
                        // Lỗi accept tạm thời (vd. hết file descriptor): nghỉ ngắn, tránh vòng lặp quay vòng
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } catch (RuntimeException e) {
                        System.err.println("[POP3] Accept loop error: " + e);
                    }
                }
            } catch (IOException e) {
                System.err.println("POP3 Server error: " + e.getMessage());
            } finally {
                stopServer();
            }
        }, "pop3-acceptor");
        acceptor.start();
    }

    /** Kiểm tra giới hạn theo IP rồi giao cho pool; quá tải thì từ chối lịch sự và đóng socket. */
    private static void dispatch(Socket client) {
        String ip = client.getInetAddress().getHostAddress();

        if (!tryAcquireIp(ip)) {
            noteRejected("too many connections from " + ip);
            reject(client, "-ERR Too many connections from your IP, try again later");
            return;
        }

        try {
            client.setTcpNoDelay(true);
        } catch (IOException ignored) {}

        activeSockets.add(client);
        try {
            threadPool.execute(() -> {
                try {
                    new Pop3Handler(client).run();
                } finally {
                    activeSockets.remove(client);
                    releaseIp(ip);
                    try {
                        client.close();
                    } catch (IOException ignored) {}
                }
            });
        } catch (RejectedExecutionException e) {
            activeSockets.remove(client);
            releaseIp(ip);
            noteRejected("pool full");
            reject(client, "-ERR Server busy, try again later");
        }
    }

    private static boolean tryAcquireIp(String ip) {
        final int max = ServerConfig.POP3_MAX_CONN_PER_IP;
        if (max <= 0) return true;
        final boolean[] ok = {false};
        connectionsPerIp.compute(ip, (k, v) -> {
            int cur = (v == null) ? 0 : v;
            if (cur >= max) return v;
            ok[0] = true;
            return cur + 1;
        });
        return ok[0];
    }

    private static void releaseIp(String ip) {
        if (ServerConfig.POP3_MAX_CONN_PER_IP <= 0) return;
        connectionsPerIp.computeIfPresent(ip, (k, v) -> (v <= 1) ? null : v - 1);
    }

    private static void reject(Socket client, String message) {
        try {
            client.setSoTimeout(1000);
            OutputStream o = client.getOutputStream();
            o.write((message + "\r\n").getBytes(StandardCharsets.US_ASCII));
            o.flush();
        } catch (IOException ignored) {
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {}
        }
    }

    // Ghi log tối đa 1 lần / 10 giây để khi bị flood không làm đầy log
    private static void noteRejected(String reason) {
        long total = rejectedCount.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastRejectLogMs > 10_000L) {
            lastRejectLogMs = now;
            System.err.println("[POP3] Rejected connection (" + reason + "), tổng số bị từ chối: " + total);
        }
    }

    public static void stopServer() {
        isRunning = false;
        ServerSocket ss = serverSocket;
        if (ss != null && !ss.isClosed()) {
            try {
                ss.close();
            } catch (IOException ignored) {}
        }
        // Chỉ tắt pool POP3 của chính class này
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(3, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
                // Thread đang chặn trong socket.read không bị interrupt: phải đóng socket để chúng thoát
                for (Socket s : activeSockets) {
                    try {
                        s.close();
                    } catch (IOException ignored) {}
                }
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
