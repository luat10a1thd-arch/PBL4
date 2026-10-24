package com.pbl4.mailserver.webapi;

import com.sun.net.httpserver.HttpExchange;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SseManager {

    private static class SseClient {
        final HttpExchange exchange;
        final OutputStream os;

        SseClient(HttpExchange exchange, OutputStream os) {
            this.exchange = exchange;
            this.os = os;
        }
    }

    private static final ConcurrentHashMap<String, Set<SseClient>> userClients = new ConcurrentHashMap<>();

    public static void register(String username, HttpExchange exchange, OutputStream os) {
        if (username == null) return;
        userClients.computeIfAbsent(username.toLowerCase(), k -> ConcurrentHashMap.newKeySet()).add(new SseClient(exchange, os));
    }

    public static void unregister(String username, SseClient client) {
        if (username == null) return;
        Set<SseClient> clients = userClients.get(username.toLowerCase());
        if (clients != null) {
            clients.remove(client);
            try { client.exchange.close(); } catch (Exception ignored) {}
        }
    }

    // Bắn sự kiện realtime tới đúng hòm thư nhận được mail
    public static void notifyUser(String username, String mailSummaryJson) {
        if (username == null) return;
        Set<SseClient> clients = userClients.get(username.toLowerCase());
        if (clients == null || clients.isEmpty()) return;

        String sseData = "event: new_mail\ndata: " + mailSummaryJson + "\n\n";
        byte[] bytes = sseData.getBytes(StandardCharsets.UTF_8);

        for (SseClient client : clients) {
            try {
                client.os.write(bytes);
                client.os.flush();
            } catch (Exception e) {
                unregister(username, client);
            }
        }
    }
}