package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.core.MailStorageEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * API Handler xử lý Gửi thư, Tải hòm thư đến và Thư đã gửi cho Web Client
 */
public class MailHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Cấu hình CORS
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();

        // 1. GỬI THƯ MỚI (POST /api/mails/send)
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && path.endsWith("/send")) {
            handleSendMail(exchange);
        } 
        // 2. TẢI HỘP THƯ ĐẾN (GET /api/mails/inbox?user=...)
        else if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) && path.endsWith("/inbox")) {
            handleGetInbox(exchange);
        } 
        // 3. TẢI THƯ ĐÃ GỬI (GET /api/mails/sent?user=...)
        else if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) && path.endsWith("/sent")) {
            handleGetSent(exchange);
        } else {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Endpoint không tồn tại!\"}");
        }
    }

    private void handleSendMail(HttpExchange exchange) throws IOException {
        InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
        BufferedReader br = new BufferedReader(isr);
        StringBuilder formData = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            formData.append(line);
        }

        String body = formData.toString();
        String from = getParamValue(body, "from");
        String to = getParamValue(body, "to");
        String subject = getParamValue(body, "subject");
        String content = getParamValue(body, "body");

        if (from == null || to == null || subject == null || content == null) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu thông tin gửi thư!\"}");
            return;
        }

        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        // Định dạng cấu trúc email thô trước khi mã hóa
        String rawEmailContent = "From: " + from + "\n" +
                                 "To: " + to + "\n" +
                                 "Subject: " + subject + "\n" +
                                 "Date: " + timestamp + "\n\n" +
                                 content;

        // Lưu vào hòm thư của NGƯỜI NHẬN (Mã hóa AES-256)
        boolean savedToRecipient = MailStorageEngine.saveEmail(to, rawEmailContent);
        
        // Lưu vào hòm thư của NGƯỜI GỬI (Thư đã gửi)
        boolean savedToSender = MailStorageEngine.saveEmail(from + "_sent", rawEmailContent);

        if (savedToRecipient && savedToSender) {
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Gửi thư thành công!\"}");
        } else {
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Lỗi khi lưu trữ email!\"}");
        }
    }

    private void handleGetInbox(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String username = getParamFromQuery(query, "user");

        if (username == null || username.isEmpty()) {
            sendResponse(exchange, 400, "[]");
            return;
        }

        List<String> rawMails = MailStorageEngine.readAllEmails(username);
        String jsonResult = convertMailsToJson(rawMails);
        sendResponse(exchange, 200, jsonResult);
    }

    private void handleGetSent(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String username = getParamFromQuery(query, "user");

        if (username == null || username.isEmpty()) {
            sendResponse(exchange, 400, "[]");
            return;
        }

        List<String> rawMails = MailStorageEngine.readAllEmails(username + "_sent");
        String jsonResult = convertMailsToJson(rawMails);
        sendResponse(exchange, 200, jsonResult);
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
                if (isBody) {
                    bodyBuilder.append(line).append("\\n");
                } else if (line.startsWith("From: ")) from = line.substring(6).trim();
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
        return input.replace("\"", "\\\"").replace("\r", "");
    }

    private String getParamValue(String body, String paramName) {
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=");
            if (kv.length == 2 && kv[0].equalsIgnoreCase(paramName)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String getParamFromQuery(String query, String paramName) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=");
            if (kv.length == 2 && kv[0].equalsIgnoreCase(paramName)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
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