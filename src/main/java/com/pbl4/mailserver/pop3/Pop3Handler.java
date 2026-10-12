package com.pbl4.mailserver.pop3;

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
    private static final int SOCKET_TIMEOUT_MS = 30000; // --- MỤC 8.5: Timeout 30s ---

    public Pop3Handler(Socket socket) {
        this.clientSocket = socket;
    }

    @Override
    public void run() {
        try {
            // --- MỤC 8.5: Cấu hình Read Timeout phòng chống treo kết nối ---
            clientSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
        } catch (Exception ignored) {}

        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true)
        ) {
            out.println("+OK PBL4 POP3 Server Ready");

            String line;
            String currentUser = null;
            boolean isAuthenticated = false;
            List<String> emails = new ArrayList<>();
            Set<Integer> deletedIndices = new HashSet<>();

            while ((line = in.readLine()) != null) {
                String[] tokens = line.split(" ", 2);
                String command = tokens[0].toUpperCase();
                String argument = tokens.length > 1 ? tokens[1].trim() : "";

                if ("USER".equals(command)) {
                    currentUser = argument;
                    out.println("+OK User accepted");
                } 
                else if ("PASS".equals(command)) {
                    if (currentUser == null) {
                        out.println("-ERR USER command required first");
                        continue;
                    }

                    String authCheckUser = currentUser.endsWith("_sent")
                            ? currentUser.substring(0, currentUser.length() - 5)
                            : currentUser;

                    String storedHash = UserStore.findHash(authCheckUser);
                    if (storedHash != null && SecurityUtils.checkPassword(argument, storedHash)) {
                        isAuthenticated = true;
                        emails = MailStorageEngine.readAllEmails(currentUser);
                        deletedIndices.clear();
                        out.println("+OK Logged in successfully");
                    } else {
                        out.println("-ERR Invalid username or password");
                    }
                } 
                else if ("STAT".equals(command)) {
                    if (!isAuthenticated) {
                        out.println("-ERR Please authenticate first");
                        continue;
                    }
                    // FIX MỤC 8.3: Chuẩn định dạng STAT
                    int activeCount = 0;
                    long totalBytes = 0;
                    for (int i = 0; i < emails.size(); i++) {
                        if (!deletedIndices.contains(i + 1)) {
                            activeCount++;
                            totalBytes += emails.get(i).getBytes(StandardCharsets.UTF_8).length;
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
                                int size = emails.get(idx - 1).getBytes(StandardCharsets.UTF_8).length;
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
                                out.println((i + 1) + " " + emails.get(i).getBytes(StandardCharsets.UTF_8).length);
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
                            out.println("+OK Octets follows");
                            String message = emails.get(index).replace("\r\n", "\n").replace('\r', '\n');
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
                // FIX MỤC 8.3: Bổ sung lệnh DELE
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
                // FIX MỤC 8.3: Bổ sung lệnh RSET
                else if ("RSET".equals(command)) {
                    if (!isAuthenticated) {
                        out.println("-ERR Please authenticate first");
                        continue;
                    }
                    deletedIndices.clear();
                    out.println("+OK Reset state, all marked deletions cleared");
                }
                // FIX MỤC 8.3: Bổ sung lệnh NOOP
                else if ("NOOP".equals(command)) {
                    out.println("+OK");
                }
                else if ("QUIT".equals(command)) {
                    // FIX MỤC 8.3: Xóa vĩnh viễn các file thư bị đánh dấu
                    if (isAuthenticated && !deletedIndices.isEmpty()) {
                        MailStorageEngine.deleteEmails(currentUser, deletedIndices);
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