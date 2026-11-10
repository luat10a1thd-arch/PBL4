<<<<<<< HEAD
package com.pbl4.mailserver.pop3;

import com.pbl4.mailserver.core.LoginThrottle;
import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.SecurityUtils;
import com.pbl4.mailserver.core.SslContextFactory;
import com.pbl4.mailserver.core.UserStore;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Pop3Handler implements Runnable {

    private Socket clientSocket;
    private static final int SOCKET_TIMEOUT_MS = 30000;

    public Pop3Handler(Socket socket) {
        this.clientSocket = socket;
    }

    @Override
    public void run() {
        try {
            clientSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
        } catch (Exception ignored) {}

        String clientIp = clientSocket.getInetAddress().getHostAddress();

        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true);

            out.println("+OK PBL4 POP3 Server Ready");

            String line;
            String pendingUser = null;
            String currentUser = null;
            boolean isAuthenticated = false;
            boolean isTlsActive = false;
            List<MailStorageEngine.StoredMail> emails = new ArrayList<>();
            Set<Integer> deletedIndices = new HashSet<>();

            while ((line = in.readLine()) != null) {
                String[] tokens = line.split(" ", 2);
                String command = tokens[0].toUpperCase();
                String argument = tokens.length > 1 ? tokens[1].trim() : "";

                if ("STLS".equals(command)) {
                    if (isTlsActive) {
                        out.println("-ERR TLS already active");
                        continue;
                    }

                    SSLContext sslContext = SslContextFactory.createSSLContext();
                    if (sslContext == null) {
                        out.println("-ERR STLS not available");
                        continue;
                    }

                    out.println("+OK Begin TLS negotiation now");

                    SSLSocketFactory sf = sslContext.getSocketFactory();
                    SSLSocket sslSocket = (SSLSocket) sf.createSocket(
                            clientSocket,
                            clientSocket.getInetAddress().getHostAddress(),
                            clientSocket.getPort(),
                            true
                    );
                    sslSocket.setUseClientMode(false);
                    sslSocket.startHandshake();

                    clientSocket = sslSocket;
                    in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
                    out = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true);
                    
                    isTlsActive = true;
                    pendingUser = null;
                }
                else if ("CAPA".equals(command)) {
                    out.println("+OK Capability list follows");
                    out.println("USER");
                    out.println("UIDL");
                    out.println("TOP");
                    if (!isTlsActive) {
                        out.println("STLS");
                    }
                    out.println(".");
                }
                else if ("USER".equals(command)) {
                    if (isAuthenticated) {
                        out.println("-ERR Already authenticated");
                        continue;
                    }
                    pendingUser = argument;
                    out.println("+OK User accepted");
                } 
                else if ("PASS".equals(command)) {
                    if (isAuthenticated) {
                        out.println("-ERR Already authenticated");
                        continue;
                    }
                    if (pendingUser == null) {
                        out.println("-ERR USER command required first");
                        continue;
                    }

                    boolean isSentFolder = pendingUser.endsWith("_sent");
                    String authCheckUser = isSentFolder
                            ? pendingUser.substring(0, pendingUser.length() - 5)
                            : pendingUser;

                    if (LoginThrottle.isThrottled(clientIp, authCheckUser)) {
                        out.println("-ERR Too many failed attempts, try again later");
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
                        emails = MailStorageEngine.listMails(currentUser);
                        deletedIndices.clear();
                        out.println("+OK Logged in successfully");
                    } else {
                        LoginThrottle.recordFailedAttempt(clientIp, authCheckUser);
                        out.println("-ERR Invalid username or password");
                    }
                } 
                else if ("STAT".equals(command)) {
                    if (!isAuthenticated) {
                        out.println("-ERR Please authenticate first");
                        continue;
                    }
                    int activeCount = 0;
                    long totalBytes = 0;
                    for (int i = 0; i < emails.size(); i++) {
                        if (!deletedIndices.contains(i + 1)) {
                            activeCount++;
                            String content = emails.get(i).content;
                            totalBytes += (content != null) ? content.getBytes(StandardCharsets.UTF_8).length : 0;
                        }
                    }
                    out.println("+OK " + activeCount + " " + totalBytes);
                } 
                else if ("LIST".equals(command)) {
                    if (!isAuthenticated) {
                        out.println("-ERR Please authenticate first");
                        continue;
                    }
                    if (!argument.isEmpty()) {
                        try {
                            int idx = Integer.parseInt(argument);
                            if (idx < 1 || idx > emails.size() || deletedIndices.contains(idx)) {
                                out.println("-ERR No such message");
                            } else {
                                String content = emails.get(idx - 1).content;
                                int size = (content != null) ? content.getBytes(StandardCharsets.UTF_8).length : 0;
                                out.println("+OK " + idx + " " + size);
                            }
                        } catch (NumberFormatException e) {
                            out.println("-ERR Invalid message number");
                        }
                    } else {
                        int activeCount = emails.size() - deletedIndices.size();
                        out.println("+OK " + activeCount + " messages");
                        for (int i = 0; i < emails.size(); i++) {
                            if (!deletedIndices.contains(i + 1)) {
                                String content = emails.get(i).content;
                                int size = (content != null) ? content.getBytes(StandardCharsets.UTF_8).length : 0;
                                out.println((i + 1) + " " + size);
                            }
                        }
                        out.println(".");
                    }
                } 
                else if ("UIDL".equals(command)) {
                    if (!isAuthenticated) {
                        out.println("-ERR Please authenticate first");
                        continue;
                    }
                    if (!argument.isEmpty()) {
                        try {
                            int idx = Integer.parseInt(argument);
                            if (idx < 1 || idx > emails.size() || deletedIndices.contains(idx)) {
                                out.println("-ERR No such message");
                            } else {
                                out.println("+OK " + idx + " " + emails.get(idx - 1).id);
                            }
                        } catch (NumberFormatException e) {
                            out.println("-ERR Invalid message number");
                        }
                    } else {
                        out.println("+OK Unique-ID listing follows");
                        for (int i = 0; i < emails.size(); i++) {
                            if (!deletedIndices.contains(i + 1)) {
                                out.println((i + 1) + " " + emails.get(i).id);
                            }
                        }
                        out.println(".");
                    }
                }
                else if ("TOP".equals(command)) {
                    if (!isAuthenticated) {
                        out.println("-ERR Please authenticate first");
                        continue;
                    }
                    String[] parts = argument.split("\\s+");
                    if (parts.length < 2) {
                        out.println("-ERR Usage: TOP msg_num lines");
                        continue;
                    }
                    try {
                        int idx = Integer.parseInt(parts[0]) - 1;
                        int linesToReturn = Integer.parseInt(parts[1]);

                        if (idx >= 0 && idx < emails.size() && !deletedIndices.contains(idx + 1)) {
                            String mailContent = emails.get(idx).content;
                            if (mailContent == null) {
                                out.println("-ERR Message decryption failed");
                                continue;
                            }
                            out.println("+OK Top lines follow");
                            String[] lines = mailContent.split("\r?\n");
                            boolean inBody = false;
                            int bodyLineCount = 0;

                            for (String l : lines) {
                                if (!inBody) {
                                    out.println(l.startsWith(".") ? "." + l : l);
                                    if (l.trim().isEmpty()) {
                                        inBody = true;
                                    }
                                } else {
                                    if (bodyLineCount >= linesToReturn) break;
                                    out.println(l.startsWith(".") ? "." + l : l);
                                    bodyLineCount++;
                                }
                            }
                            out.println(".");
                        } else {
                            out.println("-ERR Message not found");
                        }
                    } catch (NumberFormatException e) {
                        out.println("-ERR Invalid parameters");
                    }
                }
                else if ("RETR".equals(command)) {
                    if (!isAuthenticated) {
                        out.println("-ERR Please authenticate first");
                        continue;
                    }
                    try {
                        int index = Integer.parseInt(argument) - 1;
                        if (index >= 0 && index < emails.size() && !deletedIndices.contains(index + 1)) {
                            String mailContent = emails.get(index).content;
                            if (mailContent == null) {
                                out.println("-ERR Message decryption failed");
                                continue;
                            }
                            int size = mailContent.getBytes(StandardCharsets.UTF_8).length;
                            out.println("+OK " + size + " octets");
                            String message = mailContent.replace("\r\n", "\n").replace('\r', '\n');
                            String[] messageLines = message.split("\n", -1);
                            for (String messageLine : messageLines) {
                                out.println(messageLine.startsWith(".") ? "." + messageLine : messageLine);
                            }
                            out.println(".");
                        } else {
                            out.println("-ERR Message not found or marked for deletion");
                        }
                    } catch (NumberFormatException e) {
                        out.println("-ERR Invalid message number");
                    }
                } 
                else if ("DELE".equals(command)) {
                    if (!isAuthenticated) {
                        out.println("-ERR Please authenticate first");
                        continue;
                    }
                    try {
                        int idx = Integer.parseInt(argument);
                        if (idx < 1 || idx > emails.size() || deletedIndices.contains(idx)) {
                            out.println("-ERR Message " + idx + " already deleted or does not exist");
                        } else {
                            deletedIndices.add(idx);
                            out.println("+OK Message " + idx + " marked for deletion");
                        }
                    } catch (NumberFormatException e) {
                        out.println("-ERR Invalid message number");
                    }
                }
                else if ("RSET".equals(command)) {
                    if (!isAuthenticated) {
                        out.println("-ERR Please authenticate first");
                        continue;
                    }
                    deletedIndices.clear();
                    out.println("+OK Reset state, all marked deletions cleared");
                }
                else if ("NOOP".equals(command)) {
                    out.println("+OK");
                }
                else if ("QUIT".equals(command)) {
                    if (isAuthenticated && currentUser != null && !deletedIndices.isEmpty()) {
                        List<String> idsToDelete = new ArrayList<>();
                        for (int idx : deletedIndices) {
                            if (idx >= 1 && idx <= emails.size()) {
                                idsToDelete.add(emails.get(idx - 1).id);
                            }
                        }
                        MailStorageEngine.deleteMailsByIds(currentUser, idsToDelete);
                    }
                    out.println("+OK Logging out");
                    break;
                } 
                else {
                    out.println("-ERR Unknown command");
                }
            }
        } catch (SocketTimeoutException e) {
            System.err.println("[POP3] Timeout: " + clientSocket.getInetAddress());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (Exception ignored) {}
        }
    }
}
=======
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

    private static final byte[] CRLF = {'\r', '\n'};

    interface Backend {
        boolean isThrottled(String ip, String user);

        void recordFailure(String ip, String user);

        void resetFailures(String ip, String user);

        String authenticate(String user, String password);

        List<MailStorageEngine.MailMeta> listMetas(String mailbox);

        MailStorageEngine.StoredMail getMail(String mailbox, String id);

        void deleteMails(String mailbox, List<String> ids);
    }

    private static final class DefaultBackend implements Backend {
        @Override
        public boolean isThrottled(String ip, String user) {
            return LoginThrottle.isThrottled(ip, user);
        }

        @Override
        public void recordFailure(String ip, String user) {
            LoginThrottle.recordFailedAttempt(ip, user);
        }

        @Override
        public void resetFailures(String ip, String user) {
            LoginThrottle.resetAttempts(ip, user);
        }

        @Override
        public String authenticate(String user, String password) {
            String storedHash = UserStore.findHash(user);
            boolean isLocked = "LOCKED".equalsIgnoreCase(UserStore.getStatus(user));
            if (storedHash != null && !isLocked && SecurityUtils.checkPassword(password, storedHash)) {
                return UserStore.canonical(user);
            }
            return null;
        }

        @Override
        public List<MailStorageEngine.MailMeta> listMetas(String mailbox) {
            return MailStorageEngine.listMailMetas(mailbox);
        }

        @Override
        public MailStorageEngine.StoredMail getMail(String mailbox, String id) {
            return MailStorageEngine.getSingleMail(mailbox, id);
        }

        @Override
        public void deleteMails(String mailbox, List<String> ids) {
            MailStorageEngine.deleteMailsByIds(mailbox, ids);
        }
    }

    private static final Backend DEFAULT_BACKEND = new DefaultBackend();

    private Socket clientSocket;
    private final Backend backend;

    public Pop3Handler(Socket socket) {
        this(socket, DEFAULT_BACKEND);
    }

    Pop3Handler(Socket socket, Backend backend) {
        this.clientSocket = socket;
        this.backend = backend;
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

                    if (backend.isThrottled(clientIp, authCheckUser)) {
                        send(out, "-ERR Too many failed attempts, try again later");
                        continue;
                    }

                    String canonicalUser = backend.authenticate(authCheckUser, argument);

                    if (canonicalUser != null) {
                        backend.resetFailures(clientIp, authCheckUser);
                        isAuthenticated = true;

                        currentUser = isSentFolder ? canonicalUser + "_sent" : canonicalUser;

                        pendingUser = null;

                        List<MailStorageEngine.MailMeta> loaded = backend.listMetas(currentUser);
                        metas = (loaded != null) ? loaded : new ArrayList<>();
                        sizes = new long[metas.size()];
                        for (int i = 0; i < sizes.length; i++) {
                            sizes[i] = Math.max(0L, metas.get(i).sizeBytes);
                        }
                        deletedIndices.clear();
                        send(out, "+OK Logged in successfully");
                    } else {
                        backend.recordFailure(clientIp, authCheckUser);
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

                            MailStorageEngine.StoredMail mail = backend.getMail(currentUser, metas.get(idx).id);
                            if (mail == null) {
                                send(out, "-ERR Message not available");
                                continue;
                            }
                            byte[] raw = mail.rawBytes();
                            if (raw == null) {
                                send(out, "-ERR Message decryption failed");
                                continue;
                            }
                            writeLine(out, "+OK Top lines follow");
                            Pop3Wire.writeTop(out, raw, linesToReturn);
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

                            MailStorageEngine.StoredMail mail = backend.getMail(currentUser, metas.get(index).id);
                            if (mail == null) {
                                send(out, "-ERR Message not available");
                                continue;
                            }
                            byte[] raw = mail.rawBytes();
                            if (raw == null) {
                                send(out, "-ERR Message decryption failed");
                                continue;
                            }

                            long octets = Pop3Wire.octetCount(raw);
                            if (octets != sizes[index]) {
                                LOG.log(Level.FINE, "[POP3] Kích thước meta lệch RETR: id={0} meta={1} thực={2}",
                                        new Object[]{metas.get(index).id, sizes[index], octets});
                                sizes[index] = octets;
                            }

                            writeLine(out, "+OK " + octets + " octets");
                            Pop3Wire.writeMessage(out, raw);
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
                        backend.deleteMails(currentUser, idsToDelete);
                    }
                    send(out, "+OK Logging out");
                    break;
                }
                else {
                    send(out, "-ERR Unknown command");
                }
            }
        } catch (LimitedLineReader.LineTooLongException e) {

            try {
                if (out != null) send(out, "-ERR Line too long");
            } catch (IOException ignored) {}
        } catch (SocketTimeoutException e) {
            System.err.println("[POP3] Timeout: " + clientSocket.getInetAddress());
        } catch (IOException e) {

            System.err.println("[POP3] Connection error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (Exception ignored) {}
        }
    }

    private LimitedLineReader newReader(Socket socket, long sessionDeadlineNanos) throws IOException {
        LimitedLineReader reader = new LimitedLineReader(socket.getInputStream(), ServerConfig.MAX_COMMAND_LINE_BYTES);
        reader.setDeadlineNanos(sessionDeadlineNanos);
        return reader;
    }

    private OutputStream newWriter(Socket socket) throws IOException {
        return new BufferedOutputStream(socket.getOutputStream(), 8192);
    }

    private void applyReadTimeout(boolean authenticated, long sessionDeadlineNanos) throws IOException {
        long phaseMs = authenticated ? ServerConfig.POSTAUTH_TIMEOUT_MS : ServerConfig.PREAUTH_TIMEOUT_MS;
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(sessionDeadlineNanos - System.nanoTime());
        if (remainingMs <= 0) {
            throw new SocketTimeoutException("Session time limit exceeded");
        }
        clientSocket.setSoTimeout((int) Math.max(1L, Math.min(phaseMs, remainingMs)));
    }

    private void send(OutputStream out, String line) throws IOException {
        writeLine(out, line);
        out.flush();
    }

    private void writeLine(OutputStream out, String line) throws IOException {
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.write(CRLF);
    }
}
>>>>>>> stash
