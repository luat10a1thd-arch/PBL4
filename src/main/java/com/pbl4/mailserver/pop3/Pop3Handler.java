package com.pbl4.mailserver.pop3;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.LimitedLineReader;
import com.pbl4.mailserver.core.LoginThrottle;
import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.SecurityUtils;
import com.pbl4.mailserver.core.SslContextFactory;
import com.pbl4.mailserver.core.UserStore;

import javax.net.ssl.SSLSocket;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Pop3Handler implements Runnable {

    private static final Logger LOG = Logger.getLogger(Pop3Handler.class.getName());

    // Mọi dòng trả về POP3 phải kết thúc bằng CRLF tường minh (println dùng "\n" trên Linux)
    private static final byte[] CRLF = {'\r', '\n'};

    private Socket clientSocket;

    public Pop3Handler(Socket socket) {
        this.clientSocket = socket;
    }

    @Override
    public void run() {
        final long sessionDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ServerConfig.SESSION_MAX_MS);

        try {
            clientSocket.setSoTimeout(ServerConfig.PREAUTH_TIMEOUT_MS);
        } catch (Exception ignored) {}

        String clientIp = clientSocket.getInetAddress().getHostAddress();
        OutputStream out = null;

        try {
            LimitedLineReader in = newReader(clientSocket, sessionDeadline);
            out = newWriter(clientSocket);

            send(out, "+OK PBL4 POP3 Server Ready");

            String line;
            String pendingUser = null;
            String currentUser = null;
            boolean isAuthenticated = false;
            boolean isTlsActive = false;

            // PASS chỉ lấy metadata (id + kích thước), KHÔNG giải mã cả hòm thư.
            // Nội dung thư chỉ được giải mã lười, từng thư một, khi RETR/TOP.
            List<MailStorageEngine.MailMeta> metas = new ArrayList<>();
            long[] sizes = new long[0];
            Set<Integer> deletedIndices = new HashSet<>();

            while (true) {
                applyReadTimeout(isAuthenticated, sessionDeadline);
                line = in.readLine();
                if (line == null) break;

                String[] tokens = line.split(" ", 2);
                String command = tokens[0].toUpperCase();
                String argument = tokens.length > 1 ? tokens[1].trim() : "";

                if ("STLS".equals(command)) {
                    if (isAuthenticated) {
                        send(out, "-ERR Command not permitted when authenticated");
                        continue;
                    }
                    if (isTlsActive) {
                        send(out, "-ERR TLS already active");
                        continue;
                    }

                    if (SslContextFactory.createSSLContext() == null) {
                        send(out, "-ERR STLS not available");
                        continue;
                    }

                    // Còn dữ liệu thô đã đọc sẵn = client chen lệnh trước khi bắt tay TLS -> đóng
                    if (in.hasBufferedData()) {
                        send(out, "-ERR Unexpected data before TLS negotiation");
                        break;
                    }

                    send(out, "+OK Begin TLS negotiation now");

                    applyReadTimeout(false, sessionDeadline);
                    SSLSocket sslSocket = SslContextFactory.wrapServerSocket(clientSocket);
                    sslSocket.startHandshake();

                    clientSocket = sslSocket;
                    in = newReader(clientSocket, sessionDeadline);
                    out = newWriter(clientSocket);

                    isTlsActive = true;
                    pendingUser = null;
                }
                else if ("CAPA".equals(command)) {
                    writeLine(out, "+OK Capability list follows");
                    writeLine(out, "USER");
                    writeLine(out, "UIDL");
                    writeLine(out, "TOP");
                    if (!isTlsActive) {
                        writeLine(out, "STLS");
                    }
                    writeLine(out, ".");
                    out.flush();
                }
                else if ("USER".equals(command)) {
                    if (isAuthenticated) {
                        send(out, "-ERR Already authenticated");
                        continue;
                    }
                    pendingUser = argument;
                    send(out, "+OK User accepted");
                } 
                else if ("PASS".equals(command)) {
                    if (isAuthenticated) {
                        send(out, "-ERR Already authenticated");
                        continue;
                    }
                    if (pendingUser == null) {
                        send(out, "-ERR USER command required first");
                        continue;
                    }

                    boolean isSentFolder = pendingUser.endsWith("_sent");
                    String authCheckUser = isSentFolder
                            ? pendingUser.substring(0, pendingUser.length() - 5)
                            : pendingUser;

                    if (LoginThrottle.isThrottled(clientIp, authCheckUser)) {
                        send(out, "-ERR Too many failed attempts, try again later");
                        continue;
                    }

                    String storedHash = UserStore.findHash(authCheckUser);
                    boolean isLocked = "LOCKED".equalsIgnoreCase(UserStore.getStatus(authCheckUser));

                    if (storedHash != null && !isLocked && SecurityUtils.checkPassword(argument, storedHash)) {
                        LoginThrottle.resetAttempts(clientIp, authCheckUser);
                        isAuthenticated = true;
                        
                        String canonicalUser = UserStore.canonical(authCheckUser);
                        currentUser = isSentFolder ? canonicalUser + "_sent" : canonicalUser;
                        
                        pendingUser = null;

                        // Chỉ liệt kê metadata, không giải mã nội dung
                        List<MailStorageEngine.MailMeta> loaded = MailStorageEngine.listMailMetas(currentUser);
                        metas = (loaded != null) ? loaded : new ArrayList<>();
                        sizes = new long[metas.size()];
                        for (int i = 0; i < sizes.length; i++) {
                            sizes[i] = Math.max(0L, metas.get(i).sizeBytes);
                        }
                        deletedIndices.clear();
                        send(out, "+OK Logged in successfully");
                    } else {
                        LoginThrottle.recordFailedAttempt(clientIp, authCheckUser);
                        send(out, "-ERR Invalid username or password");
                    }
                } 
                else if ("STAT".equals(command)) {
                    if (!isAuthenticated) {
                        send(out, "-ERR Please authenticate first");
                        continue;
                    }
                    int activeCount = 0;
                    long totalBytes = 0;
                    for (int i = 0; i < metas.size(); i++) {
                        if (!deletedIndices.contains(i + 1)) {
                            activeCount++;
                            totalBytes += sizes[i];
                        }
                    }
                    send(out, "+OK " + activeCount + " " + totalBytes);
                } 
                else if ("LIST".equals(command)) {
                    if (!isAuthenticated) {
                        send(out, "-ERR Please authenticate first");
                        continue;
                    }
                    if (!argument.isEmpty()) {
                        try {
                            int idx = Integer.parseInt(argument);
                            if (idx < 1 || idx > metas.size() || deletedIndices.contains(idx)) {
                                send(out, "-ERR No such message");
                            } else {
                                send(out, "+OK " + idx + " " + sizes[idx - 1]);
                            }
                        } catch (NumberFormatException e) {
                            send(out, "-ERR Invalid message number");
                        }
                    } else {
                        int activeCount = metas.size() - deletedIndices.size();
                        writeLine(out, "+OK " + activeCount + " messages");
                        for (int i = 0; i < metas.size(); i++) {
                            if (!deletedIndices.contains(i + 1)) {
                                writeLine(out, (i + 1) + " " + sizes[i]);
                            }
                        }
                        writeLine(out, ".");
                        out.flush();
                    }
                } 
                else if ("UIDL".equals(command)) {
                    if (!isAuthenticated) {
                        send(out, "-ERR Please authenticate first");
                        continue;
                    }
                    if (!argument.isEmpty()) {
                        try {
                            int idx = Integer.parseInt(argument);
                            if (idx < 1 || idx > metas.size() || deletedIndices.contains(idx)) {
                                send(out, "-ERR No such message");
                            } else {
                                send(out, "+OK " + idx + " " + metas.get(idx - 1).id);
                            }
                        } catch (NumberFormatException e) {
                            send(out, "-ERR Invalid message number");
                        }
                    } else {
                        writeLine(out, "+OK Unique-ID listing follows");
                        for (int i = 0; i < metas.size(); i++) {
                            if (!deletedIndices.contains(i + 1)) {
                                writeLine(out, (i + 1) + " " + metas.get(i).id);
                            }
                        }
                        writeLine(out, ".");
                        out.flush();
                    }
                }
                else if ("TOP".equals(command)) {
                    if (!isAuthenticated) {
                        send(out, "-ERR Please authenticate first");
                        continue;
                    }
                    String[] parts = argument.split("\\s+");
                    if (parts.length < 2) {
                        send(out, "-ERR Usage: TOP msg_num lines");
                        continue;
                    }
                    try {
                        int idx = Integer.parseInt(parts[0]) - 1;
                        int linesToReturn = Integer.parseInt(parts[1]);
                        if (linesToReturn < 0) {
                            send(out, "-ERR Invalid parameters");
                            continue;
                        }

                        if (idx >= 0 && idx < metas.size() && !deletedIndices.contains(idx + 1)) {
                            // Giải mã lười đúng một thư
                            MailStorageEngine.StoredMail mail = MailStorageEngine.getSingleMail(currentUser, metas.get(idx).id);
                            if (mail == null) {
                                send(out, "-ERR Message not available");
                                continue;
                            }
                            if (mail.content == null) {
                                send(out, "-ERR Message decryption failed");
                                continue;
                            }
                            writeLine(out, "+OK Top lines follow");

                            String message = toLf(mail.content);
                            boolean inBody = false;
                            int bodyLineCount = 0;
                            int start = 0;
                            int n = message.length();

                            while (start < n) {
                                int nl = message.indexOf('\n', start);
                                int end = (nl < 0) ? n : nl;
                                if (!inBody) {
                                    writeStuffedLine(out, message, start, end);
                                    if (message.substring(start, end).trim().isEmpty()) {
                                        inBody = true;
                                    }
                                } else {
                                    if (bodyLineCount >= linesToReturn) break;
                                    writeStuffedLine(out, message, start, end);
                                    bodyLineCount++;
                                }
                                start = (nl < 0) ? n : nl + 1;
                            }
                            writeLine(out, ".");
                            out.flush();
                        } else {
                            send(out, "-ERR Message not found");
                        }
                    } catch (NumberFormatException e) {
                        send(out, "-ERR Invalid parameters");
                    }
                }
                else if ("RETR".equals(command)) {
                    if (!isAuthenticated) {
                        send(out, "-ERR Please authenticate first");
                        continue;
                    }
                    try {
                        int index = Integer.parseInt(argument) - 1;
                        if (index >= 0 && index < metas.size() && !deletedIndices.contains(index + 1)) {
                            // Giải mã lười đúng một thư
                            MailStorageEngine.StoredMail mail = MailStorageEngine.getSingleMail(currentUser, metas.get(index).id);
                            if (mail == null) {
                                send(out, "-ERR Message not available");
                                continue;
                            }
                            if (mail.content == null) {
                                send(out, "-ERR Message decryption failed");
                                continue;
                            }

                            // Chuẩn hóa về LF để tách dòng, khi gửi mỗi dòng kết thúc bằng CRLF.
                            String message = toLf(mail.content);

                            // Lượt 1: đếm số octet THỰC SỰ sẽ gửi (nội dung dạng CRLF, chưa tính
                            // dấu '.' dot-stuffing và dòng kết thúc ".").
                            long octets = 0;
                            int n = message.length();
                            int start = 0;
                            while (start < n) {
                                int nl = message.indexOf('\n', start);
                                int end = (nl < 0) ? n : nl;
                                octets += message.substring(start, end).getBytes(StandardCharsets.UTF_8).length + 2;
                                start = (nl < 0) ? n : nl + 1;
                            }

                            // sizeBytes trong metadata có thể lệch (vd. thư lưu bằng LF, hoặc kích
                            // thước tính trên dữ liệu mã hóa). Ưu tiên số đúng cho RETR và cập nhật
                            // lại để LIST/STAT sau đó trong cùng phiên nhất quán.
                            if (octets != sizes[index]) {
                                LOG.log(Level.FINE, "[POP3] Kích thước meta lệch RETR: id={0} meta={1} thực={2}",
                                        new Object[]{metas.get(index).id, sizes[index], octets});
                                sizes[index] = octets;
                            }

                            writeLine(out, "+OK " + octets + " octets");

                            // Lượt 2: gửi từng dòng + CRLF, dot-stuffing; không thêm dòng trống thừa ở cuối
                            start = 0;
                            while (start < n) {
                                int nl = message.indexOf('\n', start);
                                int end = (nl < 0) ? n : nl;
                                writeStuffedLine(out, message, start, end);
                                start = (nl < 0) ? n : nl + 1;
                            }
                            writeLine(out, ".");
                            out.flush();
                        } else {
                            send(out, "-ERR Message not found or marked for deletion");
                        }
                    } catch (NumberFormatException e) {
                        send(out, "-ERR Invalid message number");
                    }
                } 
                else if ("DELE".equals(command)) {
                    if (!isAuthenticated) {
                        send(out, "-ERR Please authenticate first");
                        continue;
                    }
                    try {
                        int idx = Integer.parseInt(argument);
                        if (idx < 1 || idx > metas.size() || deletedIndices.contains(idx)) {
                            send(out, "-ERR Message " + idx + " already deleted or does not exist");
                        } else {
                            deletedIndices.add(idx);
                            send(out, "+OK Message " + idx + " marked for deletion");
                        }
                    } catch (NumberFormatException e) {
                        send(out, "-ERR Invalid message number");
                    }
                }
                else if ("RSET".equals(command)) {
                    if (!isAuthenticated) {
                        send(out, "-ERR Please authenticate first");
                        continue;
                    }
                    deletedIndices.clear();
                    send(out, "+OK Reset state, all marked deletions cleared");
                }
                else if ("NOOP".equals(command)) {
                    send(out, "+OK");
                }
                else if ("QUIT".equals(command)) {
                    if (isAuthenticated && currentUser != null && !deletedIndices.isEmpty()) {
                        List<String> idsToDelete = new ArrayList<>();
                        for (int idx : deletedIndices) {
                            if (idx >= 1 && idx <= metas.size()) {
                                idsToDelete.add(metas.get(idx - 1).id);
                            }
                        }
                        MailStorageEngine.deleteMailsByIds(currentUser, idsToDelete);
                    }
                    send(out, "+OK Logging out");
                    break;
                } 
                else {
                    send(out, "-ERR Unknown command");
                }
            }
        } catch (LimitedLineReader.LineTooLongException e) {
            // Dòng quá dài: báo lỗi rồi đóng kết nối
            try {
                if (out != null) send(out, "-ERR Line too long");
            } catch (IOException ignored) {}
        } catch (SocketTimeoutException e) {
            System.err.println("[POP3] Timeout: " + clientSocket.getInetAddress());
        } catch (IOException e) {
            // Client ngắt kết nối đột ngột: không cần in cả stack trace
            System.err.println("[POP3] Connection error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (Exception ignored) {}
        }
    }

    // ---- Hàm phụ ----

    private LimitedLineReader newReader(Socket socket, long sessionDeadlineNanos) throws IOException {
        LimitedLineReader reader = new LimitedLineReader(socket.getInputStream(), ServerConfig.MAX_COMMAND_LINE_BYTES);
        reader.setDeadlineNanos(sessionDeadlineNanos);
        return reader;
    }

    private OutputStream newWriter(Socket socket) throws IOException {
        return new BufferedOutputStream(socket.getOutputStream(), 8192);
    }

    /**
     * Đặt timeout đọc: ngắn trước khi xác thực, dài hơn sau xác thực, và không vượt quá
     * thời gian còn lại của phiên.
     */
    private void applyReadTimeout(boolean authenticated, long sessionDeadlineNanos) throws IOException {
        long phaseMs = authenticated ? ServerConfig.POSTAUTH_TIMEOUT_MS : ServerConfig.PREAUTH_TIMEOUT_MS;
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(sessionDeadlineNanos - System.nanoTime());
        if (remainingMs <= 0) {
            throw new SocketTimeoutException("Session time limit exceeded");
        }
        clientSocket.setSoTimeout((int) Math.max(1L, Math.min(phaseMs, remainingMs)));
    }

    /** Gửi một dòng phản hồi đơn (kết thúc CRLF) và flush ngay. */
    private void send(OutputStream out, String line) throws IOException {
        writeLine(out, line);
        out.flush();
    }

    /** Ghi một dòng kết thúc CRLF vào bộ đệm (chưa flush) - dùng cho phản hồi nhiều dòng. */
    private void writeLine(OutputStream out, String line) throws IOException {
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.write(CRLF);
    }

    /** Ghi dòng message[start,end) + CRLF, thêm '.' đầu dòng nếu dòng bắt đầu bằng '.' (dot-stuffing). */
    private void writeStuffedLine(OutputStream out, String message, int start, int end) throws IOException {
        if (start < end && message.charAt(start) == '.') {
            out.write('.');
        }
        out.write(message.substring(start, end).getBytes(StandardCharsets.UTF_8));
        out.write(CRLF);
    }

    /** Chuẩn hóa mọi kiểu xuống dòng (CRLF, CR đơn) về LF. */
    private static String toLf(String content) {
        if (content.indexOf('\r') < 0) return content;
        return content.replace("\r\n", "\n").replace('\r', '\n');
    }
}
