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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MailHandler implements HttpHandler {

    /** Số người nhận tối đa trong một yêu cầu /send (chặn gửi hàng loạt qua web). */
    static final int MAX_RECIPIENTS_PER_REQUEST = 100;

    private static final String MSG_BAD_RCPT = "Địa chỉ email người nhận không hợp lệ!";

    // local-part không chứa khoảng trắng / ký tự đặc biệt; domain gồm chữ, số, dấu chấm, gạch nối (cho phép Unicode)
    private static final Pattern RCPT_PATTERN =
            Pattern.compile("^[^\\s@<>\"(),:;\\[\\]\\\\]{1,64}@[\\p{L}\\p{N}][\\p{L}\\p{N}.-]{0,253}$");

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        // 1. DÀNH RIÊNG CHO LUỒNG SSE REALTIME
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
            handleSendMail(exchange, authedUser, false);
        } else if ("POST".equalsIgnoreCase(method) && path.endsWith("/reply")) {
            handleSendMail(exchange, authedUser, true);
        } else if ("POST".equalsIgnoreCase(method) && path.endsWith("/save-draft")) {
            handleSaveDraft(exchange, authedUser);
        } else if (("POST".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)) && path.endsWith("/delete")) {
            handleDeleteMail(exchange, authedUser);
        } else if ("POST".equalsIgnoreCase(method) && path.endsWith("/restore")) {
            handleRestoreMail(exchange, authedUser);
        } else if ("POST".equalsIgnoreCase(method) && path.endsWith("/report-spam")) {
            handleReportSpam(exchange, authedUser);
        } else if ("POST".equalsIgnoreCase(method) && path.endsWith("/unspam")) {
            handleUnspamMail(exchange, authedUser);
        } else if ("POST".equalsIgnoreCase(method) && path.endsWith("/flags")) {
            handleUpdateFlags(exchange, authedUser);
        } else if ("GET".equalsIgnoreCase(method) && path.endsWith("/inbox")) {
            handleGetInbox(exchange, authedUser);
        } else if ("GET".equalsIgnoreCase(method) && path.endsWith("/sent")) {
            handleGetSent(exchange, authedUser);
        } else if ("GET".equalsIgnoreCase(method) && path.endsWith("/drafts")) {
            Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
            handleGetMailBoxPaged(exchange, mailboxFor(authedUser, "drafts"), q);
        } else if ("GET".equalsIgnoreCase(method) && path.endsWith("/spam")) {
            Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
            handleGetMailBoxPaged(exchange, mailboxFor(authedUser, "spam"), q);
        } else if ("GET".equalsIgnoreCase(method) && path.endsWith("/trash")) {
            Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
            handleGetMailBoxPaged(exchange, mailboxFor(authedUser, "trash"), q);
        } else if ("GET".equalsIgnoreCase(method) && path.endsWith("/starred")) {
            handleGetStarred(exchange, authedUser);
        } else if ("GET".equalsIgnoreCase(method) && path.endsWith("/search")) {
            handleSearchMails(exchange, authedUser);
        } else if ("GET".equalsIgnoreCase(method) && path.endsWith("/thread")) {
            handleGetThread(exchange, authedUser);
        } else if ("GET".equalsIgnoreCase(method) && path.endsWith("/detail")) {
            handleGetMailDetail(exchange, authedUser);
        } else if ("GET".equalsIgnoreCase(method) && (path.endsWith("/storage-usage") || path.endsWith("/quota"))) {
            handleGetStorageUsage(exchange, authedUser);
        } else {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Endpoint không tồn tại!\"}");
        }
    }

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

        exchange.sendResponseHeaders(200, 0);
        OutputStream os = exchange.getResponseBody();

        try {
            os.write("retry: 5000\n: connected\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (Exception e) {
            try { exchange.close(); } catch (Exception ignored) {}
            return;
        }

        SseManager.register(username, info.token, exchange, os);
    }

    // ------------------------------------------------------------------------------------------------
    // GỬI THƯ / TRẢ LỜI THƯ (REPLY) / LƯU THƯ NHÁP (DRAFTS)
    // ------------------------------------------------------------------------------------------------

    private void handleSendMail(HttpExchange exchange, String authUser, boolean forceReply) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, (int) Math.min(Integer.MAX_VALUE, ServerConfig.MAX_BODY_SEND_BYTES),
                ServerConfig.BODY_READ_TOTAL_TIMEOUT_SEND_MS);
        if (body == null) return;

        Map<String, String> form = parseForm(body);
        String rawTo = form.get("to");
        String subject = form.get("subject");
        String content = form.get("body");
        String inReplyTo = form.get("inreplyto");
        String references = form.get("references");
        String draftId = form.get("draftid");

        List<String> rcptList = parseRecipients(rawTo);
        if (rcptList == null || rcptList.isEmpty()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"" + escapeJson(MSG_BAD_RCPT) + "\"}");
            return;
        }
        if (rcptList.size() > MAX_RECIPIENTS_PER_REQUEST) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Quá nhiều người nhận trong một thư!\"}");
            return;
        }

        String cleanSubject = subject == null ? "" : subject.replace('\r', ' ').replace('\n', ' ').trim();
        if ((forceReply || (inReplyTo != null && !inReplyTo.isEmpty()))
                && !cleanSubject.regionMatches(true, 0, "Re:", 0, 3)) {
            cleanSubject = "Re: " + cleanSubject;
        }

        String fromAddress = fromAddress(authUser);

        MailService.DeliveryResult result;
        try {
            byte[] raw = MimeBuilder.fromWebFormWithReply(
                    fromAddress,
                    String.join(", ", rcptList),
                    cleanSubject,
                    content == null ? "" : content,
                    inReplyTo,
                    references
            );
            Set<String> rcpts = new LinkedHashSet<>(rcptList);
            result = MailService.submit(authUser, fromAddress, rcpts, raw);
        } catch (RuntimeException e) {
            System.err.println("[MAIL API] Lỗi khi gửi thư của " + authUser + ": " + e);
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Lỗi hệ thống khi gửi thư, vui lòng thử lại!\"}");
            return;
        }

        if (result.success) {
            // Nếu thư gửi đi từ một bản nháp thì xóa bản nháp đó
            if (draftId != null && !draftId.trim().isEmpty()) {
                MailStorageEngine.deleteMailsByIds(mailboxFor(authUser, "drafts"), Collections.singletonList(draftId.trim()));
            }
            SseManager.notifyStateChange(authUser, "MAIL_SENT", "", "sent");
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"" + escapeJson(result.message) + "\"}");
        } else {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"" + escapeJson(result.message) + "\"}");
        }
    }

    private void handleSaveDraft(HttpExchange exchange, String authUser) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, (int) Math.min(Integer.MAX_VALUE, ServerConfig.MAX_BODY_SEND_BYTES),
                ServerConfig.BODY_READ_TOTAL_TIMEOUT_SEND_MS);
        if (body == null) return;

        Map<String, String> form = parseForm(body);
        String rawTo = form.get("to");
        String subject = form.get("subject");
        String content = form.get("body");
        String oldDraftId = form.get("draftid");

        if ((rawTo == null || rawTo.trim().isEmpty())
                && (subject == null || subject.trim().isEmpty())
                && (content == null || content.trim().isEmpty())) {
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Không có nội dung để lưu nháp.\"}");
            return;
        }

        String cleanSubject = subject == null ? "" : subject.replace('\r', ' ').replace('\n', ' ');
        String fromAddress = fromAddress(authUser);

        try {
            byte[] raw = MimeBuilder.fromWebForm(fromAddress, rawTo == null ? "" : rawTo.trim(), cleanSubject,
                    content == null ? "" : content);
            String draftMailbox = mailboxFor(authUser, "drafts");
            MailStorageEngine.initMailbox(draftMailbox);

            if (oldDraftId != null && !oldDraftId.trim().isEmpty()) {
                MailStorageEngine.deleteMailsByIds(draftMailbox, Collections.singletonList(oldDraftId.trim()));
            }

            MailStorageEngine.SaveResult sr = MailStorageEngine.saveEmailResult(draftMailbox, raw);
            if (sr == MailStorageEngine.SaveResult.OK) {
                SseManager.notifyStateChange(authUser, "DRAFT_SAVED", "", "drafts");
                sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã lưu vào thư nháp!\"}");
            } else if (sr == MailStorageEngine.SaveResult.OVER_QUOTA) {
                sendResponse(exchange, 507, "{\"success\": false, \"message\": \"Hòm thư đã đầy, không thể lưu nháp!\"}");
            } else {
                sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Không thể lưu thư nháp!\"}");
            }
        } catch (Exception e) {
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Lỗi hệ thống khi lưu thư nháp!\"}");
        }
    }

    private static String fromAddress(String authUser) {
        return authUser.indexOf('@') >= 0 ? authUser : authUser + "@" + ServerConfig.MAIL_DOMAIN;
    }

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
    // DANH SÁCH / TÌM KIẾM / THREAD / CHI TIẾT / XÓA / KHÔI PHỤC / CỜ TRẠNG THÁI
    // ------------------------------------------------------------------------------------------------

    private void handleGetInbox(HttpExchange exchange, String username) throws IOException {
        Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
        String folder = q.get("folder");
        if ("starred".equalsIgnoreCase(folder)) {
            handleGetStarred(exchange, username);
            return;
        }
        handleGetMailBoxPaged(exchange, mailboxFor(username, folder), q);
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

        String keyword = q.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
        if (!keyword.isEmpty()) {
            List<MailStorageEngine.MailSummary> matched = searchInMailbox(targetMailbox, keyword);
            int total = matched.size();
            int start = Math.min((page - 1) * limit, total);
            int end = Math.min(start + limit, total);
            exchange.getResponseHeaders().set("X-Total-Count", String.valueOf(total));
            exchange.getResponseHeaders().set("Access-Control-Expose-Headers", "X-Total-Count");
            sendResponse(exchange, 200, convertSummariesToJson(targetMailbox, matched.subList(start, end)));
            return;
        }

        // Nạp danh sách và ưu tiên các thư có gắn dấu sao (starred = true) lên đầu danh sách
        MailStorageEngine.MailPage allPage = MailStorageEngine.listMailSummaries(targetMailbox, 1, 500);
        List<MailStorageEngine.MailSummary> sorted = sortStarredFirst(targetMailbox, allPage.mails);
        int total = allPage.total;
        int start = Math.min((page - 1) * limit, sorted.size());
        int end = Math.min(start + limit, sorted.size());

        exchange.getResponseHeaders().set("X-Total-Count", String.valueOf(total));
        exchange.getResponseHeaders().set("Access-Control-Expose-Headers", "X-Total-Count");

        String jsonArray = convertSummariesToJson(targetMailbox, sorted.subList(start, end));
        sendResponse(exchange, 200, jsonArray);
    }

    private void handleGetStarred(HttpExchange exchange, String username) throws IOException {
        String[] boxes = {
            username,
            username + "_sent",
            username + "_drafts",
            username + "_spam"
        };
        List<MailStorageEngine.MailSummary> starredList = new ArrayList<>();
        Map<String, String> mailOriginBox = new HashMap<>();

        for (String box : boxes) {
            MailStorageEngine.MailPage page = MailStorageEngine.listMailSummaries(box, 1, 500);
            for (MailStorageEngine.MailSummary m : page.mails) {
                Properties flags = MailStorageEngine.getMailFlags(box, m.id);
                if (Boolean.parseBoolean(flags.getProperty("starred", "false"))) {
                    starredList.add(m);
                    mailOriginBox.put(m.id, box);
                }
            }
        }
        starredList.sort((a, b) -> b.id.compareTo(a.id));
        exchange.getResponseHeaders().set("X-Total-Count", String.valueOf(starredList.size()));
        exchange.getResponseHeaders().set("Access-Control-Expose-Headers", "X-Total-Count");

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < starredList.size(); i++) {
            MailStorageEngine.MailSummary s = starredList.get(i);
            String box = mailOriginBox.getOrDefault(s.id, username);
            Properties flags = MailStorageEngine.getMailFlags(box, s.id);
            boolean seen = Boolean.parseBoolean(flags.getProperty("seen", "false"));
            if (i > 0) sb.append(",");
            sb.append("{")
              .append("\"id\":\"").append(escapeJson(s.id)).append("\",")
              .append("\"sender\":\"").append(escapeJson(s.sender)).append("\",")
              .append("\"recipient\":\"").append(escapeJson(s.recipient)).append("\",")
              .append("\"subject\":\"").append(escapeJson(s.subject)).append("\",")
              .append("\"timestamp\":\"").append(escapeJson(s.timestamp)).append("\",")
              .append("\"seen\":").append(seen).append(",")
              .append("\"starred\":true")
              .append("}");
        }
        sb.append("]");
        sendResponse(exchange, 200, sb.toString());
    }

    private void handleSearchMails(HttpExchange exchange, String username) throws IOException {
        Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
        String keyword = q.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
        String folder = q.getOrDefault("folder", "inbox");
        String targetMailbox = mailboxFor(username, folder);

        List<MailStorageEngine.MailSummary> matched = keyword.isEmpty()
                ? MailStorageEngine.listMailSummaries(targetMailbox, 1, 50).mails
                : searchInMailbox(targetMailbox, keyword);

        exchange.getResponseHeaders().set("X-Total-Count", String.valueOf(matched.size()));
        exchange.getResponseHeaders().set("Access-Control-Expose-Headers", "X-Total-Count");
        sendResponse(exchange, 200, convertSummariesToJson(targetMailbox, matched));
    }

    private List<MailStorageEngine.MailSummary> searchInMailbox(String targetMailbox, String keyword) {
        MailStorageEngine.MailPage allPage = MailStorageEngine.listMailSummaries(targetMailbox, 1, 500);
        List<MailStorageEngine.MailSummary> matched = new ArrayList<>();
        for (MailStorageEngine.MailSummary m : allPage.mails) {
            boolean headerHit = (m.sender != null && m.sender.toLowerCase(Locale.ROOT).contains(keyword))
                    || (m.recipient != null && m.recipient.toLowerCase(Locale.ROOT).contains(keyword))
                    || (m.subject != null && m.subject.toLowerCase(Locale.ROOT).contains(keyword));
            if (headerHit) {
                matched.add(m);
                continue;
            }
            // Tìm kiếm toàn văn trong nội dung thư (Full-Text Search)
            MailStorageEngine.StoredMail stored = MailStorageEngine.getSingleMail(targetMailbox, m.id);
            if (stored != null && stored.rawBytes() != null) {
                ParsedMail pm = MimeParser.parse(stored.rawBytes());
                if (pm.text().toLowerCase(Locale.ROOT).contains(keyword)) {
                    matched.add(m);
                }
            }
        }
        return sortStarredFirst(targetMailbox, matched);
    }

    /** Sắp xếp ưu tiên thư gắn sao (starred = true) lên trên đầu, sau đó mới đến thư thường (mới nhất trước). */
    private List<MailStorageEngine.MailSummary> sortStarredFirst(String mailbox, List<MailStorageEngine.MailSummary> list) {
        if (list == null || list.size() <= 1) return list == null ? List.of() : list;
        List<MailStorageEngine.MailSummary> starred = new ArrayList<>();
        List<MailStorageEngine.MailSummary> normal = new ArrayList<>();
        for (MailStorageEngine.MailSummary s : list) {
            Properties flags = MailStorageEngine.getMailFlags(mailbox, s.id);
            if (Boolean.parseBoolean(flags.getProperty("starred", "false"))) {
                starred.add(s);
            } else {
                normal.add(s);
            }
        }
        List<MailStorageEngine.MailSummary> combined = new ArrayList<>(list.size());
        combined.addAll(starred);
        combined.addAll(normal);
        return combined;
    }

    private void handleGetThread(HttpExchange exchange, String username) throws IOException {
        Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
        String subject = q.getOrDefault("subject", "");
        String normSubj = normalizeThreadSubject(subject);
        if (normSubj.isEmpty()) {
            sendResponse(exchange, 200, "[]");
            return;
        }

        String[] boxes = {username, username + "_sent"};
        List<String> itemsJson = new ArrayList<>();
        for (String box : boxes) {
            MailStorageEngine.MailPage p = MailStorageEngine.listMailSummaries(box, 1, 200);
            for (MailStorageEngine.MailSummary s : p.mails) {
                if (normalizeThreadSubject(s.subject).equalsIgnoreCase(normSubj)) {
                    MailStorageEngine.StoredMail sm = MailStorageEngine.getSingleMail(box, s.id);
                    if (sm != null && sm.rawBytes() != null) {
                        itemsJson.add(buildDetailJson(box, sm.id, sm.rawBytes(), MimeParser.parse(sm.rawBytes())));
                    }
                }
            }
        }
        sendResponse(exchange, 200, "[" + String.join(",", itemsJson) + "]");
    }

    private static String normalizeThreadSubject(String s) {
        if (s == null) return "";
        return s.replaceAll("^(?i)(Re:\\s*|Fwd:\\s*)+", "").trim();
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

        // Nếu mở từ tab "starred", có thể thư nằm ở _sent hoặc _spam
        if ((mail == null || mail.rawBytes() == null) && "starred".equalsIgnoreCase(folder)) {
            for (String alt : new String[]{username, username + "_sent", username + "_drafts", username + "_spam"}) {
                mail = MailStorageEngine.getSingleMail(alt, fileId);
                if (mail != null && mail.rawBytes() != null) {
                    targetUser = alt;
                    break;
                }
            }
        }

        if (mail == null || mail.rawBytes() == null) {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy thư!\"}");
            return;
        }

        // Tự động đánh dấu Đã đọc (seen = true) khi mở xem chi tiết
        MailStorageEngine.updateMailFlags(targetUser, mail.id, true, null);

        sendResponse(exchange, 200, buildDetailJson(targetUser, mail.id, mail.rawBytes(), MimeParser.parse(mail.rawBytes())));
    }

    private void handleGetStorageUsage(HttpExchange exchange, String username) throws IOException {
        long totalUsedBytes = MailStorageEngine.getUsedQuotaBytes(username);
        long quotaMb = ServerConfig.MAX_MAILBOX_QUOTA_MB;
        long quotaBytes = quotaMb * 1024L * 1024L;
        double usedMb = totalUsedBytes / (1024.0 * 1024.0);
        int percent = quotaBytes > 0 ? (int) Math.min(100, Math.round((totalUsedBytes * 100.0) / quotaBytes)) : 0;

        String json = String.format(Locale.US,
            "{\"success\": true, \"usedBytes\": %d, \"usedMb\": %.2f, \"quotaMb\": %d, \"maxMb\": %d, \"quotaBytes\": %d, \"percent\": %d}",
            totalUsedBytes, usedMb, quotaMb, quotaMb, quotaBytes, percent
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

        String targetBox = findMailboxContaining(username, fileId, folder);
        boolean hardDelete = "trash".equalsIgnoreCase(folder) || "true".equalsIgnoreCase(form.get("permanent"));

        if (hardDelete) {
            MailStorageEngine.deleteMailsByIds(targetBox, Collections.singletonList(fileId));
            SseManager.notifyStateChange(username, "HARD_DELETED", fileId, folder);
            sendResponse(exchange, 200, "{\"success\": true, \"action\": \"HARD_DELETED\", \"message\": \"Đã xóa vĩnh viễn thư!\"}");
        } else {
            String trashBox = mailboxFor(username, "trash");
            boolean moved = MailStorageEngine.moveEmail(targetBox, trashBox, fileId);
            if (!moved) {
                // Dự phòng nếu file không chuyển được thì xóa trực tiếp
                MailStorageEngine.deleteMailsByIds(targetBox, Collections.singletonList(fileId));
            }
            SseManager.notifyStateChange(username, "MOVED_TO_TRASH", fileId, folder);
            sendResponse(exchange, 200, "{\"success\": true, \"action\": \"MOVED_TO_TRASH\", \"message\": \"Đã chuyển thư vào Thùng rác!\"}");
        }
    }

    private void handleRestoreMail(HttpExchange exchange, String username) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;
        Map<String, String> form = parseForm(body);
        String fileId = form.get("id");
        String targetFolder = form.getOrDefault("targetfolder", "inbox");

        if (fileId == null || fileId.trim().isEmpty()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu ID thư cần khôi phục!\"}");
            return;
        }

        String trashBox = mailboxFor(username, "trash");
        String destBox = mailboxFor(username, targetFolder);
        boolean restored = MailStorageEngine.moveEmail(trashBox, destBox, fileId);

        if (restored) {
            SseManager.notifyStateChange(username, "RESTORED", fileId, targetFolder);
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã khôi phục thư về Hộp thư đến!\"}");
        } else {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy thư trong Thùng rác!\"}");
        }
    }

    private void handleReportSpam(HttpExchange exchange, String username) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;
        Map<String, String> form = parseForm(body);
        String fileId = form.get("id");
        String folder = form.getOrDefault("folder", "inbox");

        if (fileId == null || fileId.trim().isEmpty()) {
            // Hỗ trợ kích hoạt Thư rác trực tiếp khi mới nhập mail người nhận trong hộp thoại Thư mới
            String rawTo = form.get("to");
            String subject = form.getOrDefault("subject", "(Không có chủ đề)");
            String content = form.getOrDefault("body", "");
            if (rawTo != null && !rawTo.trim().isEmpty()) {
                String cleanMe = MailStorageEngine.extractCleanEmail(username);
                String targetPeer = MailStorageEngine.extractCleanEmail(rawTo);
                if (!targetPeer.isEmpty()) {
                    MailStorageEngine.addBlockedSender(cleanMe, targetPeer);
                    MailStorageEngine.addBlockedSender(targetPeer, cleanMe);
                }
                byte[] raw = MimeBuilder.fromWebForm(fromAddress(username), rawTo.trim(), subject, content);
                String spamBox = mailboxFor(username, "spam");
                MailStorageEngine.initMailbox(spamBox);
                MailStorageEngine.saveEmailResult(spamBox, raw);
                if (!targetPeer.isEmpty() && !targetPeer.equalsIgnoreCase(cleanMe)) {
                    String peerSpamBox = mailboxFor(targetPeer, "spam");
                    MailStorageEngine.initMailbox(peerSpamBox);
                    MailStorageEngine.saveEmailResult(peerSpamBox, raw);
                }
                SseManager.notifyStateChange(username, "REPORTED_SPAM", "", "spam");
                sendResponse(exchange, 200, "{\"success\": true, \"blockedSender\": \"" + escapeJson(targetPeer) + "\", \"message\": \"⚠️ Đã đưa vào Thư rác & hạn chế " + escapeJson(targetPeer) + "!\"}");
                return;
            }
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu ID thư hoặc người nhận!\"}");
            return;
        }

        String sourceBox = findMailboxContaining(username, fileId, folder);
        MailStorageEngine.StoredMail mail = MailStorageEngine.getSingleMail(sourceBox, fileId);
        if (mail == null || mail.rawBytes() == null) {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy thư!\"}");
            return;
        }

        ParsedMail parsed = MimeParser.parse(mail.rawBytes());
        String senderEmail = MailStorageEngine.extractCleanEmail(parsed.from());
        String rcptEmail = MailStorageEngine.extractCleanEmail(parsed.to());
        String cleanMe = MailStorageEngine.extractCleanEmail(username);

        // Xác định địa chỉ đối tác cần hạn chế (nếu thư trong Thư đã gửi/Thư nháp thì lấy người nhận, ngược lại lấy người gửi)
        String targetPeer = (!senderEmail.isEmpty() && !senderEmail.equalsIgnoreCase(cleanMe))
                ? senderEmail
                : (!rcptEmail.isEmpty() ? rcptEmail : senderEmail);

        boolean blocked = false;
        if (!targetPeer.isEmpty()) {
            blocked = MailStorageEngine.addBlockedSender(cleanMe, targetPeer);
            MailStorageEngine.addBlockedSender(targetPeer, cleanMe);
        }
        if (!senderEmail.isEmpty()) {
            MailStorageEngine.addBlockedSender(cleanMe, senderEmail);
        }

        String spamBox = mailboxFor(username, "spam");
        if (!sourceBox.equalsIgnoreCase(spamBox)) {
            MailStorageEngine.moveEmail(sourceBox, spamBox, fileId);
        }

        SseManager.notifyStateChange(username, "REPORTED_SPAM", fileId, "spam");
        String msg = blocked
                ? "⚠️ Đã chuyển vào Thư rác & hạn chế địa chỉ " + targetPeer + " (thư tiếp theo sẽ tự động vào Thư rác)!"
                : "⚠️ Đã chuyển thư vào mục Thư rác!";
        sendResponse(exchange, 200, "{\"success\": true, \"blockedSender\": \"" + escapeJson(targetPeer) + "\", \"message\": \"" + escapeJson(msg) + "\"}");
    }

    private void handleUnspamMail(HttpExchange exchange, String username) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;
        Map<String, String> form = parseForm(body);
        String fileId = form.get("id");

        if (fileId == null || fileId.trim().isEmpty()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu ID thư!\"}");
            return;
        }

        String spamBox = mailboxFor(username, "spam");
        MailStorageEngine.StoredMail mail = MailStorageEngine.getSingleMail(spamBox, fileId);
        if (mail == null || mail.rawBytes() == null) {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy thư trong mục Thư rác!\"}");
            return;
        }

        ParsedMail parsed = MimeParser.parse(mail.rawBytes());
        String senderEmail = MailStorageEngine.extractCleanEmail(parsed.from());
        String rcptEmail = MailStorageEngine.extractCleanEmail(parsed.to());
        String cleanMe = MailStorageEngine.extractCleanEmail(username);
        if (!senderEmail.isEmpty()) {
            MailStorageEngine.removeBlockedSender(cleanMe, senderEmail);
            MailStorageEngine.removeBlockedSender(senderEmail, cleanMe);
        }
        if (!rcptEmail.isEmpty()) {
            MailStorageEngine.removeBlockedSender(cleanMe, rcptEmail);
            MailStorageEngine.removeBlockedSender(rcptEmail, cleanMe);
        }

        String inboxBox = mailboxFor(username, "inbox");
        boolean moved = MailStorageEngine.moveEmail(spamBox, inboxBox, fileId);
        if (moved) {
            SseManager.notifyStateChange(username, "UNSPAMMED", fileId, "inbox");
            String msg = !senderEmail.isEmpty()
                    ? "✅ Đã bỏ hạn chế " + senderEmail + " và chuyển thư về Hộp thư đến!"
                    : "✅ Đã chuyển thư về Hộp thư đến!";
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"" + escapeJson(msg) + "\"}");
        } else {
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Không thể chuyển thư về Hộp thư đến!\"}");
        }
    }

    private static String findMailboxContaining(String username, String fileId, String preferredFolder) {
        String preferredBox = mailboxFor(username, preferredFolder);
        if (MailStorageEngine.getSingleMail(preferredBox, fileId) != null) {
            return preferredBox;
        }
        String[] allBoxes = {
            username,
            username + "_sent",
            username + "_drafts",
            username + "_spam",
            username + "_trash"
        };
        for (String box : allBoxes) {
            if (MailStorageEngine.getSingleMail(box, fileId) != null) {
                return box;
            }
        }
        return preferredBox;
    }

    private void handleUpdateFlags(HttpExchange exchange, String username) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;
        Map<String, String> form = parseForm(body);
        String fileId = form.get("id");
        String folder = form.getOrDefault("folder", "inbox");
        Boolean seen = form.containsKey("seen") ? Boolean.parseBoolean(form.get("seen")) : null;
        Boolean starred = form.containsKey("starred") ? Boolean.parseBoolean(form.get("starred")) : null;

        if (fileId == null || fileId.trim().isEmpty()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu ID thư!\"}");
            return;
        }

        String targetBox = findMailboxContaining(username, fileId, folder);
        boolean ok = MailStorageEngine.updateMailFlags(targetBox, fileId, seen, starred);

        if (ok) {
            SseManager.notifyStateChange(username, "FLAGS_UPDATED", fileId, folder);
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã cập nhật trạng thái thư!\"}");
        } else {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy thư!\"}");
        }
    }

    /** Hỗ trợ đầy đủ: inbox, sent, spam, drafts, trash. */
    static String mailboxFor(String username, String folder) {
        if ("sent".equalsIgnoreCase(folder)) return username + "_sent";
        if ("spam".equalsIgnoreCase(folder)) return username + "_spam";
        if ("drafts".equalsIgnoreCase(folder)) return username + "_drafts";
        if ("trash".equalsIgnoreCase(folder)) return username + "_trash";
        return username;
    }

    // ------------------------------------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------------------------------------

    private String convertSummariesToJson(String mailbox, List<MailStorageEngine.MailSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) return "[]";

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < summaries.size(); i++) {
            MailStorageEngine.MailSummary summary = summaries.get(i);
            Properties flags = MailStorageEngine.getMailFlags(mailbox, summary.id);
            boolean seen = Boolean.parseBoolean(flags.getProperty("seen", "false"));
            boolean starred = Boolean.parseBoolean(flags.getProperty("starred", "false"));

            String displaySubject = (summary.subject == null || summary.subject.trim().isEmpty())
                    ? (mailbox.endsWith("_drafts") ? "(Thư nháp)" : "(Không có chủ đề)")
                    : summary.subject;
            sb.append("{")
              .append("\"id\":\"").append(escapeJson(summary.id)).append("\",")
              .append("\"sender\":\"").append(escapeJson(summary.sender)).append("\",")
              .append("\"recipient\":\"").append(escapeJson(summary.recipient)).append("\",")
              .append("\"subject\":\"").append(escapeJson(displaySubject)).append("\",")
              .append("\"timestamp\":\"").append(escapeJson(summary.timestamp)).append("\",")
              .append("\"seen\":").append(seen).append(",")
              .append("\"starred\":").append(starred)
              .append("}");

            if (i < summaries.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    /** Giữ hàm cũ để tương thích ngược với các test gọi trực tiếp MailHandler.buildDetailJson(id, parsed). */
    static String buildDetailJson(String id, ParsedMail parsed) {
        return buildDetailJson(null, id, null, parsed);
    }

    static String buildDetailJson(String mailbox, String id, byte[] rawBytes, ParsedMail parsed) {
        String tags = attachmentsToTags(parsed.attachments());
        StringBuilder body = new StringBuilder(parsed.text().length() + tags.length() + 1);
        body.append(parsed.text());
        if (!tags.isEmpty()) {
            if (body.length() > 0) body.append('\n');
            body.append(tags);
        }

        String msgId = extractRawHeader(rawBytes, "Message-ID");
        String inReplyTo = extractRawHeader(rawBytes, "In-Reply-To");
        String references = extractRawHeader(rawBytes, "References");

        boolean seen = true;
        boolean starred = false;
        if (mailbox != null) {
            Properties flags = MailStorageEngine.getMailFlags(mailbox, id);
            seen = Boolean.parseBoolean(flags.getProperty("seen", "true"));
            starred = Boolean.parseBoolean(flags.getProperty("starred", "false"));
        }

        StringBuilder sb = new StringBuilder(body.length() + 640);
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
        sb.append("\",\"messageId\":\"");
        appendEscaped(sb, msgId);
        sb.append("\",\"inReplyTo\":\"");
        appendEscaped(sb, inReplyTo);
        sb.append("\",\"references\":\"");
        appendEscaped(sb, references);
        sb.append("\",\"seen\":");
        sb.append(seen);
        sb.append(",\"starred\":");
        sb.append(starred);
        sb.append(",\"body\":\"");
        appendEscaped(sb, body);
        sb.append("\"}");
        return sb.toString();
    }

    private static String extractRawHeader(byte[] raw, String headerName) {
        if (raw == null || raw.length == 0) return "";
        String prefix = headerName.toLowerCase(Locale.ROOT) + ":";
        int limit = Math.min(raw.length, 16384);
        String head = new String(raw, 0, limit, StandardCharsets.UTF_8).replaceAll("\r?\n(?=[ \t])", " ");
        for (String line : head.split("\r?\n")) {
            if (line.isEmpty()) break;
            if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

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
            } catch (IllegalArgumentException ignored) {}
        }
        return map;
    }

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
                    } catch (IllegalArgumentException ignored) {}
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
