package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.MailService;
import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.mime.AttachmentInfo;
import com.pbl4.mailserver.mime.MimeBuilder;
import com.pbl4.mailserver.mime.MimeParser;
import com.pbl4.mailserver.mime.ParsedMail;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class MailHandler implements HttpHandler {

    /** Số người nhận tối đa trong một yêu cầu /send (chặn gửi hàng loạt qua web). */
    static final int MAX_RECIPIENTS_PER_REQUEST = 100;

    private static final String MSG_BAD_RCPT = "Địa chỉ email người nhận không hợp lệ!";

    // local-part không chứa khoảng trắng / ký tự đặc biệt; domain gồm chữ, số, dấu chấm, gạch nối (cho phép Unicode)
    private static final Pattern RCPT_PATTERN =
            Pattern.compile("^[^\\s@<>\"(),:;\\[\\]\\\\]{1,64}@[\\p{L}\\p{N}][\\p{L}\\p{N}.-]{0,253}$");

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

    // ------------------------------------------------------------------------------------------------
    // GỬI THƯ
    // ------------------------------------------------------------------------------------------------

    private void handleSendMail(HttpExchange exchange, String authUser) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, (int) Math.min(Integer.MAX_VALUE, ServerConfig.MAX_BODY_SEND_BYTES),
                ServerConfig.BODY_READ_TOTAL_TIMEOUT_SEND_MS);
        if (body == null) return;

        Map<String, String> form = parseForm(body);
        String rawTo = form.get("to");
        String subject = form.get("subject");
        String content = form.get("body");

        List<String> rcptList = parseRecipients(rawTo);
        if (rcptList == null || rcptList.isEmpty()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"" + escapeJson(MSG_BAD_RCPT) + "\"}");
            return;
        }
        if (rcptList.size() > MAX_RECIPIENTS_PER_REQUEST) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Quá nhiều người nhận trong một thư!\"}");
            return;
        }

        // Chống chèn header qua Subject: bỏ mọi CR/LF
        String cleanSubject = subject == null ? "" : subject.replace('\r', ' ').replace('\n', ' ');
        String fromAddress = fromAddress(authUser);

        MailService.DeliveryResult result;
        try {
            byte[] raw = MimeBuilder.fromWebForm(fromAddress, String.join(", ", rcptList), cleanSubject,
                    content == null ? "" : content);
            Set<String> rcpts = new LinkedHashSet<>(rcptList);
            result = MailService.submit(authUser, fromAddress, rcpts, raw);
        } catch (RuntimeException e) {
            System.err.println("[MAIL API] Lỗi khi gửi thư của " + authUser + ": " + e);
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Lỗi hệ thống khi gửi thư, vui lòng thử lại!\"}");
            return;
        }

        if (result.success) {
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"" + escapeJson(result.message) + "\"}");
        } else {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"" + escapeJson(result.message) + "\"}");
        }
    }

    /** Địa chỉ đầy đủ của người gửi: phiên chỉ lưu tên đăng nhập nên ghép thêm MAIL_DOMAIN. */
    private static String fromAddress(String authUser) {
        return authUser.indexOf('@') >= 0 ? authUser : authUser + "@" + ServerConfig.MAIL_DOMAIN;
    }

    /**
     * Tách danh sách người nhận (phân cách bằng dấu cách, phẩy, chấm phẩy). Địa chỉ không có '@' được hiểu là
     * tài khoản nội bộ và ghép MAIL_DOMAIN. Trả null nếu có địa chỉ sai cú pháp.
     */
    static List<String> parseRecipients(String rawTo) {
        Set<String> out = new LinkedHashSet<>();
        if (rawTo == null) return List.of();
        for (String tok : rawTo.split("[\\s,;]+")) {
            String t = tok.trim();
            if (t.startsWith("<") && t.endsWith(">") && t.length() > 2) t = t.substring(1, t.length() - 1);
            if (t.regionMatches(true, 0, "mailto:", 0, 7)) t = t.substring(7);
            if (t.isEmpty()) continue;
            if (t.indexOf('@') < 0) t = t + "@" + ServerConfig.MAIL_DOMAIN;
            if (t.length() > 254 || !RCPT_PATTERN.matcher(t).matches()) return null;
            out.add(t);
            if (out.size() > MAX_RECIPIENTS_PER_REQUEST + 1) break;
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------------------------------------
    // DANH SÁCH / CHI TIẾT / XÓA
    // ------------------------------------------------------------------------------------------------

    private void handleGetInbox(HttpExchange exchange, String username) throws IOException {
        Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
        handleGetMailBoxPaged(exchange, mailboxFor(username, q.get("folder")), q);
    }

    private void handleGetSent(HttpExchange exchange, String username) throws IOException {
        Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
        handleGetMailBoxPaged(exchange, username + "_sent", q);
    }

    private void handleGetMailBoxPaged(HttpExchange exchange, String targetMailbox, Map<String, String> q) throws IOException {
        int page = 1;
        int limit = 30;

        try {
            if (q.get("page") != null) page = Integer.parseInt(q.get("page"));
        } catch (NumberFormatException ignored) {}
        try {
            if (q.get("limit") != null) limit = Integer.parseInt(q.get("limit"));
        } catch (NumberFormatException ignored) {}

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
        Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
        String fileId = q.get("id");
        String folder = q.get("folder");

        if (fileId == null) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu ID thư!\"}");
            return;
        }

        String targetUser = mailboxFor(username, folder);
        MailStorageEngine.StoredMail mail = MailStorageEngine.getSingleMail(targetUser, fileId);

        if (mail == null || mail.rawBytes() == null) {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy thư!\"}");
            return;
        }

        sendResponse(exchange, 200, buildDetailJson(mail.id, MimeParser.parse(mail.rawBytes())));
    }

    private void handleGetStorageUsage(HttpExchange exchange, String username) throws IOException {
        // Quota dùng chung cho inbox + sent + spam (khớp MailStorageEngine)
        long inboxBytes = MailStorageEngine.getMailboxSizeInBytes(username);
        long sentBytes = MailStorageEngine.getMailboxSizeInBytes(username + "_sent");
        long spamBytes = MailStorageEngine.getMailboxSizeInBytes(username + "_spam");
        long totalUsedBytes = inboxBytes + sentBytes + spamBytes;

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
        Map<String, String> form = parseForm(body);
        String fileId = form.get("id");
        String folder = form.get("folder");

        if (fileId == null || fileId.trim().isEmpty()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu ID thư cần xóa!\"}");
            return;
        }

        String targetUser = mailboxFor(username, folder);
        MailStorageEngine.deleteMailsByIds(targetUser, Collections.singletonList(fileId));

        sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã xóa thư thành công!\"}");
    }

    /** folder=sent -> u_sent, folder=spam -> u_spam, còn lại (inbox, rỗng, giá trị lạ) -> u. */
    static String mailboxFor(String username, String folder) {
        if ("sent".equalsIgnoreCase(folder)) return username + "_sent";
        if ("spam".equalsIgnoreCase(folder)) return username + "_spam";
        return username;
    }

    // ------------------------------------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------------------------------------

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

    /**
     * JSON chi tiết thư, ĐÚNG định dạng cũ: id, sender, recipient, subject, timestamp, body.
     * Đính kèm được nối vào cuối body dạng tag để app.js hiện có hiển thị/tải về không cần sửa.
     */
    static String buildDetailJson(String id, ParsedMail parsed) {
        String tags = attachmentsToTags(parsed.attachments());
        StringBuilder body = new StringBuilder(parsed.text().length() + tags.length() + 1);
        body.append(parsed.text());
        if (!tags.isEmpty()) {
            if (body.length() > 0) body.append('\n');
            body.append(tags);
        }

        StringBuilder sb = new StringBuilder(body.length() + 512);
        sb.append("{\"id\":\"");
        appendEscaped(sb, id);
        sb.append("\",\"sender\":\"");
        appendEscaped(sb, parsed.from());
        sb.append("\",\"recipient\":\"");
        appendEscaped(sb, parsed.to());
        sb.append("\",\"subject\":\"");
        appendEscaped(sb, parsed.subject());
        sb.append("\",\"timestamp\":\"");
        appendEscaped(sb, parsed.date());
        sb.append("\",\"body\":\"");
        appendEscaped(sb, body);
        sb.append("\"}");
        return sb.toString();
    }

    /**
     * Tái tạo "[ATTACHMENT:tên]base64[/ATTACHMENT]" cho từng đính kèm, mỗi tag trên một dòng (khớp regex của app.js).
     * Tệp 0 byte bị bỏ qua vì regex của frontend cần dữ liệu khác rỗng.
     */
    static String attachmentsToTags(List<AttachmentInfo> attachments) {
        if (attachments == null || attachments.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        Base64.Encoder enc = Base64.getEncoder();
        for (AttachmentInfo a : attachments) {
            if (a.data().length == 0) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append("[ATTACHMENT:").append(safeTagName(a.name())).append(']')
              .append(enc.encodeToString(a.data()))
              .append("[/ATTACHMENT]");
        }
        return sb.toString();
    }

    /** Tên trong tag không được chứa ']' hoặc xuống dòng (regex frontend: [^\]\r\n]+). */
    static String safeTagName(String name) {
        if (name == null) return "attachment";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == ']' || c == '[' || c < 0x20 || c == 0x7f) sb.append('_');
            else sb.append(c);
        }
        String n = sb.toString().trim();
        return n.isEmpty() ? "attachment" : n;
    }

    static String escapeJson(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder(input.length() + 16);
        appendEscaped(sb, input);
        return sb.toString();
    }

    /** Escape JSON đầy đủ: " \ , mọi ký tự điều khiển (< 0x20, 0x7F), U+2028/2029, surrogate lẻ. Unicode hợp lệ giữ nguyên. */
    static void appendEscaped(StringBuilder sb, CharSequence s) {
        if (s == null) return;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || c == 0x7f || c == '\u2028' || c == '\u2029') {
                        sb.append("\\u").append(String.format("%04x", (int) c));
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                            sb.append(c).append(s.charAt(++i));
                        } else {
                            sb.append("\\ufffd");
                        }
                    } else if (Character.isLowSurrogate(c)) {
                        sb.append("\\ufffd");
                    } else {
                        sb.append(c);
                    }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // TIỆN ÍCH HTTP
    // ------------------------------------------------------------------------------------------------

    /** Query string (dạng thô) -> map, khóa viết thường, giá trị đã giải mã; tham số cuối cùng thắng; bỏ qua cặp lỗi %. */
    static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return map;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                String key = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                String val = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if (!val.isEmpty()) map.put(key, val);
            } catch (IllegalArgumentException ignored) {
                // %-escape hỏng: bỏ qua tham số này
            }
        }
        return map;
    }

    /**
     * Form application/x-www-form-urlencoded -> map (một lần quét, không split nhiều lần trên body lớn).
     * Giữ đúng hành vi cũ: tham số đầu tiên thắng, khóa không phân biệt hoa thường, riêng "body" giữ nguyên dấu '+'.
     */
    static Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<>();
        int n = body.length();
        int start = 0;
        while (start <= n) {
            int amp = body.indexOf('&', start);
            int end = amp < 0 ? n : amp;
            int sep = body.indexOf('=', start);
            if (sep > start && sep < end) {
                String key = body.substring(start, sep).toLowerCase(Locale.ROOT);
                if (!map.containsKey(key)) {
                    String val = body.substring(sep + 1, end);
                    if ("body".equals(key)) {
                        val = val.replace("+", "%2B");
                    }
                    try {
                        map.put(key, java.net.URLDecoder.decode(val, StandardCharsets.UTF_8));
                    } catch (IllegalArgumentException ignored) {
                        // %-escape hỏng: coi như thiếu tham số
                    }
                }
            }
            if (amp < 0) break;
            start = amp + 1;
        }
        return map;
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