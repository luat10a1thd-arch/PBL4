// FILE: src/main/java/com/pbl4/mailserver/webapi/AuthHandler.java
package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.LoginThrottle;
import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.OutboundQueue;
import com.pbl4.mailserver.core.RecoveryStore;
import com.pbl4.mailserver.core.SecurityUtils;
import com.pbl4.mailserver.core.User;
import com.pbl4.mailserver.core.UserStore;
import com.pbl4.mailserver.mime.MessageNormalizer;
import com.pbl4.mailserver.mime.MimeBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class AuthHandler implements HttpHandler {

    private static final String ADMIN_EMAIL = ServerConfig.ADMIN_EMAIL;
    private static final Pattern SAFE_EMAIL_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,31}@" + Pattern.quote(ServerConfig.MAIL_DOMAIN.toLowerCase(Locale.ROOT)) + "$");

    // Email khôi phục: địa chỉ ASCII thông thường, domain phải có dấu chấm + TLD
    private static final Pattern EXTERNAL_EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+-]{1,64}@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,24}$");

    private static final String MSG_FORGOT_GENERIC =
            "Nếu tài khoản tồn tại và đã liên kết email khôi phục, mã OTP đã được gửi. Vui lòng kiểm tra hộp thư (kể cả thư rác)!";
    private static final String MSG_FORGOT_LIMITED =
            "Quá nhiều yêu cầu khôi phục. Vui lòng thử lại sau!";
    private static final String MSG_RESET_FAIL =
            "Thông tin xác thực không đúng hoặc đã hết hạn!";
    private static final String MSG_RECOVERY_LOCKED =
            "Khôi phục mật khẩu tạm thời bị khóa do nhập sai quá nhiều lần. Vui lòng thử lại sau 15 phút!";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Gửi thư hệ thống ở nền: phản hồi forgot-password có cùng độ trễ dù tài khoản có tồn tại hay không
    private static final ThreadPoolExecutor MAILER = new ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(500),
            r -> {
                Thread t = new Thread(r, "auth-mailer");
                t.setDaemon(true);
                return t;
            },
            (r, ex) -> System.err.println("[AUTH] Hàng đợi gửi thư hệ thống đầy, bỏ một thư."));

    static {
        MAILER.allowCoreThreadTimeOut(true);
    }

    public AuthHandler() {
        ensureAdminAccountExists();
    }

    private synchronized void ensureAdminAccountExists() {
        try {
            File dataDir = new File("data");
            if (!dataDir.exists()) {
                dataDir.mkdirs();
            }

            if (!UserStore.exists(ADMIN_EMAIL)) {
                // Thiếu PBL4_ADMIN_PASSWORD: mật khẩu ngẫu nhiên được in ra console đúng một lần ở đây
                String passwordHash = SecurityUtils.hashPassword(ServerConfig.consumeAdminInitialPassword());
                UserStore.addUserIfAbsent(ADMIN_EMAIL, passwordHash);
                MailStorageEngine.initMailbox(ADMIN_EMAIL);
                System.out.println("[AUTH] Admin account initialized: " + ADMIN_EMAIL);
            }
        } catch (Exception e) {
            System.err.println("[AUTH] Error initializing Admin account: " + e.getMessage());
        }
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

        if ("GET".equalsIgnoreCase(method) && path.endsWith("/recovery-status")) {
            handleGetRecoveryStatus(exchange);
            return;
        }
        if ("GET".equalsIgnoreCase(method) && path.endsWith("/recovery-options")) {
            handleGetRecoveryOptions(exchange);
            return;
        }
        if ("POST".equalsIgnoreCase(method)) {
            if (path.endsWith("/logout")) { handleLogout(exchange); return; }
            if (path.endsWith("/change-password")) { handleChangePassword(exchange); return; }
            if (path.endsWith("/recovery-email/verify")) { handleVerifyRecoveryEmail(exchange); return; }
            if (path.endsWith("/recovery-email")) { handleRequestRecoveryEmail(exchange); return; }
            if (path.endsWith("/backup-codes/generate")) { handleGenerateBackupCodes(exchange); return; }
            if (path.endsWith("/forgot-password")) { handleForgotPassword(exchange); return; }
            if (path.endsWith("/reset-password")) { handleResetPassword(exchange); return; }

            boolean isRegister = path.endsWith("/register");
            boolean isLogin = path.endsWith("/login");
            if (!isRegister && !isLogin) {
                sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Endpoint không tồn tại!\"}");
                return;
            }

            String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
            if (body == null) return;

            String username = getParamValue(body, "username");
            String password = getParamValue(body, "password");

            if (username == null || password == null || username.trim().isEmpty() || password.isEmpty()) {
                sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Thiếu thông tin tài khoản hoặc mật khẩu!\"}");
                return;
            }

            username = username.trim().toLowerCase(Locale.ROOT);

            if (isRegister) {
                handleRegister(exchange, username, password);
            } else {
                handleLogin(exchange, username, password);
            }
        } else {
            sendResponse(exchange, 405, "{\"success\": false, \"message\": \"Phương thức không được hỗ trợ!\"}");
        }
    }

    // ===================================================================== Đăng ký / đăng nhập / đăng xuất

    private void handleLogout(HttpExchange exchange) throws IOException {
        String token = bearerToken(exchange);
        if (token != null) {
            SessionManager.invalidate(token);
        }
        sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã đăng xuất thành công!\"}");
    }

    private void handleRegister(HttpExchange exchange, String username, String password) throws IOException {
        // Chế độ đăng ký: CLOSED từ chối ngay; APPROVAL tạo tài khoản ở trạng thái LOCKED chờ Admin duyệt
        final String mode = ServerConfig.REGISTRATION_MODE;
        if ("CLOSED".equals(mode)) {
            sendResponse(exchange, 403, "{\"success\": false, \"message\": \"Hệ thống hiện không nhận đăng ký tài khoản mới!\"}");
            return;
        }

        String clientIp = clientIp(exchange);
        if (LoginThrottle.isRegisterThrottled(clientIp)) {
            sendResponse(exchange, 429, "{\"success\": false, \"message\": \"Bạn đăng ký quá nhiều lần. Vui lòng thử lại sau 15 phút!\"}");
            return;
        }
        LoginThrottle.recordRegistration(clientIp);

        if (!SAFE_EMAIL_PATTERN.matcher(username).matches()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Tên tài khoản chứa ký tự không hợp lệ hoặc sai định dạng @" + ServerConfig.MAIL_DOMAIN.toLowerCase(Locale.ROOT) + "!\"}");
            return;
        }

        String emailPrefix = username.substring(0, username.indexOf('@'));
        if (ADMIN_EMAIL.equals(username) || emailPrefix.startsWith("admin")) {
            sendResponse(exchange, 403, "{\"success\": false, \"message\": \"Không được phép đăng ký tài khoản Admin!\"}");
            return;
        }

        String passwordValidationResult = validatePasswordStrength(password);
        if (passwordValidationResult != null) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"" + passwordValidationResult + "\"}");
            return;
        }

        boolean needsApproval = "APPROVAL".equals(mode);
        String passwordHash = SecurityUtils.hashPassword(password);
        boolean added = UserStore.addUserIfAbsent(username, passwordHash, needsApproval ? "LOCKED" : "ACTIVE");

        if (added) {
            MailStorageEngine.initMailbox(username);
            if (needsApproval) {
                sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đăng ký thành công! Tài khoản đang chờ quản trị viên duyệt trước khi sử dụng.\"}");
            } else {
                sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đăng ký tài khoản thành công!\"}");
            }
        } else {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Tài khoản đã tồn tại!\"}");
        }
    }

    /** Mật khẩu: tối thiểu 10 ký tự, tối đa 72 BYTE UTF-8 (giới hạn thực của bcrypt), có chữ + số + ký tự đặc biệt. */
    private String validatePasswordStrength(String password) {
        if (password == null || password.length() < ServerConfig.PASSWORD_MIN_LENGTH) {
            return "Mật khẩu phải có ít nhất " + ServerConfig.PASSWORD_MIN_LENGTH + " ký tự!";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > ServerConfig.PASSWORD_MAX_BYTES) {
            return "Mật khẩu quá dài (tối đa " + ServerConfig.PASSWORD_MAX_BYTES + " byte, ký tự có dấu chiếm nhiều byte)!";
        }

        boolean hasLetter = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;

        for (char c : password.toCharArray()) {
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            } else {
                hasSpecial = true;
            }
        }

        if (!hasLetter) return "Mật khẩu phải chứa ít nhất một chữ cái!";
        if (!hasDigit) return "Mật khẩu phải chứa ít nhất một chữ số!";
        if (!hasSpecial) return "Mật khẩu phải chứa ít nhất một ký tự đặc biệt (!@#$%^&*...)!";

        return null;
    }

    private void handleLogin(HttpExchange exchange, String username, String password) throws IOException {
        String clientIp = clientIp(exchange);

        if (LoginThrottle.isThrottled(clientIp, username)) {
            sendResponse(exchange, 429, "{\"success\": false, \"message\": \"Tài khoản tạm thời bị khóa do thử sai mật khẩu quá nhiều lần. Vui lòng thử lại sau 15 phút!\"}");
            return;
        }

        // Một đường xử lý duy nhất cho tài khoản không tồn tại / sai mật khẩu: cùng thông điệp, cùng thời gian
        // (verifyPassword tự băm giả khi storedHash == null hoặc hash cũ cost thấp)
        String storedHash = UserStore.findHash(username);
        boolean isValid = SecurityUtils.verifyPassword(password, storedHash);
        if (!isValid) {
            LoginThrottle.recordFailedAttempt(clientIp, username);
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Sai tài khoản hoặc mật khẩu!\"}");
            return;
        }

        LoginThrottle.resetAttempts(clientIp, username);

        // Chỉ người biết đúng mật khẩu mới biết tài khoản đang bị khóa / chờ duyệt
        if (checkIsLocked(username)) {
            sendResponse(exchange, 403, "{\"success\": false, \"message\": \"Tài khoản của bạn đang bị khóa hoặc đang chờ Admin duyệt!\"}");
            return;
        }

        // Hash cũ (cost thấp) + đăng nhập đúng => băm lại với cost hiện hành (giữ nguyên salt mã hóa thư)
        if (SecurityUtils.needsRehash(storedHash)) {
            upgradeHashIfUnchanged(username, storedHash, SecurityUtils.hashPassword(password));
        }

        // Token mới hoàn toàn cho mỗi lần đăng nhập
        String token = SessionManager.createSession(username);
        String role = ADMIN_EMAIL.equalsIgnoreCase(username) ? "ADMIN" : "USER";

        sendResponse(exchange, 200,
            "{\"success\": true, \"message\": \"Đăng nhập thành công!\", " +
            "\"username\": \"" + username + "\", " +
            "\"token\": \"" + token + "\", " +
            "\"role\": \"" + role + "\"}");
    }

    private boolean checkIsLocked(String username) {
        return "LOCKED".equalsIgnoreCase(UserStore.getStatus(username));
    }

    // ===================================================================== 1) Đổi mật khẩu

    private void handleChangePassword(HttpExchange exchange) throws IOException {
        String token = bearerToken(exchange);
        String authedUser = SessionManager.validate(token);
        if (authedUser == null) {
            sendUnauthorized(exchange);
            return;
        }

        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;

        String oldPassword = getParamValue(body, "oldPassword");
        String newPassword = getParamValue(body, "newPassword");
        String confirmPassword = getParamValue(body, "confirmPassword");

        if (oldPassword == null || oldPassword.isEmpty() || newPassword == null || newPassword.isEmpty()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Vui lòng nhập đầy đủ mật khẩu hiện tại và mật khẩu mới!\"}");
            return;
        }

        // Bắt buộc mật khẩu hiện tại (có giới hạn số lần sai: kẻ cầm tạm một phiên không dò được mật khẩu)
        if (!verifyCurrentPasswordOrReply(exchange, authedUser, oldPassword, "WRONG_OLD_PASSWORD")) return;

        if (SecurityUtils.constantTimeEquals(newPassword, oldPassword)) {
            sendResponse(exchange, 400, "{\"success\": false, \"errorCode\": \"SAME_AS_OLD_PASSWORD\", \"message\": \"⚠️ Mật khẩu mới KHÔNG ĐƯỢC TRÙNG với mật khẩu cũ!\"}");
            return;
        }

        if (confirmPassword != null && !confirmPassword.isEmpty() && !SecurityUtils.constantTimeEquals(newPassword, confirmPassword)) {
            sendResponse(exchange, 400, "{\"success\": false, \"errorCode\": \"CONFIRM_MISMATCH\", \"message\": \"⚠️ Mật khẩu xác nhận không khớp với mật khẩu mới!\"}");
            return;
        }

        String strengthErr = validatePasswordStrength(newPassword);
        if (strengthErr != null) {
            sendResponse(exchange, 400, "{\"success\": false, \"errorCode\": \"WEAK_PASSWORD\", \"message\": \"" + escapeJson(strengthErr) + "\"}");
            return;
        }

        if (!updatePasswordKeepSalt(authedUser, SecurityUtils.hashPassword(newPassword))) {
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Lỗi hệ thống khi đổi mật khẩu!\"}");
            return;
        }

        // Vô hiệu MỌI phiên (SessionManager chỉ có invalidateUser), rồi cấp phiên mới cho thiết bị đang đổi mật khẩu
        SessionManager.invalidateUser(authedUser);
        String newToken = SessionManager.createSession(authedUser);

        notifyPasswordChanged(authedUser, "đổi mật khẩu trong phần cài đặt");

        sendResponse(exchange, 200, "{\"success\": true, \"token\": \"" + newToken
                + "\", \"message\": \"✅ Đổi mật khẩu thành công! Các thiết bị khác đã bị đăng xuất.\"}");
    }

    // ===================================================================== 2) Email khôi phục (có xác minh sở hữu)

    /** Bước 1: nhập mật khẩu hiện tại + email. Gửi mã 6 số tới email đó; CHƯA lưu thành email khôi phục. */
    private void handleRequestRecoveryEmail(HttpExchange exchange) throws IOException {
        String authedUser = SessionManager.validate(bearerToken(exchange));
        if (authedUser == null) {
            sendUnauthorized(exchange);
            return;
        }

        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;

        String currentPassword = getParamValue(body, "currentPassword");
        String emailRaw = getParamValue(body, "recoveryEmail");
        if (emailRaw == null) emailRaw = getParamValue(body, "email");

        if (!verifyCurrentPasswordOrReply(exchange, authedUser, currentPassword, "WRONG_PASSWORD")) return;

        String email = validateExternalEmail(emailRaw);
        if (email == null) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Email khôi phục phải là địa chỉ hợp lệ NGOÀI hệ thống @" + escapeJson(ServerConfig.MAIL_DOMAIN.toLowerCase(Locale.ROOT)) + "!\"}");
            return;
        }

        try {
            if (email.equalsIgnoreCase(RecoveryStore.getRecoveryEmail(authedUser))) {
                sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Đây đã là email khôi phục hiện tại của bạn!\"}");
                return;
            }
            if (!LoginThrottle.tryAcquireEmailVerification(authedUser)) {
                sendResponse(exchange, 429, "{\"success\": false, \"message\": \"Bạn đã yêu cầu gửi mã xác minh quá nhiều lần. Vui lòng thử lại sau 1 giờ!\"}");
                return;
            }

            String code = sixDigits();
            RecoveryStore.startEmailVerification(authedUser, email, code);

            String subject = "[DUT Mail] Mã xác minh email khôi phục";
            String mailBody = "Xin chào,\n\n"
                    + "Tài khoản " + authedUser + " vừa yêu cầu dùng địa chỉ này làm email khôi phục.\n\n"
                    + "Mã xác minh (6 số): " + code + "\n\n"
                    + "- Mã có hiệu lực 10 phút.\n"
                    + "- Nếu không phải bạn yêu cầu, hãy bỏ qua thư này.\n";
            if (!sendSystemMail(email, subject, mailBody)) {
                RecoveryStore.cancelPendingEmail(authedUser);
                sendResponse(exchange, 503, "{\"success\": false, \"message\": \"Không gửi được thư xác minh lúc này, vui lòng thử lại sau!\"}");
                return;
            }
        } catch (RuntimeException e) {
            System.err.println("[AUTH] Lỗi bắt đầu xác minh email khôi phục: " + e.getMessage());
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Lỗi hệ thống, vui lòng thử lại!\"}");
            return;
        }

        sendResponse(exchange, 200, "{\"success\": true, \"maskedRecoveryEmail\": \"" + escapeJson(maskEmail(email))
                + "\", \"message\": \"📨 Đã gửi mã xác minh 6 số tới " + escapeJson(maskEmail(email))
                + ". Nhập mã để hoàn tất liên kết email khôi phục.\"}");
    }

    /** Bước 2: nhập mã 6 số. Đúng mới lưu email khôi phục. */
    private void handleVerifyRecoveryEmail(HttpExchange exchange) throws IOException {
        String authedUser = SessionManager.validate(bearerToken(exchange));
        if (authedUser == null) {
            sendUnauthorized(exchange);
            return;
        }

        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;

        String code = getParamValue(body, "code");
        if (code == null || code.trim().isEmpty()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Vui lòng nhập mã xác minh!\"}");
            return;
        }

        RecoveryStore.EmailConfirm result;
        try {
            result = RecoveryStore.confirmEmail(authedUser, code);
        } catch (RuntimeException e) {
            System.err.println("[AUTH] Lỗi xác minh email khôi phục: " + e.getMessage());
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Lỗi hệ thống, vui lòng thử lại!\"}");
            return;
        }

        switch (result.result) {
            case OK:
                if (!result.previousEmail.isEmpty() && !result.previousEmail.equalsIgnoreCase(result.email)) {
                    final String old = result.previousEmail;
                    final String user = authedUser;
                    final String neu = result.email;
                    MAILER.execute(() -> sendSystemMail(old, "[DUT Mail] Email khôi phục đã được thay đổi",
                            "Email khôi phục của tài khoản " + user + " vừa được đổi sang " + maskEmail(neu) + ".\n"
                                    + "Nếu không phải bạn thực hiện, hãy đổi mật khẩu ngay lập tức.\n"));
                }
                sendResponse(exchange, 200, "{\"success\": true, \"maskedRecoveryEmail\": \"" + escapeJson(maskEmail(result.email))
                        + "\", \"message\": \"✅ Đã xác minh và lưu email khôi phục!\"}");
                break;
            case INVALID:
                sendResponse(exchange, 400, "{\"success\": false, \"message\": \"❌ Mã xác minh không đúng!\"}");
                break;
            default:
                sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Mã xác minh đã hết hạn hoặc chưa có yêu cầu nào. Vui lòng yêu cầu lại!\"}");
        }
    }

    /** Trả về email đã chuẩn hóa nếu hợp lệ và NGOÀI domain của server; ngược lại null. */
    private static String validateExternalEmail(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > 254 || !EXTERNAL_EMAIL.matcher(s).matches()) return null;
        String domain = s.substring(s.indexOf('@') + 1);
        String mine = ServerConfig.MAIL_DOMAIN.toLowerCase(Locale.ROOT);
        if (domain.equals(mine) || domain.endsWith("." + mine)) return null;
        return s;
    }

    // ===================================================================== 3) Mã dự phòng

    private void handleGenerateBackupCodes(HttpExchange exchange) throws IOException {
        String authedUser = SessionManager.validate(bearerToken(exchange));
        if (authedUser == null) {
            sendUnauthorized(exchange);
            return;
        }

        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;

        // Luôn yêu cầu mật khẩu hiện tại (tạo mã mới hủy toàn bộ mã cũ)
        String currentPassword = getParamValue(body, "currentPassword");
        if (!verifyCurrentPasswordOrReply(exchange, authedUser, currentPassword, "WRONG_PASSWORD")) return;

        List<String> codes;
        try {
            codes = RecoveryStore.regenerateBackupCodes(authedUser);
        } catch (RuntimeException e) {
            System.err.println("[AUTH] Lỗi lưu mã dự phòng: " + e.getMessage());
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Không thể lưu bộ mã dự phòng!\"}");
            return;
        }

        StringBuilder codesJson = new StringBuilder("[");
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) codesJson.append(",");
            codesJson.append("\"").append(codes.get(i)).append("\"");
        }
        codesJson.append("]");

        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        sendResponse(exchange, 200,
            "{\"success\": true, \"codes\": " + codesJson
            + ", \"totalBackupCodes\": " + RecoveryStore.BACKUP_CODE_COUNT
            + ", \"remainingBackupCodes\": " + RecoveryStore.BACKUP_CODE_COUNT
            + ", \"message\": \"✅ Đã tạo " + RecoveryStore.BACKUP_CODE_COUNT
            + " mã dự phòng mới (mã cũ đã bị hủy). Mỗi mã dùng được một lần và CHỈ hiển thị lúc này, hãy lưu lại ngay!\"}");
    }

    // ===================================================================== Trạng thái / tùy chọn khôi phục

    private void handleGetRecoveryStatus(HttpExchange exchange) throws IOException {
        String authedUser = SessionManager.validate(bearerToken(exchange));
        if (authedUser == null) {
            sendUnauthorized(exchange);
            return;
        }

        RecoveryStore.Status st;
        try {
            st = RecoveryStore.status(authedUser);
        } catch (RuntimeException e) {
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Không đọc được cấu hình khôi phục!\"}");
            return;
        }

        String json = "{\"success\": true, \"username\": \"" + escapeJson(authedUser) + "\""
                + ", \"hasRecoveryEmail\": " + !st.recoveryEmail.isEmpty()
                + ", \"maskedRecoveryEmail\": \"" + escapeJson(maskEmail(st.recoveryEmail)) + "\""
                + ", \"pendingRecoveryEmail\": \"" + escapeJson(maskEmail(st.pendingEmail)) + "\""
                + ", \"backupCodesConfigured\": " + (st.backupRemaining > 0)
                + ", \"remainingBackupCodes\": " + st.backupRemaining
                + ", \"totalBackupCodes\": " + RecoveryStore.BACKUP_CODE_COUNT + "}";
        sendResponse(exchange, 200, json);
    }

    /** Công khai nhưng KHÔNG phụ thuộc username: trả cùng một nội dung cho mọi tài khoản (không lộ tài khoản/email khôi phục). */
    private void handleGetRecoveryOptions(HttpExchange exchange) throws IOException {
        sendResponse(exchange, 200, "{\"success\": true, \"methods\": [\"RECOVERY_OTP\", \"BACKUP_CODE\"]}");
    }

    // ===================================================================== 4) Quên mật khẩu

    private void handleForgotPassword(HttpExchange exchange) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;

        String clientIp = clientIp(exchange);
        String username = normalizeEmail(getParamValue(body, "username"));

        if (username.isEmpty() || username.length() > 254) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Vui lòng nhập tên tài khoản!\"}");
            return;
        }

        // Hạn mức áp dụng cho MỌI username (kể cả không tồn tại) nên 429 cũng không lộ gì
        if (!LoginThrottle.tryAcquireForgotRequest(clientIp, username)) {
            sendResponse(exchange, 429, "{\"success\": false, \"message\": \"" + MSG_FORGOT_LIMITED + "\"}");
            return;
        }

        final String target = username;
        MAILER.execute(() -> {
            try {
                processForgot(target);
            } catch (RuntimeException e) {
                System.err.println("[AUTH] Lỗi xử lý quên mật khẩu: " + e.getMessage());
            }
        });

        // LUÔN 200 + thông điệp chung: tài khoản có tồn tại hay không, có email khôi phục hay không đều như nhau
        sendResponse(exchange, 200, "{\"success\": true, \"message\": \"" + MSG_FORGOT_GENERIC + "\"}");
    }

    private void processForgot(String username) {
        User u = UserStore.getUser(username);
        if (u == null || checkIsLocked(username)) return;
        String acct = u.getUsername().toLowerCase(Locale.ROOT);
        String email = RecoveryStore.getRecoveryEmail(acct);
        if (email.isEmpty()) return;

        String otp = sixDigits();
        RecoveryStore.saveOtp(acct, otp);   // thay OTP cũ, đặt lại bộ đếm 5 lần thử, hết hạn sau 10 phút

        String subject = "[DUT Mail] Mã OTP khôi phục mật khẩu";
        String mailBody = "Xin chào,\n\n"
                + "Hệ thống nhận được yêu cầu khôi phục mật khẩu cho tài khoản: " + acct + "\n\n"
                + "Mã OTP (6 số): " + otp + "\n\n"
                + "- Mã có hiệu lực 10 phút và chỉ nhập sai được tối đa 5 lần.\n"
                + "- Nếu bạn không thực hiện yêu cầu này, hãy bỏ qua thư này.\n";
        sendSystemMail(email, subject, mailBody);
    }

    // ===================================================================== 5) Đặt lại mật khẩu

    private void handleResetPassword(HttpExchange exchange) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;

        String clientIp = clientIp(exchange);
        String username = normalizeEmail(getParamValue(body, "username"));
        String method = getParamValue(body, "method");
        String code = getParamValue(body, "code");
        if (code == null || code.trim().isEmpty()) code = getParamValue(body, "otp");
        String newPassword = getParamValue(body, "newPassword");
        String confirmPassword = getParamValue(body, "confirmPassword");

        method = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        boolean useBackup = method.equals("BACKUP_CODE");
        boolean useOtp = method.equals("OTP") || method.equals("RECOVERY_OTP");

        if (username.isEmpty() || username.length() > 254 || code == null || code.trim().isEmpty()
                || newPassword == null || newPassword.isEmpty() || !(useBackup || useOtp)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Vui lòng nhập đầy đủ Tài khoản, Phương thức (OTP hoặc BACKUP_CODE), Mã xác thực và Mật khẩu mới!\"}");
            return;
        }

        // Các kiểm tra không phụ thuộc tài khoản làm TRƯỚC: không tiêu mã vì lỗi nhập mật khẩu
        if (confirmPassword != null && !confirmPassword.isEmpty() && !SecurityUtils.constantTimeEquals(newPassword, confirmPassword)) {
            sendResponse(exchange, 400, "{\"success\": false, \"errorCode\": \"CONFIRM_MISMATCH\", \"message\": \"⚠️ Mật khẩu xác nhận không khớp với mật khẩu mới!\"}");
            return;
        }
        String strengthErr = validatePasswordStrength(newPassword);
        if (strengthErr != null) {
            sendResponse(exchange, 400, "{\"success\": false, \"errorCode\": \"WEAK_PASSWORD\", \"message\": \"" + escapeJson(strengthErr) + "\"}");
            return;
        }

        // Khóa theo TÀI KHOẢN (không chỉ IP) và theo cặp IP+tài khoản; áp dụng cả với tài khoản không tồn tại
        if (LoginThrottle.isRecoveryLocked(username) || LoginThrottle.isThrottled(clientIp, username)) {
            sendResponse(exchange, 429, "{\"success\": false, \"message\": \"" + MSG_RECOVERY_LOCKED + "\"}");
            return;
        }

        User u = UserStore.getUser(username);   // getUser: không tính alias postmaster@/abuse@
        boolean accountOk = u != null && !checkIsLocked(username);
        String acct = u != null ? u.getUsername().toLowerCase(Locale.ROOT) : username;

        boolean verified = false;
        if (accountOk) {
            try {
                verified = useBackup
                        ? RecoveryStore.consumeBackupCode(acct, code)
                        : RecoveryStore.consumeOtp(acct, code) == RecoveryStore.CodeResult.OK;
            } catch (RuntimeException e) {
                System.err.println("[AUTH] Lỗi đọc/ghi dữ liệu khôi phục: " + e.getMessage());
                sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Lỗi hệ thống, vui lòng thử lại!\"}");
                return;
            }
        } else {
            RecoveryStore.dummyVerify(code);   // cùng khối lượng việc như tài khoản thật
        }

        if (!verified) {
            LoginThrottle.recordFailedAttempt(clientIp, username);
            LoginThrottle.recordRecoveryFailure(username);
            // Một thông điệp chung cho: sai mã, hết hạn, chưa yêu cầu, tài khoản không tồn tại/bị khóa
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"" + MSG_RESET_FAIL + "\"}");
            return;
        }

        // Cố ý KHÔNG kiểm tra "mới ≠ cũ" ở đây: người chưa đăng nhập sẽ dùng nó làm oracle dò mật khẩu hiện tại
        if (!updatePasswordKeepSalt(acct, SecurityUtils.hashPassword(newPassword))) {
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Không thể cập nhật mật khẩu mới, vui lòng yêu cầu mã khác và thử lại!\"}");
            return;
        }

        try {
            RecoveryStore.clearOtp(acct);   // đã đổi mật khẩu: mọi OTP đang chờ đều vô hiệu
        } catch (RuntimeException ignored) {}
        LoginThrottle.resetAttempts(clientIp, username);
        LoginThrottle.resetRecoveryFailures(username);
        SessionManager.invalidateUser(acct);

        notifyPasswordChanged(acct, useBackup ? "mã dự phòng" : "mã OTP gửi qua email khôi phục");

        String extra = "";
        if (useBackup) {
            try {
                extra = " (Còn lại " + RecoveryStore.status(acct).backupRemaining + "/" + RecoveryStore.BACKUP_CODE_COUNT + " mã dự phòng)";
            } catch (RuntimeException ignored) {}
        }
        sendResponse(exchange, 200, "{\"success\": true, \"message\": \"✅ Đặt lại mật khẩu thành công!" + escapeJson(extra) + " Vui lòng đăng nhập lại.\"}");
    }

    // ===================================================================== Tiện ích dùng chung

    /**
     * Xác thực mật khẩu hiện tại cho thao tác nhạy cảm của người đã đăng nhập, có giới hạn số lần sai
     * (dùng chung bộ đếm với đăng nhập). Trả true nếu đúng; nếu sai đã gửi phản hồi lỗi và trả false.
     */
    private boolean verifyCurrentPasswordOrReply(HttpExchange exchange, String user, String password, String errorCode) throws IOException {
        String ip = clientIp(exchange);
        if (LoginThrottle.isThrottled(ip, user)) {
            sendResponse(exchange, 429, "{\"success\": false, \"message\": \"Thử sai mật khẩu quá nhiều lần. Vui lòng thử lại sau 15 phút!\"}");
            return false;
        }
        String storedHash = UserStore.findHash(user);
        boolean ok = password != null && !password.isEmpty() && SecurityUtils.verifyPassword(password, storedHash);
        if (!ok) {
            LoginThrottle.recordFailedAttempt(ip, user);
            sendResponse(exchange, 400, "{\"success\": false, \"errorCode\": \"" + errorCode
                    + "\", \"message\": \"❌ Mật khẩu hiện tại không chính xác!\"}");
            return false;
        }
        LoginThrottle.resetAttempts(ip, user);
        return true;
    }

    /**
     * Đổi hash mật khẩu, GIỮ NGUYÊN salt của user (khóa mã hóa thư dẫn xuất từ salt; đổi salt là mất thư cũ).
     * Chỉ gọi setPasswordHash; bản ghi được ghi xuống users.json bằng setStatus (ghi lại toàn bộ bản ghi, salt nguyên vẹn).
     */
    private static synchronized boolean updatePasswordKeepSalt(String username, String newHash) {
        try {
            User u = UserStore.getUser(username);
            if (u == null) return false;
            String saltBefore = u.getSalt();
            String status = u.getStatus() == null ? "ACTIVE" : u.getStatus();

            u.setPasswordHash(newHash);
            if (!UserStore.setStatus(username, status)) return false;

            String saltAfter = UserStore.findSalt(username);
            return saltBefore == null ? saltAfter == null : saltBefore.equals(saltAfter);
        } catch (Exception e) {
            System.err.println("[AUTH] Lỗi cập nhật mật khẩu: " + e.getMessage());
            return false;
        }
    }

    /**
     * Nâng cấp hash cũ lên cost mới sau đăng nhập đúng. Chỉ ghi khi hash hiện tại VẪN là hash đã xác thực
     * (nếu giữa chừng người dùng đã đổi mật khẩu thì không ghi đè). Lỗi thì bỏ qua, lần đăng nhập sau thử lại.
     */
    private static synchronized void upgradeHashIfUnchanged(String username, String expectedOldHash, String newHash) {
        try {
            String current = UserStore.findHash(username);
            if (current != null && SecurityUtils.constantTimeEquals(current, expectedOldHash)) {
                updatePasswordKeepSalt(username, newHash);
            }
        } catch (RuntimeException e) {
            System.err.println("[AUTH] Không nâng cấp được hash mật khẩu: " + e.getMessage());
        }
    }

    private void notifyPasswordChanged(String acct, String how) {
        final String to;
        try {
            to = RecoveryStore.getRecoveryEmail(acct);
        } catch (RuntimeException e) {
            return;
        }
        if (to == null || to.isEmpty()) return;
        final String when = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        MAILER.execute(() -> sendSystemMail(to, "[DUT Mail] Mật khẩu của bạn vừa được đổi",
                "Xin chào,\n\n"
                        + "Mật khẩu của tài khoản " + acct + " vừa được đổi (" + how + ") lúc " + when + ".\n\n"
                        + "Nếu không phải bạn thực hiện, hãy dùng chức năng quên mật khẩu hoặc liên hệ quản trị viên ngay.\n"));
    }

    /** Gửi thư hệ thống từ no-reply@MAIL_DOMAIN qua OutboundQueue. Trả true nếu đã xếp hàng. Không bao giờ ghi nội dung thư vào log. */
    private static boolean sendSystemMail(String to, String subject, String body) {
        try {
            String from = "no-reply@" + ServerConfig.MAIL_DOMAIN.toLowerCase(Locale.ROOT);
            byte[] raw = MimeBuilder.fromWebForm(from, to, subject, body);
            try {
                byte[] normalized = MessageNormalizer.normalize(raw, from);
                if (normalized != null) raw = normalized;
            } catch (RuntimeException e) {
                System.err.println("[AUTH] Không chuẩn hóa được thư hệ thống: " + e.getMessage());
            }
            Set<String> rcpts = new LinkedHashSet<>();
            rcpts.add(to);
            return OutboundQueue.enqueue(from, rcpts, raw);
        } catch (RuntimeException e) {
            System.err.println("[AUTH] Lỗi gửi thư hệ thống: " + e.getMessage());
            return false;
        }
    }

    private static String sixDigits() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    private static String normalizeEmail(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return "";
        if (!s.contains("@")) {
            s = s + "@" + ServerConfig.MAIL_DOMAIN.toLowerCase(Locale.ROOT);
        }
        return s;
    }

    private static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "";
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String dom = email.substring(at);
        if (local.length() <= 2) return local.charAt(0) + "***" + dom;
        return local.substring(0, 2) + "***" + local.charAt(local.length() - 1) + dom;
    }

    private static String clientIp(HttpExchange exchange) {
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private static String bearerToken(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        return (authHeader != null && authHeader.startsWith("Bearer ")) ? authHeader.substring(7) : null;
    }

    private void sendUnauthorized(HttpExchange exchange) throws IOException {
        sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Chưa đăng nhập hoặc phiên hết hạn!\"}");
    }

    private static String escapeJson(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private String getParamValue(String body, String paramName) {
        if (body == null || body.isEmpty()) return null;
        for (String pair : body.split("&", -1)) {
            int separator = pair.indexOf('=');
            if (separator < 0) continue;
            String key = pair.substring(0, separator);
            if (key.equalsIgnoreCase(paramName)) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return null;
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