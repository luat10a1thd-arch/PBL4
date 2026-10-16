package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.core.MailService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
            handleSendMail(exchange, authedUser);
        } else if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) && path.endsWith("/inbox")) {
            handleGetInbox(exchange, authedUser);
        } else if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) && path.endsWith("/sent")) {
            handleGetSent(exchange, authedUser);
        } else {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Endpoint không tồn tại!\"}");
        }
    }

    private void handleSendMail(HttpExchange exchange, String from) throws IOException {
        String body = readBody(exchange);
        String rawTo = getParamValue(body, "to");
        String rawSubject = getParamValue(body, "subject");
        String content = getParamValue(body, "body");

        MailService.DeliveryResult result = MailService.deliver(from, rawTo, rawSubject, content);

        if (result.success) {
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"" + escapeJson(result.message) + "\"}");
        } else {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"" + escapeJson(result.message) + "\"}");
        }
    }

    private void handleGetInbox(HttpExchange exchange, String username) throws IOException {
        List<String> rawMails = MailService.listInbox(username);

        if (rawMails == null) {
            sendResponse(exchange, 200, "[]");
            return;
        }
        sendResponse(exchange, 200, convertMailsToJson(rawMails));
    }

    private void handleGetSent(HttpExchange exchange, String username) throws IOException {
        List<String> rawMails = MailService.listSent(username);

        if (rawMails == null) {
            sendResponse(exchange, 200, "[]");
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
                if (isBody) {
                    bodyBuilder.append(line).append('\n');
                } else if (line.isEmpty()) {
                    isBody = true;
                } else if (from.isEmpty() && line.startsWith("From: ")) {
                    from = line.substring(6).trim();
                } else if (to.isEmpty() && line.startsWith("To: ")) {
                    to = line.substring(4).trim();
                } else if (subject.isEmpty() && line.startsWith("Subject: ")) {
                    subject = line.substring(9).trim();
                } else if (date.isEmpty() && line.startsWith("Date: ")) {
                    date = line.substring(6).trim();
                }
            }

            if (bodyBuilder.length() > 0 && bodyBuilder.charAt(bodyBuilder.length() - 1) == '\n') {
                bodyBuilder.setLength(bodyBuilder.length() - 1);
            }

            String sanitizedBody = sanitizeHtml(bodyBuilder.toString());

            sb.append("{")
              .append("\"sender\":\"").append(escapeJson(from)).append("\",")
              .append("\"recipient\":\"").append(escapeJson(to)).append("\",")
              .append("\"subject\":\"").append(escapeJson(subject)).append("\",")
              .append("\"timestamp\":\"").append(escapeJson(date)).append("\",")
              .append("\"body\":\"").append(escapeJson(sanitizedBody)).append("\"")
              .append("}");

            if (i < rawMails.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private String sanitizeHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("(?i)<script.*?>.*?</script>", "")
                   .replaceAll("(?i)<iframe.*?>.*?</iframe>", "")
                   .replaceAll("(?i)<object.*?>.*?</object>", "")
                   .replaceAll("(?i)<embed.*?>.*?</embed>", "")
                   .replaceAll("(?i)on\\w+\\s*=\\s*\"[^\"]*\"", "")
                   .replaceAll("(?i)on\\w+\\s*=\\s*'[^']*'", "")
                   .replaceAll("(?i)javascript:", "");
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\r", "").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t");
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
            int separator = pair.indexOf('=');
            if (separator > 0 && pair.substring(0, separator).equalsIgnoreCase(paramName)) {
                return java.net.URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
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