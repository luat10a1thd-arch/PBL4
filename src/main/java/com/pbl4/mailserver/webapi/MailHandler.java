// FILE: src/main/java/com/pbl4/mailserver/webapi/MailHandler.java
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MailHandler implements HttpHandler {

    /** Số người nhận tối đa trong một yêu cầu /send (chặn gửi hàng loạt qua web). */
    static final int MAX_RECIPIENTS_PER_REQUEST = 100;

    /** Tìm kiếm: tối đa 50 kết quả, từ khóa tối đa 100 ký tự, chỉ quét tóm tắt (không giải mã cả hòm thư). */
    static final int SEARCH_MAX_RESULTS = 50;
    static final int SEARCH_MAX_QUERY_CHARS = 100;

    /** Số thư mới nhất của mỗi hòm được quét khi tìm kiếm / lọc thư gắn sao. */
    private static final int SCAN_LIMIT = 500;

    /** Phần trích dẫn thư gốc khi trả lời / chuyển tiếp tối đa bấy nhiêu ký tự. */
    private static final int MAX_QUOTE_CHARS = 64 * 1024;

    private static final String TAG_OPEN = "[ATTACHMENT:";

    private static final String MSG_BAD_RCPT = "Địa chỉ email người nhận không hợp lệ!";

    // local-part không chứa khoảng trắng / ký tự đặc biệt; domain gồm chữ, số, dấu chấm, gạch nối (cho phép Unicode)
    private static final Pattern RCPT_PATTERN =
            Pattern.compile("^[^\\s@<>\"(),:;\\[\\]\\\\]{1,64}@[\\p{L}\\p{N}][\\p{L}\\p{N}.-]{0,253}$");

    // Dùng để rút các địa chỉ từ giá trị header From/To (có thể kèm tên hiển thị)
    private static final Pattern ADDR_FIND =
            Pattern.compile("[^\\s@<>\"(),:;\\[\\]\\\\]{1,64}@[\\p{L}\\p{N}][\\p{L}\\p{N}.-]{0,253}");

    private static final Pattern MSG_ID_PATTERN =
            Pattern.compile("<[^<>\\s]{1,200}>");

    // id file thư an toàn: chỉ ký tự tên file, không "..", bắt buộc đuôi .enc (chặn đọc/xóa blocked_senders.txt, meta, ...)
    private static final Pattern SAFE_MAIL_ID = Pattern.compile("[A-Za-z0-9_.-]{1,100}");

    static boolean isSafeId(String id) {
        return id != null && !id.contains("..") && id.endsWith(".enc") && SAFE_MAIL_ID.matcher(id).matches();
    }

    static List<String> extractMessageIds(String headerValue) {
        List<String> out = new java.util.ArrayList<>();
        if (headerValue == null) return out;
        java.util.regex.Matcher m = MSG_ID_PATTERN.matcher(headerValue);
        while (m.find()) out.add(m.group());
        return out;
    }

    static String buildReferences(String oldReferences, String parentId) {
        List<String> ids = extractMessageIds(oldReferences);
        if (ids.size() > 9) ids = ids.subList(ids.size() - 9, ids.size());
        List<String> all = new java.util.ArrayList<>(ids);
        all.remove(parentId);
        all.add(parentId);
        return String.join(" ", all);
    }

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

        // 2. XÁC THỰC USER CHO CÁC API THÔNG THƯỜNG (người dùng luôn lấy từ phiên, không tin tham số client)
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        String token = (authHeader != null && authHeader.startsWith("Bearer "))
                ? authHeader.substring(7) : null;
        String authedUser = SessionManager.validate(token);

        if (authedUser == null) {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Chưa đăng nhập hoặc phiên hết hạn!\"}");
            return;
        }

        String seg = path.substring(path.lastIndexOf('/') + 1);
        boolean get = "GET".equalsIgnoreCase(method);
        boolean post = "POST".equalsIgnoreCase(method);
        boolean del = "DELETE".equalsIgnoreCase(method);

        if (post && seg.equals("stream-ticket")) {
            handleStreamTicket(exchange, authedUser, token);
        } else if (post && seg.equals("send")) {
            handleSendMail(exchange, authedUser);
        } else if (post && seg.equals("reply")) {
            handleReply(exchange, authedUser);
        } else if (post && seg.equals("save-draft")) {
            handleSaveDraft(exchange, authedUser);
        } else if ((post || del) && seg.equals("delete")) {
            handleDeleteMail(exchange, authedUser);
        } else if (post && seg.equals("delete-forever")) {
            handleDeleteForever(exchange, authedUser);
        } else if (post && seg.equals("trash")) {
            handleMoveToTrash(exchange, authedUser);
        } else if (post && seg.equals("restore")) {
            handleRestore(exchange, authedUser);
        } else if (post && seg.equals("empty")) {
            handleEmptyFolder(exchange, authedUser);
        } else if (post && seg.equals("flags")) {
            handleUpdateFlags(exchange, authedUser);
        } else if (post && seg.equals("report-spam")) {
            handleReportSpam(exchange, authedUser);
        } else if (post && seg.equals("unspam")) {
            handleUnspam(exchange, authedUser);
        } else if (seg.equals("blocked") && (get || post || del)) {
            handleBlocked(exchange, authedUser, get);
        } else if (get && seg.equals("inbox")) {
            handleGetInbox(exchange, authedUser);
        } else if (get && seg.equals("sent")) {
            handleGetSent(exchange, authedUser);
        } else if (get && (seg.equals("drafts") || seg.equals("spam") || seg.equals("trash"))) {
            Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
            handleGetMailBoxPaged(exchange, mailboxFor(authedUser, seg), seg, q);
        } else if (get && seg.equals("starred")) {
            handleGetStarred(exchange, authedUser);
        } else if (get && seg.equals("search")) {
            handleSearch(exchange, authedUser);
        } else if (get && seg.equals("detail")) {
            handleGetMailDetail(exchange, authedUser);
        } else if (get && seg.equals("storage-usage")) {
            handleGetStorageUsage(exchange, authedUser);
        } else if (get && seg.equals("quota")) {
            handleGetQuota(exchange, authedUser);
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
    // GỬI THƯ / TRẢ LỜI / CHUYỂN TIẾP / NHÁP
    // ------------------------------------------------------------------------------------------------

    private void handleSendMail(HttpExchange exchange, String authUser) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, (int) Math.min(Integer.MAX_VALUE, ServerConfig.MAX_BODY_SEND_BYTES),
                ServerConfig.BODY_READ_TOTAL_TIMEOUT_SEND_MS);
        if (body == null) return;

        Map<String, String> form = parseForm(body);
        String rawTo = form.get("to");
        String subject = form.get("subject");
        String content = form.get("body");
        String replyId = form.get("reply_id");
        String replyFolder = form.get("reply_folder");

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
            String inReplyTo = null;
            String references = null;

            if (replyId != null && !replyId.isBlank() && isSafeId(replyId.trim())) {
                MailStorageEngine.StoredMail orig =
                        MailStorageEngine.getSingleMail(mailboxFor(authUser, replyFolder), replyId.trim());
                if (orig != null && orig.rawBytes() != null) {
                    List<String> ids = extractMessageIds(MimeParser.rawHeader(orig.rawBytes(), "message-id"));
                    if (!ids.isEmpty()) {
                        inReplyTo = ids.get(0);
                        references = buildReferences(MimeParser.rawHeader(orig.rawBytes(), "references"), inReplyTo);
                    }
                }
            }

            byte[] raw = MimeBuilder.fromWebForm(fromAddress, String.join(", ", rcptList), cleanSubject,
                    content == null ? "" : content, inReplyTo, references);
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

    /** POST /reply {id, folder, mode: reply|forward, to?, body}. Đi qua MailService.submit (From ép = người đăng nhập). */
    private void handleReply(HttpExchange exchange, String authUser) throws IOException {
        String reqBody = ApiServer.readBodyOrReply(exchange, (int) Math.min(Integer.MAX_VALUE, ServerConfig.MAX_BODY_SEND_BYTES),
                ServerConfig.BODY_READ_TOTAL_TIMEOUT_SEND_MS);
        if (reqBody == null) return;

        Map<String, String> form = parseForm(reqBody);
        String id = form.get("id");
        String mode = form.getOrDefault("mode", "reply").trim().toLowerCase(Locale.ROOT);
        String toRaw = form.get("to");
        String userText = form.get("body") == null ? "" : form.get("body");

        if (!isSafeId(id == null ? null : id.trim())) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu hoặc sai ID thư!\"}");
            return;
        }
        id = id.trim();
        boolean forward = mode.equals("forward");
        if (!forward && !mode.equals("reply")) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Chế độ không hợp lệ!\"}");
            return;
        }
        String box = strictBox(authUser, form.get("folder"));
        if (box == null) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thư mục không hợp lệ!\"}");
            return;
        }

        MailStorageEngine.StoredMail orig = MailStorageEngine.getSingleMail(box, id);
        if (orig == null || orig.rawBytes() == null) {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy thư!\"}");
            return;
        }

        ParsedMail pm = MimeParser.parse(orig.rawBytes());
        String me = MailStorageEngine.extractCleanEmail(authUser);

        List<String> rcptList;
        if (toRaw != null && !toRaw.isBlank()) {
            rcptList = parseRecipients(toRaw);
        } else if (forward) {
            rcptList = null;
        } else {
            rcptList = defaultReplyTargets(pm, me);
        }
        if (rcptList == null || rcptList.isEmpty()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"" + escapeJson(MSG_BAD_RCPT) + "\"}");
            return;
        }
        if (rcptList.size() > MAX_RECIPIENTS_PER_REQUEST) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Quá nhiều người nhận trong một thư!\"}");
            return;
        }

        String subject = withPrefix(pm.subject(), forward);

        // Tách phần chữ và các thẻ đính kèm do client gửi: phần trích dẫn phải nằm TRƯỚC thẻ đầu tiên
        int ti = userText.indexOf(TAG_OPEN);
        String head = ti < 0 ? userText : userText.substring(0, ti);
        String tail = ti < 0 ? "" : userText.substring(ti);

        String quote = forward ? quoteForward(pm) : quoteReply(pm);

        long maxBody = ServerConfig.MAX_BODY_SEND_BYTES;
        String fwdTags = "";
        if (forward) {
            long budget = (maxBody / 10) * 9 - head.length() - quote.length() - tail.length() - 256;
            List<AttachmentInfo> keep = new ArrayList<>();
            long used = 0;
            boolean dropped = false;
            for (AttachmentInfo a : pm.attachments()) {
                if (a.data() == null || a.data().length == 0) continue;
                long enc = ((a.data().length + 2L) / 3) * 4 + 40 + (a.name() == null ? 10 : a.name().length());
                if (budget > 0 && used + enc <= budget) {
                    keep.add(a);
                    used += enc;
                } else {
                    dropped = true;
                }
            }
            if (dropped) {
                quote = quote + "\n[Một số tệp đính kèm không được chuyển tiếp vì vượt giới hạn dung lượng]\n";
            }
            fwdTags = attachmentsToTags(keep);
        }

        StringBuilder full = new StringBuilder(head.length() + quote.length() + fwdTags.length() + tail.length() + 4);
        full.append(head).append(quote);
        if (!fwdTags.isEmpty()) full.append('\n').append(fwdTags);
        if (!tail.isEmpty()) full.append('\n').append(tail);

        if (full.length() > maxBody) {
            sendResponse(exchange, 413, "{\"success\": false, \"message\": \"Thư quá lớn!\"}");
            return;
        }

        String inReplyTo = null;
        String references = null;
        if (!forward) {
            List<String> ids = extractMessageIds(MimeParser.rawHeader(orig.rawBytes(), "message-id"));
            if (!ids.isEmpty()) {
                inReplyTo = ids.get(0);
                references = buildReferences(MimeParser.rawHeader(orig.rawBytes(), "references"), inReplyTo);
            }
        }

        String fromAddress = fromAddress(authUser);
        MailService.DeliveryResult result;
        try {
            byte[] raw = MimeBuilder.fromWebForm(fromAddress, String.join(", ", rcptList), subject,
                    full.toString(), inReplyTo, references);
            result = MailService.submit(authUser, fromAddress, new LinkedHashSet<>(rcptList), raw);
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

    /** Người nhận mặc định khi trả lời: người gửi thư gốc (trừ chính mình); nếu thư gốc do mình gửi thì dùng To. */
    static List<String> defaultReplyTargets(ParsedMail pm, String me) {
        List<String> targets = extractAddresses(pm.from());
        targets.remove(me);
        if (targets.isEmpty()) {
            targets = extractAddresses(pm.to());
            targets.remove(me);
        }
        List<String> ok = new ArrayList<>();
        for (String t : targets) {
            if (t.length() <= 254 && RCPT_PATTERN.matcher(t).matches()) ok.add(t);
            if (ok.size() >= MAX_RECIPIENTS_PER_REQUEST) break;
        }
        return ok;
    }

    /** Rút mọi địa chỉ email (chữ thường, không trùng) từ giá trị header; chịu được tên hiển thị. */
    static List<String> extractAddresses(String header) {
        List<String> out = new ArrayList<>();
        if (header == null || header.isEmpty()) return out;
        Matcher m = ADDR_FIND.matcher(header);
        while (m.find()) {
            String a = m.group();
            while (a.endsWith(".") || a.endsWith("-")) a = a.substring(0, a.length() - 1);
            a = a.toLowerCase(Locale.ROOT);
            if (a.indexOf('@') > 0 && !out.contains(a)) out.add(a);
        }
        return out;
    }

    /** Thêm "Re: " / "Fwd: " nếu chưa có; loại CR/LF. */
    static String withPrefix(String subject, boolean forward) {
        String s = subject == null ? "" : subject.replace('\r', ' ').replace('\n', ' ').trim();
        if (forward) {
            if (s.regionMatches(true, 0, "fwd:", 0, 4) || s.regionMatches(true, 0, "fw:", 0, 3)) return s;
            return "Fwd: " + s;
        }
        if (s.regionMatches(true, 0, "re:", 0, 3)) return s;
        return "Re: " + s;
    }

    /** Vô hiệu hóa thẻ đính kèm trong nội dung do thư gốc cung cấp (chống chèn đính kèm giả khi dựng thư). */
    private static String neutralize(String s) {
        if (s == null) return "";
        return s.replace(TAG_OPEN, "[ATTACHMENT :");
    }

    private static String limitQuote(String s) {
        if (s.length() <= MAX_QUOTE_CHARS) return s;
        int cut = MAX_QUOTE_CHARS;
        if (Character.isHighSurrogate(s.charAt(cut - 1))) cut--;
        return s.substring(0, cut) + "\n[...]";
    }

    private static String quoteReply(ParsedMail pm) {
        StringBuilder sb = new StringBuilder("\n\n");
        sb.append("Vào ").append(neutralize(pm.date())).append(", ")
          .append(neutralize(pm.from())).append(" đã viết:\n");
        String t = limitQuote(neutralize(pm.text()));
        for (String line : t.split("\n", -1)) {
            sb.append("> ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static String quoteForward(ParsedMail pm) {
        StringBuilder sb = new StringBuilder("\n\n---------- Thư được chuyển tiếp ----------\n");
        sb.append("Từ: ").append(neutralize(pm.from())).append('\n');
        sb.append("Đến: ").append(neutralize(pm.to())).append('\n');
        sb.append("Chủ đề: ").append(neutralize(pm.subject())).append('\n');
        sb.append("Ngày: ").append(neutralize(pm.date())).append("\n\n");
        sb.append(limitQuote(neutralize(pm.text()))).append('\n');
        return sb.toString();
    }

    /** POST /save-draft {id?, to, subject, body} -> {id}. */
    private void handleSaveDraft(HttpExchange exchange, String authUser) throws IOException {
        String reqBody = ApiServer.readBodyOrReply(exchange, (int) Math.min(Integer.MAX_VALUE, ServerConfig.MAX_BODY_SEND_BYTES),
                ServerConfig.BODY_READ_TOTAL_TIMEOUT_SEND_MS);
        if (reqBody == null) return;

        Map<String, String> form = parseForm(reqBody);
        String rawTo = form.get("to");
        String subject = form.get("subject");
        String content = form.get("body");
        String oldId = form.get("id");

        if (oldId != null && !oldId.isBlank() && !isSafeId(oldId.trim())) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"ID thư nháp không hợp lệ!\"}");
            return;
        }
        if ((rawTo == null || rawTo.trim().isEmpty())
                && (subject == null || subject.trim().isEmpty())
                && (content == null || content.trim().isEmpty())) {
            sendResponse(exchange, 200, "{\"success\": true, \"id\": \"\", \"message\": \"Không có nội dung để lưu nháp.\"}");
            return;
        }

        String cleanSubject = subject == null ? "" : subject.replace('\r', ' ').replace('\n', ' ');
        String newId;
        try {
            byte[] raw = MimeBuilder.fromWebForm(fromAddress(authUser), rawTo == null ? "" : rawTo.trim(), cleanSubject,
                    content == null ? "" : content);
            newId = MailStorageEngine.saveDraft(authUser, oldId == null || oldId.isBlank() ? null : oldId.trim(), raw);
        } catch (RuntimeException e) {
            System.err.println("[MAIL API] Lỗi khi lưu nháp của " + authUser + ": " + e);
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Lỗi hệ thống khi lưu thư nháp!\"}");
            return;
        }

        if (newId != null) {
            sendResponse(exchange, 200, "{\"success\": true, \"id\": \"" + escapeJson(newId) + "\", \"message\": \"Đã lưu vào thư nháp!\"}");
            return;
        }
        long limit = ServerConfig.MAX_MAILBOX_QUOTA_MB * 1024L * 1024L;
        if (MailStorageEngine.getUsedQuotaBytes(authUser) >= limit) {
            sendResponse(exchange, 507, "{\"success\": false, \"message\": \"Hòm thư đã đầy, không thể lưu nháp!\"}");
        } else {
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Không thể lưu thư nháp!\"}");
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
    // DANH SÁCH / CHI TIẾT / TÌM KIẾM
    // ------------------------------------------------------------------------------------------------

    private void handleGetInbox(HttpExchange exchange, String username) throws IOException {
        Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
        String folder = q.get("folder");
        if ("starred".equalsIgnoreCase(folder)) {
            handleGetStarred(exchange, username);
            return;
        }
        handleGetMailBoxPaged(exchange, mailboxFor(username, folder), labelFor(folder), q);
    }

    private void handleGetSent(HttpExchange exchange, String username) throws IOException {
        Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
        handleGetMailBoxPaged(exchange, username + "_sent", "sent", q);
    }

    private void handleGetMailBoxPaged(HttpExchange exchange, String targetMailbox, String label,
                                       Map<String, String> q) throws IOException {
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

        sendResponse(exchange, 200, summariesToJson(targetMailbox, label, mailPage.mails));
    }

    /** GET /starred: thư gắn sao của inbox/sent/drafts/spam của CHÍNH người dùng, mới nhất trước. */
    private void handleGetStarred(HttpExchange exchange, String username) throws IOException {
        Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
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

        String[][] boxes = {
                {username, "inbox"},
                {username + "_sent", "sent"},
                {username + "_drafts", "drafts"},
                {username + "_spam", "spam"}
        };

        List<String[]> keys = new ArrayList<>();                       // {id, box, label}
        Map<String, MailStorageEngine.MailSummary> byKey = new HashMap<>();
        for (String[] b : boxes) {
            MailStorageEngine.MailPage p = MailStorageEngine.listMailSummaries(b[0], 1, SCAN_LIMIT);
            for (MailStorageEngine.MailSummary m : p.mails) {
                Properties f = MailStorageEngine.getMailFlags(b[0], m.id);
                if (Boolean.parseBoolean(f.getProperty("starred", "false"))) {
                    keys.add(new String[]{m.id, b[0], b[1]});
                    byKey.put(b[0] + "|" + m.id, m);
                }
            }
        }
        keys.sort((x, y) -> y[0].compareTo(x[0]));

        int total = keys.size();
        int start = (int) Math.min((long) (page - 1) * limit, total);
        int end = Math.min(start + limit, total);

        exchange.getResponseHeaders().set("X-Total-Count", String.valueOf(total));
        exchange.getResponseHeaders().set("Access-Control-Expose-Headers", "X-Total-Count");

        if (start >= end) {
            sendResponse(exchange, 200, "[]");
            return;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = start; i < end; i++) {
            String[] k = keys.get(i);
            if (i > start) sb.append(',');
            appendSummaryJson(sb, k[1], k[2], byKey.get(k[1] + "|" + k[0]));
        }
        sb.append(']');
        sendResponse(exchange, 200, sb.toString());
    }

    /** GET /search?q=&folder= : chỉ khớp người gửi, chủ đề, ngày trên tóm tắt; tối đa 50 kết quả. */
    private void handleSearch(HttpExchange exchange, String username) throws IOException {
        Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
        String keyword = q.getOrDefault("q", "").trim();
        if (keyword.length() > SEARCH_MAX_QUERY_CHARS) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Từ khóa tìm kiếm quá dài!\"}");
            return;
        }
        String folder = q.getOrDefault("folder", "inbox");
        String box = strictBox(username, folder);
        if (box == null) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thư mục không hợp lệ!\"}");
            return;
        }

        List<MailStorageEngine.MailSummary> matched = new ArrayList<>();
        if (!keyword.isEmpty()) {
            MailStorageEngine.MailPage all = MailStorageEngine.listMailSummaries(box, 1, SCAN_LIMIT);
            matched = filterSummaries(all.mails, keyword, SEARCH_MAX_RESULTS);
        }

        exchange.getResponseHeaders().set("X-Total-Count", String.valueOf(matched.size()));
        exchange.getResponseHeaders().set("Access-Control-Expose-Headers", "X-Total-Count");
        sendResponse(exchange, 200, summariesToJson(box, labelFor(folder), matched));
    }

    /** Lọc theo chuỗi con (không phân biệt hoa thường, KHÔNG dùng regex nên ký tự đặc biệt được hiểu nguyên văn). */
    static List<MailStorageEngine.MailSummary> filterSummaries(List<MailStorageEngine.MailSummary> all,
                                                               String keyword, int max) {
        List<MailStorageEngine.MailSummary> out = new ArrayList<>();
        if (all == null || keyword == null) return out;
        String k = keyword.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty()) return out;
        for (MailStorageEngine.MailSummary m : all) {
            if (containsIgnoreCase(m.sender, k) || containsIgnoreCase(m.subject, k) || containsIgnoreCase(m.timestamp, k)) {
                out.add(m);
                if (out.size() >= max) break;
            }
        }
        return out;
    }

    private static boolean containsIgnoreCase(String hay, String lowerNeedle) {
        return hay != null && hay.toLowerCase(Locale.ROOT).contains(lowerNeedle);
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
        // Quota dùng chung cho inbox + sent + spam + drafts + trash (khớp MailStorageEngine)
        long totalUsedBytes = MailStorageEngine.getUsedQuotaBytes(username);

        long quotaMb = ServerConfig.MAX_MAILBOX_QUOTA_MB;
        long quotaBytes = quotaMb * 1024L * 1024L;
        double usedMb = totalUsedBytes / (1024.0 * 1024.0);

        String json = String.format(Locale.US,
            "{\"success\": true, \"usedBytes\": %d, \"usedMb\": %.2f, \"quotaMb\": %d, \"quotaBytes\": %d}",
            totalUsedBytes, usedMb, quotaMb, quotaBytes
        );
        sendResponse(exchange, 200, json);
    }

    /** GET /quota -> {usedBytes, limitBytes, percent}. */
    private void handleGetQuota(HttpExchange exchange, String username) throws IOException {
        long used = MailStorageEngine.getUsedQuotaBytes(username);
        long limit = ServerConfig.MAX_MAILBOX_QUOTA_MB * 1024L * 1024L;
        int percent = limit > 0 ? (int) Math.min(100, Math.round((used * 100.0) / limit)) : 0;
        sendResponse(exchange, 200, "{\"success\": true, \"usedBytes\": " + used
                + ", \"limitBytes\": " + limit + ", \"percent\": " + percent + "}");
    }

    // ------------------------------------------------------------------------------------------------
    // XÓA / THÙNG RÁC / KHÔI PHỤC / CỜ
    // ------------------------------------------------------------------------------------------------

    /** Giữ nguyên route cũ /delete: từ nay "xóa" = chuyển vào Thùng rác; trong trash/drafts thì xóa hẳn. */
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
        fileId = fileId.trim();
        if (!isSafeId(fileId)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"ID thư không hợp lệ!\"}");
            return;
        }
        String box = strictBox(username, folder);
        if (box == null) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thư mục không hợp lệ!\"}");
            return;
        }

        if (box.equals(username + "_trash") || box.equals(username + "_drafts")) {
            MailStorageEngine.deleteMailsByIds(box, Collections.singletonList(fileId));
        } else {
            String moved = MailStorageEngine.moveEmail(box, username + "_trash", fileId);
            if (moved == null) {
                sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy thư!\"}");
                return;
            }
        }
        sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã xóa thư thành công!\"}");
    }

    /** POST /trash {id, folder}: chuyển thư vào Thùng rác. */
    private void handleMoveToTrash(HttpExchange exchange, String username) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;
        Map<String, String> form = parseForm(body);
        String fileId = form.get("id") == null ? null : form.get("id").trim();
        if (fileId == null || !isSafeId(fileId)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu hoặc sai ID thư!\"}");
            return;
        }
        String box = strictBox(username, form.get("folder"));
        String trash = username + "_trash";
        if (box == null || box.equals(trash)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thư mục không hợp lệ!\"}");
            return;
        }
        String newId = MailStorageEngine.moveEmail(box, trash, fileId);
        if (newId == null) {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy thư!\"}");
            return;
        }
        sendResponse(exchange, 200, "{\"success\": true, \"id\": \"" + escapeJson(newId)
                + "\", \"message\": \"Đã chuyển thư vào Thùng rác!\"}");
    }

    /** POST /restore {id, target?}: đưa thư trong Thùng rác về inbox (hoặc sent/spam/drafts nếu có target). */
    private void handleRestore(HttpExchange exchange, String username) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;
        Map<String, String> form = parseForm(body);
        String fileId = form.get("id") == null ? null : form.get("id").trim();
        if (fileId == null || !isSafeId(fileId)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu hoặc sai ID thư!\"}");
            return;
        }
        String trash = username + "_trash";
        String dest = strictBox(username, form.get("target"));
        if (dest == null || dest.equals(trash)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thư mục đích không hợp lệ!\"}");
            return;
        }
        String newId = MailStorageEngine.moveEmail(trash, dest, fileId);
        if (newId == null) {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy thư trong Thùng rác!\"}");
            return;
        }
        sendResponse(exchange, 200, "{\"success\": true, \"id\": \"" + escapeJson(newId)
                + "\", \"message\": \"Đã khôi phục thư!\"}");
    }

    /** POST /delete-forever {id, folder?}: chỉ xóa hẳn trong _trash hoặc _drafts của chính mình. */
    private void handleDeleteForever(HttpExchange exchange, String username) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;
        Map<String, String> form = parseForm(body);
        String fileId = form.get("id") == null ? null : form.get("id").trim();
        if (fileId == null || !isSafeId(fileId)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu hoặc sai ID thư!\"}");
            return;
        }
        String trash = username + "_trash";
        String drafts = username + "_drafts";

        String target = null;
        String folder = form.get("folder");
        if (folder != null && !folder.isBlank()) {
            String box = strictBox(username, folder);
            if (box == null || !(box.equals(trash) || box.equals(drafts))) {
                sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Chỉ được xóa vĩnh viễn trong Thùng rác hoặc Thư nháp!\"}");
                return;
            }
            if (MailStorageEngine.getSingleMail(box, fileId) != null) target = box;
        } else if (MailStorageEngine.getSingleMail(trash, fileId) != null) {
            target = trash;
        } else if (MailStorageEngine.getSingleMail(drafts, fileId) != null) {
            target = drafts;
        }

        if (target == null) {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy thư!\"}");
            return;
        }
        MailStorageEngine.deleteMailsByIds(target, Collections.singletonList(fileId));
        sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã xóa vĩnh viễn thư!\"}");
    }

    /** POST /empty {folder: trash|spam}: dọn sạch hòm của chính mình. */
    private void handleEmptyFolder(HttpExchange exchange, String username) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;
        Map<String, String> form = parseForm(body);
        String folder = form.get("folder") == null ? "" : form.get("folder").trim().toLowerCase(Locale.ROOT);
        if (!folder.equals("trash") && !folder.equals("spam")) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Chỉ được dọn Thùng rác hoặc Thư rác!\"}");
            return;
        }
        String box = mailboxFor(username, folder);

        int first = -1;
        int prev = Integer.MAX_VALUE;
        for (int round = 0; round < 100; round++) {
            MailStorageEngine.MailPage p = MailStorageEngine.listMailSummaries(box, 1, 200);
            if (first < 0) first = p.total;
            if (p.mails.isEmpty() || p.total >= prev) break; // hết thư hoặc không tiến triển
            prev = p.total;
            List<String> ids = new ArrayList<>(p.mails.size());
            for (MailStorageEngine.MailSummary m : p.mails) ids.add(m.id);
            MailStorageEngine.deleteMailsByIds(box, ids);
        }
        int remaining = MailStorageEngine.listMailSummaries(box, 1, 1).total;
        int deleted = first < 0 ? 0 : Math.max(0, first - remaining);
        sendResponse(exchange, 200, "{\"success\": true, \"count\": " + deleted
                + ", \"message\": \"Đã dọn sạch " + deleted + " thư!\"}");
    }

    /** POST /flags {id, folder, seen?, starred?}. */
    private void handleUpdateFlags(HttpExchange exchange, String username) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;
        Map<String, String> form = parseForm(body);
        String fileId = form.get("id") == null ? null : form.get("id").trim();
        if (fileId == null || !isSafeId(fileId)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu hoặc sai ID thư!\"}");
            return;
        }
        String box = strictBox(username, form.get("folder"));
        if (box == null) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thư mục không hợp lệ!\"}");
            return;
        }
        Boolean seen;
        Boolean starred;
        try {
            seen = parseBool(form.get("seen"));
            starred = parseBool(form.get("starred"));
        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Giá trị trạng thái không hợp lệ!\"}");
            return;
        }
        if (seen == null && starred == null) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu trạng thái cần cập nhật!\"}");
            return;
        }
        if (MailStorageEngine.updateMailFlags(box, fileId, seen, starred)) {
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã cập nhật trạng thái thư!\"}");
        } else {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy thư!\"}");
        }
    }

    private static Boolean parseBool(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (t.equalsIgnoreCase("false")) return Boolean.FALSE;
        throw new IllegalArgumentException("bool");
    }

    // ------------------------------------------------------------------------------------------------
    // THƯ RÁC / DANH SÁCH CHẶN (chỉ tác động hòm và danh sách chặn của CHÍNH người đăng nhập)
    // ------------------------------------------------------------------------------------------------

    /** POST /report-spam {id, folder}: chuyển thư của mình vào _spam và chặn người gửi (danh sách của mình). */
    private void handleReportSpam(HttpExchange exchange, String username) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;
        Map<String, String> form = parseForm(body);
        String fileId = form.get("id") == null ? null : form.get("id").trim();
        if (fileId == null || !isSafeId(fileId)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu hoặc sai ID thư!\"}");
            return;
        }
        String src = strictBox(username, form.get("folder"));
        if (src == null || !(src.equals(username) || src.equals(username + "_trash"))) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Không thể báo cáo thư ở mục này!\"}");
            return;
        }

        MailStorageEngine.StoredMail mail = MailStorageEngine.getSingleMail(src, fileId);
        if (mail == null || mail.rawBytes() == null) {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy thư!\"}");
            return;
        }
        String sender = MailStorageEngine.extractCleanEmail(MimeParser.rawHeader(mail.rawBytes(), "from"));
        String me = MailStorageEngine.extractCleanEmail(username);

        String newId = MailStorageEngine.moveEmail(src, username + "_spam", fileId);
        if (newId == null) {
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Không thể chuyển thư vào Thư rác!\"}");
            return;
        }

        boolean blocked = false;
        if (!sender.isEmpty() && !sender.equals(me)) {
            blocked = MailStorageEngine.addBlockedSender(username, sender);
        }
        String msg = blocked
                ? "Đã chuyển vào Thư rác và chặn người gửi!"
                : "Đã chuyển thư vào mục Thư rác!";
        sendResponse(exchange, 200, "{\"success\": true, \"id\": \"" + escapeJson(newId)
                + "\", \"blockedSender\": \"" + escapeJson(blocked ? sender : "")
                + "\", \"message\": \"" + escapeJson(msg) + "\"}");
    }

    /** POST /unspam {id}: đưa thư về inbox và bỏ chặn người gửi (danh sách của mình). */
    private void handleUnspam(HttpExchange exchange, String username) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;
        Map<String, String> form = parseForm(body);
        String fileId = form.get("id") == null ? null : form.get("id").trim();
        if (fileId == null || !isSafeId(fileId)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu hoặc sai ID thư!\"}");
            return;
        }
        String spam = username + "_spam";
        MailStorageEngine.StoredMail mail = MailStorageEngine.getSingleMail(spam, fileId);
        if (mail == null || mail.rawBytes() == null) {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy thư trong mục Thư rác!\"}");
            return;
        }
        String sender = MailStorageEngine.extractCleanEmail(MimeParser.rawHeader(mail.rawBytes(), "from"));

        String newId = MailStorageEngine.moveEmail(spam, username, fileId);
        if (newId == null) {
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Không thể chuyển thư về Hộp thư đến!\"}");
            return;
        }
        if (!sender.isEmpty()) {
            MailStorageEngine.removeBlockedSender(username, sender);
        }
        sendResponse(exchange, 200, "{\"success\": true, \"id\": \"" + escapeJson(newId)
                + "\", \"message\": \"Đã chuyển thư về Hộp thư đến!\"}");
    }

    /**
     * GET /blocked -> {"success":true,"blocked":[...]}; POST /blocked {sender}; DELETE /blocked {sender}
     * (sender có thể nằm ở query hoặc body). Luôn là danh sách của người đăng nhập.
     */
    private void handleBlocked(HttpExchange exchange, String username, boolean isGet) throws IOException {
        if (isGet) {
            List<String> list = MailStorageEngine.listBlockedSenders(username);
            StringBuilder sb = new StringBuilder("{\"success\": true, \"blocked\": [");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(escapeJson(list.get(i))).append('"');
            }
            sb.append("]}");
            sendResponse(exchange, 200, sb.toString());
            return;
        }

        boolean remove = "DELETE".equalsIgnoreCase(exchange.getRequestMethod());
        Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
        String senderRaw = q.get("sender");
        if (senderRaw == null) {
            String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
            if (body == null) return;
            senderRaw = parseForm(body).get("sender");
        }
        String sender = MailStorageEngine.extractCleanEmail(senderRaw);
        if (sender.isEmpty()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Địa chỉ không hợp lệ!\"}");
            return;
        }
        if (!remove && sender.equals(MailStorageEngine.extractCleanEmail(username))) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Không thể chặn chính mình!\"}");
            return;
        }
        boolean ok = remove
                ? MailStorageEngine.removeBlockedSender(username, sender)
                : MailStorageEngine.addBlockedSender(username, sender);
        if (ok) {
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \""
                    + (remove ? "Đã bỏ chặn " : "Đã chặn ") + escapeJson(sender) + "!\"}");
        } else {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Không thể cập nhật danh sách chặn!\"}");
        }
    }

    // ------------------------------------------------------------------------------------------------
    // HÒM THƯ
    // ------------------------------------------------------------------------------------------------

    /**
     * folder=sent -> u_sent, spam -> u_spam, drafts -> u_drafts, trash -> u_trash,
     * còn lại (inbox, rỗng, giá trị lạ) -> u. Tên hòm luôn bắt đầu bằng tên người dùng trong phiên.
     */
    static String mailboxFor(String username, String folder) {
        if ("sent".equalsIgnoreCase(folder)) return username + "_sent";
        if ("spam".equalsIgnoreCase(folder)) return username + "_spam";
        if ("drafts".equalsIgnoreCase(folder)) return username + "_drafts";
        if ("trash".equalsIgnoreCase(folder)) return username + "_trash";
        return username;
    }

    /** Như mailboxFor nhưng NGHIÊM: rỗng/inbox -> u; giá trị lạ -> null (dùng cho thao tác ghi). */
    static String strictBox(String username, String folder) {
        if (folder == null || folder.isBlank()) return username;
        String f = folder.trim().toLowerCase(Locale.ROOT);
        switch (f) {
            case "inbox": return username;
            case "sent":
            case "spam":
            case "drafts":
            case "trash":
                return mailboxFor(username, f);
            default:
                return null;
        }
    }

    private static String labelFor(String folder) {
        if ("sent".equalsIgnoreCase(folder)) return "sent";
        if ("spam".equalsIgnoreCase(folder)) return "spam";
        if ("drafts".equalsIgnoreCase(folder)) return "drafts";
        if ("trash".equalsIgnoreCase(folder)) return "trash";
        return "inbox";
    }

    // ------------------------------------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------------------------------------

    private static String summariesToJson(String mailbox, String label, List<MailStorageEngine.MailSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < summaries.size(); i++) {
            if (i > 0) sb.append(',');
            appendSummaryJson(sb, mailbox, label, summaries.get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    /** Cùng định dạng cũ (id, sender, recipient, subject, timestamp) + seen, starred, folder. */
    private static void appendSummaryJson(StringBuilder sb, String mailbox, String label,
                                          MailStorageEngine.MailSummary summary) {
        Properties flags = MailStorageEngine.getMailFlags(mailbox, summary.id);
        boolean defaultSeen = label.equals("sent") || label.equals("drafts");
        boolean seen = Boolean.parseBoolean(flags.getProperty("seen", String.valueOf(defaultSeen)));
        boolean starred = Boolean.parseBoolean(flags.getProperty("starred", "false"));
        sb.append("{")
          .append("\"id\":\"").append(escapeJson(summary.id)).append("\",")
          .append("\"sender\":\"").append(escapeJson(summary.sender)).append("\",")
          .append("\"recipient\":\"").append(escapeJson(summary.recipient)).append("\",")
          .append("\"subject\":\"").append(escapeJson(summary.subject)).append("\",")
          .append("\"timestamp\":\"").append(escapeJson(summary.timestamp)).append("\",")
          .append("\"seen\":").append(seen).append(",")
          .append("\"starred\":").append(starred).append(",")
          .append("\"folder\":\"").append(label).append("\"")
          .append("}");
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