package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.client.POP3Client;
import com.pbl4.mailserver.client.SMTPClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MailHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        String token = (authHeader != null && authHeader.startsWith("Bearer "))
                ? authHeader.substring(7) : null;
        String authedUser = SessionManager.validate(token);

        if (authedUser == null) {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Chưa đăng nhập hoặc phiên hết hạn!\"}");
            return;
        }

        String path = exchange.getRequestURI().getPath();

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && path.endsWith("/send")) {
            handleSendMail(exchange, authedUser, token);
        } else if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) && path.endsWith("/inbox")) {
            handleGetInbox(exchange, authedUser, token);
        } else if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) && path.endsWith("/sent")) {
            handleGetSent(exchange, authedUser, token);
        } else {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Endpoint không tồn tại!\"}");
        }
    }

    private void handleSendMail(HttpExchange exchange, String from, String token) throws IOException {
        String body = readBody(exchange);
        String to = getParamValue(body, "to");
        String subject = getParamValue(body, "subject");
        String content = getParamValue(body, "body");

        if (to == null || subject == null || content == null) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu thông tin gửi thư!\"}");
            return;
        }

        String password = SessionManager.getPassword(token);
        boolean sent = SMTPClient.send(from, password, to, subject, content);

        if (sent) {
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Gửi thư thành công!\"}");
        } else {
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Gửi thư thất bại!\"}");
        }
    }

    private void handleGetInbox(HttpExchange exchange, String username, String token) throws IOException {
        String password = SessionManager.getPassword(token);
        List<String> rawMails = POP3Client.fetchAll(username, password);

        if (rawMails == null) {
            sendResponse(exchange, 500, "[]");
            return;
        }
        sendResponse(exchange, 200, convertMailsToJson(rawMails));
    }

    private void handleGetSent(HttpExchange exchange, String username, String token) throws IOException {
        String password = SessionManager.getPassword(token);
        List<String> rawMails = POP3Client.fetchAll(username + "_sent", password);

        if (rawMails == null) {
            sendResponse(exchange, 500, "[]");
            return;
        }
        sendResponse(exchange, 200, convertMailsToJson(rawMails));
    }

    private String convertMailsToJson(List<String> rawMails) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rawMails.size(); i++) {
            String mail = rawMails.get(i);
            String[] lines = mail.split("\n");

            String from = "", to = "", subject = "", date = "";
            StringBuilder bodyBuilder = new StringBuilder();
            boolean isBody = false;

            for (String line : lines) {
                if (isBody) bodyBuilder.append(line).append("\\n");
                else if (line.startsWith("From: ")) from = line.substring(6).trim();
                else if (line.startsWith("To: ")) to = line.substring(4).trim();
                else if (line.startsWith("Subject: ")) subject = line.substring(9).trim();
                else if (line.startsWith("Date: ")) date = line.substring(6).trim();
                else if (line.isEmpty()) isBody = true;
            }

            sb.append("{")
              .append("\"sender\":\"").append(escapeJson(from)).append("\",")
              .append("\"recipient\":\"").append(escapeJson(to)).append("\",")
              .append("\"subject\":\"").append(escapeJson(subject)).append("\",")
              .append("\"timestamp\":\"").append(escapeJson(date)).append("\",")
              .append("\"body\":\"").append(escapeJson(bodyBuilder.toString())).append("\"")
              .append("}");

            if (i < rawMails.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "");
    }

    private String readBody(HttpExchange exchange) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private String getParamValue(String body, String paramName) {
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=");
            if (kv.length == 2 && kv[0].equalsIgnoreCase(paramName)) {
                try {
                    // Đã bọc try-catch để xử lý UnsupportedEncodingException trên Java 8
                    return java.net.URLDecoder.decode(kv[1], "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String jsonResponse) throws IOException {
        byte[] bytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }
}