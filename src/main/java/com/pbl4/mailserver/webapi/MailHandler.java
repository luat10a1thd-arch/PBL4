package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.MailService;
import com.pbl4.mailserver.core.MailStorageEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

public class MailHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // CORS do CorsHandlerWrapper đặt một lần duy nhất, không thêm header CORS ở đây

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        // 1. DÀNH RIÊNG CHO LUỒNG SSE REALTIME (Không dùng Auth Header thông thường)
        if ("GET".equalsIgnoreCase(method) && path.endsWith("/stream")) {
            handleSseStream(exchange);
            return;
        }

        // 2. XÁC THỰC USER CHO CÁC API THÔNG THƯỜNG
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        String token = (authHeader != null && authHeader.startsWith("Bearer "))
                ? authHeader.substring(7) : null;
        String authedUser = SessionManager.validate(token);

        if (authedUser == null) {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Chưa đăng nhập hoặc phiên hết hạn!\"}");
            return;
        }

        if ("POST".equalsIgnoreCase(method) && path.endsWith("/stream-ticket")) {
            handleStreamTicket(exchange, authedUser, token);
        } else if ("POST".equalsIgnoreCase(method) && path.endsWith("/send")) {
            handleSendMail(exchange, authedUser);
        } else if ("GET".equalsIgnoreCase(method) && path.endsWith("/inbox")) {
            handleGetInbox(exchange, authedUser);
        } else if ("GET".equalsIgnoreCase(method) && path.endsWith("/sent")) {
            handleGetSent(exchange, authedUser);
        } else if ("GET".equalsIgnoreCase(method) && path.endsWith("/detail")) {
            handleGetMailDetail(exchange, authedUser);
        } else if ("GET".equalsIgnoreCase(method) && path.endsWith("/storage-usage")) {
            handleGetStorageUsage(exchange, authedUser);
        } else if (("POST".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)) && path.endsWith("/delete")) {
            handleDeleteMail(exchange, authedUser);
        } else {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Endpoint không tồn tại!\"}");
        }
    }

    // Cấp ticket dùng một lần (sống ~30s) để mở SSE mà không đưa token phiên lên URL
    private void handleStreamTicket(HttpExchange exchange, String username, String token) throws IOException {
        String ticket = SseManager.issueTicket(username, token);
        if (ticket == null) {
            sendResponse(exchange, 503, "{\"success\": false, \"message\": \"Hệ thống bận, vui lòng thử lại!\"}");
            return;
        }
        sendResponse(exchange, 200, "{\"success\": true, \"ticket\": \"" + ticket + "\"}");
    }

    private void handleSseStream(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String ticket = null;

        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length > 1 && "ticket".equalsIgnoreCase(pair[0])) {
                    ticket = java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                }
            }
        }

        // Ticket chỉ dùng được một lần; kiểm tra lại phiên còn hợp lệ và đúng user
        SseManager.TicketInfo info = SseManager.consumeTicket(ticket);
        String username = (info == null) ? null : SessionManager.validate(info.token);
        if (info == null || username == null || !username.equalsIgnoreCase(info.username)) {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Ticket không hợp lệ hoặc đã hết hạn!\"}");
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");

        exchange.sendResponseHeaders(200, 0); // Giữ luồng Chunked
        OutputStream os = exchange.getResponseBody();

        // Ghi lời chào TRƯỚC khi đăng ký để không lẫn với dữ liệu từ luồng thông báo
        try {
            os.write("retry: 5000\n: connected\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (Exception e) {
            try { exchange.close(); } catch (Exception ignored) {}
            return;
        }

        // Chỉ đăng ký MỘT key (username đầy đủ); SseManager tự giới hạn số kết nối/user
        SseManager.register(username, info.token, exchange, os);
    }

    private void handleSendMail(HttpExchange exchange, String from) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, (int) Math.min(Integer.MAX_VALUE, ServerConfig.MAX_BODY_SEND_BYTES),
                ServerConfig.BODY_READ_TOTAL_TIMEOUT_SEND_MS);
        if (body == null) return;
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
        handleGetMailBoxPaged(exchange, username);
    }

    private void handleGetSent(HttpExchange exchange, String username) throws IOException {
        handleGetMailBoxPaged(exchange, username + "_sent");
    }

    private void handleGetMailBoxPaged(HttpExchange exchange, String targetMailbox) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        int page = 1;
        int limit = 30;

        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length > 1) {
                    try {
                        if ("page".equalsIgnoreCase(pair[0])) {
                            page = Integer.parseInt(pair[1]);
                        }
                        if ("limit".equalsIgnoreCase(pair[0])) {
                            limit = Integer.parseInt(pair[1]);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        if (page < 1) page = 1;
        if (limit < 1) limit = 30;
        if (limit > 100) limit = 100;

        MailStorageEngine.MailPage mailPage = MailStorageEngine.listMailSummaries(targetMailbox, page, limit);

        exchange.getResponseHeaders().set("X-Total-Count", String.valueOf(mailPage.total));
        exchange.getResponseHeaders().set("Access-Control-Expose-Headers", "X-Total-Count");

        String jsonArray = convertSummariesToJson(mailPage.mails);
        sendResponse(exchange, 200, jsonArray);
    }

    private void handleGetMailDetail(HttpExchange exchange, String username) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String fileId = null;
        String folder = "inbox";

        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length > 1) {
                    if ("id".equalsIgnoreCase(pair[0])) fileId = java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    if ("folder".equalsIgnoreCase(pair[0])) folder = java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                }
            }
        }

        if (fileId == null) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu ID thư!\"}");
            return;
        }

        String targetUser = "sent".equalsIgnoreCase(folder) ? username + "_sent" : username;
        MailStorageEngine.StoredMail mail = MailStorageEngine.getSingleMail(targetUser, fileId);

        if (mail == null || mail.content == null) {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy thư!\"}");
            return;
        }

        sendResponse(exchange, 200, convertSingleMailToJson(mail));
    }

    private void handleGetStorageUsage(HttpExchange exchange, String username) throws IOException {
        long inboxBytes = MailStorageEngine.getMailboxSizeInBytes(username);
        long sentBytes = MailStorageEngine.getMailboxSizeInBytes(username + "_sent");
        long totalUsedBytes = inboxBytes + sentBytes;

        long quotaMb = ServerConfig.MAX_MAILBOX_QUOTA_MB;
        long quotaBytes = quotaMb * 1024L * 1024L;
        double usedMb = totalUsedBytes / (1024.0 * 1024.0);

        String json = String.format(
            "{\"success\": true, \"usedBytes\": %d, \"usedMb\": %.2f, \"quotaMb\": %d, \"quotaBytes\": %d}",
            totalUsedBytes, usedMb, quotaMb, quotaBytes
        );
        sendResponse(exchange, 200, json);
    }

    private void handleDeleteMail(HttpExchange exchange, String username) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;
        String fileId = getParamValue(body, "id");
        String folder = getParamValue(body, "folder");

        if (fileId == null || fileId.trim().isEmpty()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu ID thư cần xóa!\"}");
            return;
        }

        String targetUser = ("sent".equalsIgnoreCase(folder)) ? username + "_sent" : username;
        MailStorageEngine.deleteMailsByIds(targetUser, Collections.singletonList(fileId));

        sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã xóa thư thành công!\"}");
    }

    private String convertSummariesToJson(List<MailStorageEngine.MailSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) return "[]";

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < summaries.size(); i++) {
            MailStorageEngine.MailSummary summary = summaries.get(i);
            sb.append("{")
              .append("\"id\":\"").append(escapeJson(summary.id)).append("\",")
              .append("\"sender\":\"").append(escapeJson(summary.sender)).append("\",")
              .append("\"recipient\":\"").append(escapeJson(summary.recipient)).append("\",")
              .append("\"subject\":\"").append(escapeJson(summary.subject)).append("\",")
              .append("\"timestamp\":\"").append(escapeJson(summary.timestamp)).append("\"")
              .append("}");

            if (i < summaries.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private String convertSingleMailToJson(MailStorageEngine.StoredMail storedMail) {
        ParsedMail parsed = parseRawEmail(storedMail.content);

        return "{"
          + "\"id\":\"" + escapeJson(storedMail.id) + "\","
          + "\"sender\":\"" + escapeJson(parsed.from) + "\","
          + "\"recipient\":\"" + escapeJson(parsed.to) + "\","
          + "\"subject\":\"" + escapeJson(parsed.subject) + "\","
          + "\"timestamp\":\"" + escapeJson(parsed.date) + "\","
          + "\"body\":\"" + escapeJson(parsed.body) + "\""
          + "}";
    }

    private static class ParsedMail {
        String from = "";
        String to = "";
        String subject = "";
        String date = "";
        String body = "";
    }

    private ParsedMail parseRawEmail(String rawContent) {
        ParsedMail mail = new ParsedMail();
        if (rawContent == null) return mail;

        String[] lines = rawContent.split("\r?\n");
        StringBuilder bodyBuilder = new StringBuilder();
        boolean isBody = false;

        for (String line : lines) {
            if (isBody) {
                bodyBuilder.append(line).append('\n');
            } else if (line.isEmpty()) {
                isBody = true;
            } else {
                String lower = line.toLowerCase();
                if (mail.from.isEmpty() && lower.startsWith("from:")) {
                    mail.from = line.substring(5).trim();
                } else if (mail.to.isEmpty() && lower.startsWith("to:")) {
                    mail.to = line.substring(3).trim();
                } else if (mail.subject.isEmpty() && lower.startsWith("subject:")) {
                    mail.subject = line.substring(8).trim();
                } else if (mail.date.isEmpty() && lower.startsWith("date:")) {
                    mail.date = line.substring(5).trim();
                }
            }
        }

        if (bodyBuilder.length() > 0 && bodyBuilder.charAt(bodyBuilder.length() - 1) == '\n') {
            bodyBuilder.setLength(bodyBuilder.length() - 1);
        }

        mail.body = bodyBuilder.toString();
        return mail;
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\r", "").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t");
    }

    private String getParamValue(String body, String paramName) {
        for (String pair : body.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0 && pair.substring(0, separator).equalsIgnoreCase(paramName)) {
                String val = pair.substring(separator + 1);
                if ("body".equalsIgnoreCase(paramName)) {
                    val = val.replace("+", "%2B");
                }
                return java.net.URLDecoder.decode(val, StandardCharsets.UTF_8);
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
