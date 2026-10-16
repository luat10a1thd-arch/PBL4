package com.pbl4.mailserver.pop3;

import com.pbl4.mailserver.core.LoginThrottle;
import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.SecurityUtils;
import com.pbl4.mailserver.core.UserStore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Pop3Handler implements Runnable {

    private final Socket clientSocket;
    private static final int SOCKET_TIMEOUT_MS = 30000;

    public Pop3Handler(Socket socket) {
        this.clientSocket = socket;
    }

    @Override
    public void run() {
        try {
            clientSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
        } catch (Exception ignored) {}

        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true)
        ) {
            out.println("+OK PBL4 POP3 Server Ready");

            String line;
            String pendingUser = null;
            String currentUser = null;
            boolean isAuthenticated = false;
            List<MailStorageEngine.StoredMail> emails = new ArrayList<>();
            Set<Integer> deletedIndices = new HashSet<>();

            while ((line = in.readLine()) != null) {
                String[] tokens = line.split(" ", 2);
                String command = tokens[0].toUpperCase();
                String argument = tokens.length > 1 ? tokens[1].trim() : "";

                if ("USER".equals(command)) {
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

                    String authCheckUser = pendingUser.endsWith("_sent")
                            ? pendingUser.substring(0, pendingUser.length() - 5)
                            : pendingUser;

                    if (LoginThrottle.isThrottled(authCheckUser)) {
                        out.println("-ERR Too many failed attempts, try again later");
                        continue;
                    }

                    String storedHash = UserStore.findHash(authCheckUser);
                    boolean isLocked = "LOCKED".equalsIgnoreCase(UserStore.getStatus(authCheckUser));

                    if (storedHash != null && !isLocked && SecurityUtils.checkPassword(argument, storedHash)) {
                        LoginThrottle.resetAttempts(authCheckUser);
                        isAuthenticated = true;
                        currentUser = pendingUser;
                        pendingUser = null;
                        emails = MailStorageEngine.listMails(currentUser);
                        deletedIndices.clear();
                        out.println("+OK Logged in successfully");
                    } else {
                        LoginThrottle.recordFailedAttempt(authCheckUser);
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
            System.err.println("[POP3] Ngắt kết nối do quá thời gian chờ (Timeout): " + clientSocket.getInetAddress());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (Exception ignored) {}
        }
    }
}