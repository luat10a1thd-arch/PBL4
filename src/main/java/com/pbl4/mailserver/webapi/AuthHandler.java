package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.config.ServerConfig;
import com.pbl4.mailserver.core.LoginThrottle;
import com.pbl4.mailserver.core.MailService;
import com.pbl4.mailserver.core.MailStorageEngine;
import com.pbl4.mailserver.core.SecurityUtils;
import com.pbl4.mailserver.core.UserStore;
import com.pbl4.mailserver.mime.MimeBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class AuthHandler implements HttpHandler {

    private static final String ADMIN_EMAIL = ServerConfig.ADMIN_EMAIL;
    private static final Pattern SAFE_EMAIL_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,31}@" + Pattern.quote(ServerConfig.MAIL_DOMAIN.toLowerCase(Locale.ROOT)) + "$");
    private static final String DUMMY_HASH = SecurityUtils.hashPassword("DummyPassword123!");

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
                String passwordHash = SecurityUtils.hashPassword(ServerConfig.ADMIN_DEFAULT_PASS);
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
        if ("POST".equalsIgnoreCase(method) && path.endsWith("/logout")) {
            handleLogout(exchange);
            return;
        }
        if ("POST".equalsIgnoreCase(method) && path.endsWith("/backup-codes/generate")) {
            handleGenerateBackupCodes(exchange);
            return;
        }
        if ("POST".equalsIgnoreCase(method) && path.endsWith("/recovery-settings")) {
            handleSaveRecoverySettings(exchange);
            return;
        }
        if ("POST".equalsIgnoreCase(method) && path.endsWith("/forgot-password")) {
            handleForgotPassword(exchange);
            return;
        }
        if ("POST".equalsIgnoreCase(method) && path.endsWith("/reset-password")) {
            handleResetPassword(exchange);
            return;
        }
        if ("POST".equalsIgnoreCase(method) && path.endsWith("/change-password")) {
            handleChangePassword(exchange);
            return;
        }

        if ("POST".equalsIgnoreCase(method)) {
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

    private void handleLogout(HttpExchange exchange) throws IOException {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            SessionManager.invalidate(token);
        }
        sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đã đăng xuất thành công!\"}");
    }

    private void handleRegister(HttpExchange exchange, String username, String password) throws IOException {
        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
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

        String passwordHash = SecurityUtils.hashPassword(password);
        boolean added = UserStore.addUserIfAbsent(username, passwordHash);

        if (added) {
            MailStorageEngine.initMailbox(username);
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"Đăng ký tài khoản thành công!\"}");
        } else {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Tài khoản đã tồn tại!\"}");
        }
    }

    private String validatePasswordStrength(String password) {
        if (password == null || password.length() < 8 || password.length() > 32) {
            return "Mật khẩu phải có độ dài từ 8 đến 32 ký tự!";
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
        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();

        if (LoginThrottle.isThrottled(clientIp, username)) {
            sendResponse(exchange, 429, "{\"success\": false, \"message\": \"Tài khoản tạm thời bị khóa do thử sai quá nhiều lần. Vui lòng thử lại sau 15 phút!\"}");
            return;
        }

        String storedHash = UserStore.findHash(username);
        boolean userExists = (storedHash != null);

        if (!userExists) {
            SecurityUtils.checkPassword(password, DUMMY_HASH);
            LoginThrottle.recordFailedAttempt(clientIp, username);
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Sai tài khoản hoặc mật khẩu!\"}");
            return;
        }

        boolean isValid = SecurityUtils.checkPassword(password, storedHash);
        if (!isValid) {
            LoginThrottle.recordFailedAttempt(clientIp, username);
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Sai tài khoản hoặc mật khẩu!\"}");
            return;
        }

        LoginThrottle.resetAttempts(clientIp, username);

        if (checkIsLocked(username)) {
            sendResponse(exchange, 403, "{\"success\": false, \"message\": \"Tài khoản của bạn đã bị Admin khóa!\"}");
            return;
        }

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

    private static final int BACKUP_CODE_COUNT = 5;
    private static final ConcurrentHashMap<String, OtpRecord> RESET_OTPS = new ConcurrentHashMap<>();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Object RECOVERY_FILE_LOCK = new Object();

    private static final class OtpRecord {
        final String code;
        final long expiresAt;
        int attempts;
        OtpRecord(String code, long expiresAt) {
            this.code = code;
            this.expiresAt = expiresAt;
        }
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

    private static Path recoveryFilePath(String username) throws IOException {
        String clean = normalizeEmail(username).replaceAll("[^a-z0-9._@-]", "_");
        Path dir = Paths.get(ServerConfig.BASE_DATA_DIR, "recovery").toAbsolutePath().normalize();
        Files.createDirectories(dir);
        return dir.resolve(clean + ".properties").normalize();
    }

    private static Properties loadRecoveryProps(String username) {
        Properties props = new Properties();
        synchronized (RECOVERY_FILE_LOCK) {
            try {
                Path p = recoveryFilePath(username);
                if (Files.exists(p)) {
                    try (InputStream in = Files.newInputStream(p)) {
                        props.load(in);
                    }
                }
            } catch (Exception ignored) {}
        }
        return props;
    }

    private static boolean saveRecoveryProps(String username, Properties props) {
        synchronized (RECOVERY_FILE_LOCK) {
            try {
                Path p = recoveryFilePath(username);
                try (OutputStream out = Files.newOutputStream(p)) {
                    props.store(out, "DUT Mail Account Recovery Security");
                }
                return true;
            } catch (Exception e) {
                System.err.println("[AUTH] Lỗi lưu cấu hình khôi phục cho " + username + ": " + e.getMessage());
                return false;
            }
        }
    }

    private static String sha256Hex(String username, String secret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String cleanSecret = secret == null ? "" : secret.trim().toLowerCase(Locale.ROOT);
            String payload = normalizeEmail(username) + ":" + ServerConfig.SECRET_KEY + ":" + cleanSecret;
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<String> getBackupHashesList(Properties props) {
        String raw = props.getProperty("backupHashes", "").trim();
        if (raw.isEmpty()) return new ArrayList<>();
        List<String> list = new ArrayList<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) list.add(t);
        }
        return list;
    }

    private static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "";
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String dom = email.substring(at);
        if (local.length() <= 2) return local.charAt(0) + "***" + dom;
        return local.substring(0, 2) + "***" + local.charAt(local.length() - 1) + dom;
    }

    private void handleGetRecoveryStatus(HttpExchange exchange) throws IOException {
        String authedUser = authenticateRequest(exchange);
        if (authedUser == null) {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Chưa đăng nhập hoặc phiên hết hạn!\"}");
            return;
        }

        Properties props = loadRecoveryProps(authedUser);
        List<String> hashes = getBackupHashesList(props);
        boolean configured = "true".equalsIgnoreCase(props.getProperty("backupConfigured", "false")) || !hashes.isEmpty();
        String recEmail = props.getProperty("recoveryEmail", "").trim();
        String secQuestion = props.getProperty("securityQuestion", "").trim();
        boolean hasSecAnswer = !props.getProperty("securityAnswerHash", "").trim().isEmpty();

        String json = String.format(
            "{\"success\": true, \"username\": \"%s\", \"backupCodesConfigured\": %s, \"remainingBackupCodes\": %d, \"totalBackupCodes\": %d, \"recoveryEmail\": \"%s\", \"securityQuestion\": \"%s\", \"hasSecurityQuestion\": %s}",
            escapeJson(authedUser),
            configured,
            hashes.size(),
            BACKUP_CODE_COUNT,
            escapeJson(recEmail),
            escapeJson(secQuestion),
            (!secQuestion.isEmpty() && hasSecAnswer)
        );
        sendResponse(exchange, 200, json);
    }

    private void handleGetRecoveryOptions(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        String username = null;
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "username".equalsIgnoreCase(pair.substring(0, eq))) {
                    username = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        }
        String cleanUser = normalizeEmail(username);
        if (cleanUser.isEmpty() || !UserStore.exists(cleanUser)) {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Không tìm thấy tài khoản này trong hệ thống!\"}");
            return;
        }

        Properties props = loadRecoveryProps(cleanUser);
        List<String> hashes = getBackupHashesList(props);
        String recEmail = props.getProperty("recoveryEmail", "").trim();
        String secQuestion = props.getProperty("securityQuestion", "").trim();
        boolean hasSecAnswer = !props.getProperty("securityAnswerHash", "").trim().isEmpty();

        String json = String.format(
            "{\"success\": true, \"username\": \"%s\", \"hasBackupCodes\": %s, \"remainingBackupCodes\": %d, \"hasRecoveryEmail\": %s, \"maskedRecoveryEmail\": \"%s\", \"hasSecurityQuestion\": %s, \"securityQuestion\": \"%s\"}",
            escapeJson(cleanUser),
            !hashes.isEmpty(),
            hashes.size(),
            !recEmail.isEmpty(),
            escapeJson(maskEmail(recEmail)),
            (!secQuestion.isEmpty() && hasSecAnswer),
            escapeJson(secQuestion)
        );
        sendResponse(exchange, 200, json);
    }

    private void handleGenerateBackupCodes(HttpExchange exchange) throws IOException {
        String authedUser = authenticateRequest(exchange);
        if (authedUser == null) {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Chưa đăng nhập hoặc phiên hết hạn!\"}");
            return;
        }

        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;

        Properties props = loadRecoveryProps(authedUser);
        List<String> existingHashes = getBackupHashesList(props);
        String currentPassword = getParamValue(body, "currentPassword");

        if (!existingHashes.isEmpty()) {
            String storedHash = UserStore.findHash(authedUser);
            if (currentPassword == null || currentPassword.isEmpty() || storedHash == null || !SecurityUtils.checkPassword(currentPassword, storedHash)) {
                sendResponse(exchange, 400, "{\"success\": false, \"requirePassword\": true, \"message\": \"Vui lòng nhập đúng mật khẩu hiện tại để tạo bộ 5 mã dự phòng mới!\"}");
                return;
            }
        }

        List<String> plainCodes = new ArrayList<>(BACKUP_CODE_COUNT);
        List<String> newHashes = new ArrayList<>(BACKUP_CODE_COUNT);
        Set<String> uniqueCheck = new LinkedHashSet<>();

        while (plainCodes.size() < BACKUP_CODE_COUNT) {
            int num = 10_000_000 + SECURE_RANDOM.nextInt(90_000_000);
            String code = String.format("%08d", num);
            if (uniqueCheck.add(code)) {
                plainCodes.add(code);
                newHashes.add(sha256Hex(authedUser, code));
            }
        }

        props.setProperty("backupConfigured", "true");
        props.setProperty("backupHashes", String.join(",", newHashes));
        props.setProperty("updatedAt", String.valueOf(System.currentTimeMillis()));

        if (!saveRecoveryProps(authedUser, props)) {
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Không thể lưu bộ mã dự phòng!\"}");
            return;
        }

        StringBuilder codesJson = new StringBuilder("[");
        for (int i = 0; i < plainCodes.size(); i++) {
            if (i > 0) codesJson.append(",");
            codesJson.append("\"").append(plainCodes.get(i)).append("\"");
        }
        codesJson.append("]");

        sendResponse(exchange, 200,
            "{\"success\": true, \"codes\": " + codesJson +
            ", \"remainingBackupCodes\": " + BACKUP_CODE_COUNT +
            ", \"message\": \"✅ Đã tạo thành công bộ 5 Mã dự phòng (8 số)! Hãy sao chép hoặc tải về máy cá nhân của bạn.\"}");
    }

    private void handleSaveRecoverySettings(HttpExchange exchange) throws IOException {
        String authedUser = authenticateRequest(exchange);
        if (authedUser == null) {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Chưa đăng nhập hoặc phiên hết hạn!\"}");
            return;
        }

        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;

        String currentPassword = getParamValue(body, "currentPassword");
        String recoveryEmail = getParamValue(body, "recoveryEmail");
        String securityQuestion = getParamValue(body, "securityQuestion");
        String securityAnswer = getParamValue(body, "securityAnswer");

        String storedHash = UserStore.findHash(authedUser);
        if (currentPassword == null || currentPassword.isEmpty() || storedHash == null || !SecurityUtils.checkPassword(currentPassword, storedHash)) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"❌ Vui lòng nhập đúng mật khẩu hiện tại để lưu cài đặt khôi phục!\"}");
            return;
        }

        Properties props = loadRecoveryProps(authedUser);

        if (recoveryEmail != null) {
            String cleanRec = recoveryEmail.trim().toLowerCase(Locale.ROOT);
            if (!cleanRec.isEmpty()) {
                if (!cleanRec.contains("@")) {
                    cleanRec = cleanRec + "@" + ServerConfig.MAIL_DOMAIN.toLowerCase(Locale.ROOT);
                }
                if (cleanRec.equalsIgnoreCase(authedUser)) {
                    sendResponse(exchange, 400, "{\"success\": false, \"message\": \"⚠️ Email khôi phục phải là một tài khoản khác với tài khoản chính của bạn!\"}");
                    return;
                }
                props.setProperty("recoveryEmail", cleanRec);
            } else {
                props.remove("recoveryEmail");
            }
        }

        if (securityQuestion != null && !securityQuestion.trim().isEmpty()) {
            props.setProperty("securityQuestion", securityQuestion.trim());
            if (securityAnswer != null && !securityAnswer.trim().isEmpty()) {
                props.setProperty("securityAnswerHash", sha256Hex(authedUser, securityAnswer.trim()));
            }
        }

        if (saveRecoveryProps(authedUser, props)) {
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"✅ Đã lưu cài đặt Email khôi phục & Câu hỏi bảo mật thành công!\"}");
        } else {
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Không thể lưu cài đặt khôi phục!\"}");
        }
    }

    private void handleForgotPassword(HttpExchange exchange) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;

        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        String username = normalizeEmail(getParamValue(body, "username"));

        if (username.isEmpty() || !UserStore.exists(username)) {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Tài khoản không tồn tại trong hệ thống!\"}");
            return;
        }
        if (LoginThrottle.isThrottled(clientIp, username)) {
            sendResponse(exchange, 429, "{\"success\": false, \"message\": \"Quá nhiều yêu cầu khôi phục. Vui lòng thử lại sau 15 phút!\"}");
            return;
        }
        if (checkIsLocked(username)) {
            sendResponse(exchange, 403, "{\"success\": false, \"message\": \"Tài khoản đang bị Admin khóa, không thể khôi phục!\"}");
            return;
        }

        Properties props = loadRecoveryProps(username);
        String registeredRecoveryEmail = props.getProperty("recoveryEmail", "").trim();

        if (registeredRecoveryEmail.isEmpty()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"⚠️ Tài khoản này chưa liên kết Email khôi phục! Vui lòng sử dụng 1 trong 5 Mã dự phòng (8 số) hoặc Câu hỏi bảo mật đã thiết lập.\"}");
            return;
        }

        String otp = String.format("%06d", 100000 + SECURE_RANDOM.nextInt(900000));
        RESET_OTPS.put(username, new OtpRecord(otp, System.currentTimeMillis() + 10 * 60_000L));

        String subject = "[DUT Security] Mã OTP khôi phục mật khẩu cho tài khoản " + username;
        String mailBody = "Xin chào,

"
                + "Hệ thống nhận được yêu cầu khôi phục mật khẩu cho tài khoản: " + username + "

"
                + "👉 Mã xác thực OTP (6 số) là: " + otp + "

"
                + "- Thời gian hiệu lực: 10 phút.
"
                + "- Nếu bạn không thực hiện yêu cầu này, vui lòng bỏ qua email này.
";

        try {
            byte[] raw = MimeBuilder.fromWebForm(
                    ADMIN_EMAIL,
                    registeredRecoveryEmail,
                    subject,
                    mailBody
            );
            MailService.submit(ADMIN_EMAIL, ADMIN_EMAIL, Collections.singleton(registeredRecoveryEmail), raw);
        } catch (Exception e) {
            System.err.println("[AUTH] Lỗi gửi email OTP khôi phục: " + e.getMessage());
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Không thể gửi thư OTP tới email khôi phục!\"}");
            return;
        }

        String masked = maskEmail(registeredRecoveryEmail);
        sendResponse(exchange, 200, "{\"success\": true, \"maskedRecoveryEmail\": \"" + escapeJson(masked)
                + "\", \"message\": \"📨 Đã gửi mã OTP (6 số) tới hộp thư khôi phục đã liên kết (" + escapeJson(masked) + "). Vui lòng mở hộp thư đó để lấy mã!\"}");
    }

    private void handleResetPassword(HttpExchange exchange) throws IOException {
        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;

        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        String username = normalizeEmail(getParamValue(body, "username"));
        String method = getParamValue(body, "method");
        String codeOrOtp = getParamValue(body, "code");
        if (codeOrOtp == null || codeOrOtp.trim().isEmpty()) {
            codeOrOtp = getParamValue(body, "otp");
        }
        String securityAnswer = getParamValue(body, "securityAnswer");
        String newPassword = getParamValue(body, "newPassword");
        String confirmPassword = getParamValue(body, "confirmPassword");

        if (username.isEmpty() || newPassword == null || newPassword.isEmpty()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Vui lòng nhập đầy đủ Tài khoản, Mã xác thực và Mật khẩu mới!\"}");
            return;
        }

        if (LoginThrottle.isThrottled(clientIp, username)) {
            sendResponse(exchange, 429, "{\"success\": false, \"message\": \"Bạn đã nhập sai mã xác thực quá nhiều lần. Vui lòng thử lại sau 15 phút!\"}");
            return;
        }

        String storedHash = UserStore.findHash(username);
        if (storedHash == null) {
            sendResponse(exchange, 404, "{\"success\": false, \"message\": \"Tài khoản không tồn tại!\"}");
            return;
        }
        if (checkIsLocked(username)) {
            sendResponse(exchange, 403, "{\"success\": false, \"message\": \"Tài khoản đang bị Admin khóa!\"}");
            return;
        }

        if (confirmPassword != null && !confirmPassword.isEmpty() && !newPassword.equals(confirmPassword)) {
            sendResponse(exchange, 400, "{\"success\": false, \"errorCode\": \"CONFIRM_MISMATCH\", \"message\": \"⚠️ Mật khẩu xác nhận không khớp với mật khẩu mới!\"}");
            return;
        }

        Properties props = loadRecoveryProps(username);
        List<String> backupHashes = getBackupHashesList(props);
        int matchedBackupIndex = -1;

        String cleanCode = codeOrOtp == null ? "" : codeOrOtp.replaceAll("\\s+", "");

        if ("SECURITY_QUESTION".equalsIgnoreCase(method)) {
            String expectedAnsHash = props.getProperty("securityAnswerHash", "").trim();
            if (expectedAnsHash.isEmpty() || securityAnswer == null || securityAnswer.trim().isEmpty()) {
                sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Vui lòng nhập câu trả lời bảo mật!\"}");
                return;
            }
            String actualHash = sha256Hex(username, securityAnswer.trim());
            if (!expectedAnsHash.equals(actualHash)) {
                LoginThrottle.recordFailedAttempt(clientIp, username);
                sendResponse(exchange, 400, "{\"success\": false, \"message\": \"❌ Câu trả lời bảo mật không chính xác!\"}");
                return;
            }
        } else if ("RECOVERY_OTP".equalsIgnoreCase(method) || (cleanCode.length() == 6 && !"BACKUP_CODE".equalsIgnoreCase(method))) {
            OtpRecord record = RESET_OTPS.get(username);
            if (record == null || System.currentTimeMillis() > record.expiresAt) {
                RESET_OTPS.remove(username);
                sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Mã OTP 6 số không tồn tại hoặc đã hết hạn (10 phút)!\"}");
                return;
            }
            synchronized (record) {
                if (!record.code.equals(cleanCode)) {
                    LoginThrottle.recordFailedAttempt(clientIp, username);
                    if (++record.attempts >= 5) RESET_OTPS.remove(username);
                    sendResponse(exchange, 400, "{\"success\": false, \"message\": \"❌ Mã OTP 6 số không chính xác!\"}");
                    return;
                }
            }
        } else {

            if (!cleanCode.matches("\\d{8}")) {
                sendResponse(exchange, 400, "{\"success\": false, \"message\": \"⚠️ Mã dự phòng phải gồm đúng 8 chữ số!\"}");
                return;
            }
            if (backupHashes.isEmpty()) {
                sendResponse(exchange, 400, "{\"success\": false, \"message\": \"⚠️ Tài khoản này chưa tạo hoặc đã dùng hết 5 Mã dự phòng 8 số!\"}");
                return;
            }
            String candidateHash = sha256Hex(username, cleanCode);
            matchedBackupIndex = backupHashes.indexOf(candidateHash);
            if (matchedBackupIndex < 0) {
                LoginThrottle.recordFailedAttempt(clientIp, username);
                sendResponse(exchange, 400, "{\"success\": false, \"message\": \"❌ Mã dự phòng 8 số không chính xác hoặc đã được sử dụng trước đó!\"}");
                return;
            }
        }

        if (SecurityUtils.checkPassword(newPassword, storedHash)) {
            sendResponse(exchange, 400, "{\"success\": false, \"errorCode\": \"SAME_AS_OLD_PASSWORD\", \"message\": \"⚠️ Ngoại lệ: Mật khẩu mới KHÔNG ĐƯỢC TRÙNG với mật khẩu cũ! (Mã xác thực của bạn vẫn được giữ nguyên, hãy nhập mật khẩu mới khác).\"}");
            return;
        }

        String strengthErr = validatePasswordStrength(newPassword);
        if (strengthErr != null) {
            sendResponse(exchange, 400, "{\"success\": false, \"errorCode\": \"WEAK_PASSWORD\", \"message\": \"" + strengthErr + "\"}");
            return;
        }

        String newHash = SecurityUtils.hashPassword(newPassword);
        if (!updatePasswordKeepSalt(username, newHash)) {
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Không thể cập nhật mật khẩu mới!\"}");
            return;
        }

        String extraInfo = "";
        if (matchedBackupIndex >= 0) {
            backupHashes.remove(matchedBackupIndex);
            props.setProperty("backupHashes", String.join(",", backupHashes));
            saveRecoveryProps(username, props);
            extraInfo = " (Còn lại " + backupHashes.size() + "/" + BACKUP_CODE_COUNT + " mã dự phòng 8 số)";
        }
        RESET_OTPS.remove(username);
        LoginThrottle.resetAttempts(clientIp, username);
        SessionManager.invalidateUser(username);

        sendResponse(exchange, 200, "{\"success\": true, \"message\": \"✅ Đặt lại mật khẩu mới thành công!" + escapeJson(extraInfo) + " Vui lòng đăng nhập lại.\"}");
    }

    private void handleChangePassword(HttpExchange exchange) throws IOException {
        String authedUser = authenticateRequest(exchange);
        if (authedUser == null) {
            sendResponse(exchange, 401, "{\"success\": false, \"message\": \"Chưa đăng nhập hoặc phiên hết hạn!\"}");
            return;
        }

        String body = ApiServer.readBodyOrReply(exchange, ServerConfig.MAX_BODY_AUTH_BYTES, ServerConfig.BODY_READ_TOTAL_TIMEOUT_SMALL_MS);
        if (body == null) return;

        String oldPassword = getParamValue(body, "oldPassword");
        String newPassword = getParamValue(body, "newPassword");
        String confirmPassword = getParamValue(body, "confirmPassword");
        String storedHash = UserStore.findHash(authedUser);

        if (oldPassword == null || oldPassword.isEmpty() || newPassword == null || newPassword.isEmpty()) {
            sendResponse(exchange, 400, "{\"success\": false, \"message\": \"Vui lòng nhập đầy đủ mật khẩu hiện tại và mật khẩu mới!\"}");
            return;
        }

        if (storedHash == null || !SecurityUtils.checkPassword(oldPassword, storedHash)) {
            sendResponse(exchange, 400, "{\"success\": false, \"errorCode\": \"WRONG_OLD_PASSWORD\", \"message\": \"❌ Mật khẩu hiện tại không chính xác!\"}");
            return;
        }

        if (newPassword.equals(oldPassword) || SecurityUtils.checkPassword(newPassword, storedHash)) {
            sendResponse(exchange, 400, "{\"success\": false, \"errorCode\": \"SAME_AS_OLD_PASSWORD\", \"message\": \"⚠️ Ngoại lệ: Mật khẩu mới KHÔNG ĐƯỢC TRÙNG với mật khẩu cũ! Vui lòng nhập mật khẩu khác.\"}");
            return;
        }

        if (confirmPassword != null && !confirmPassword.isEmpty() && !newPassword.equals(confirmPassword)) {
            sendResponse(exchange, 400, "{\"success\": false, \"errorCode\": \"CONFIRM_MISMATCH\", \"message\": \"⚠️ Mật khẩu xác nhận không khớp với mật khẩu mới!\"}");
            return;
        }

        String strengthErr = validatePasswordStrength(newPassword);
        if (strengthErr != null) {
            sendResponse(exchange, 400, "{\"success\": false, \"errorCode\": \"WEAK_PASSWORD\", \"message\": \"" + strengthErr + "\"}");
            return;
        }

        if (updatePasswordKeepSalt(authedUser, SecurityUtils.hashPassword(newPassword))) {
            sendResponse(exchange, 200, "{\"success\": true, \"message\": \"✅ Đổi mật khẩu thành công!\"}");
        } else {
            sendResponse(exchange, 500, "{\"success\": false, \"message\": \"Lỗi hệ thống khi đổi mật khẩu!\"}");
        }
    }

    private static String authenticateRequest(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        String token = (authHeader != null && authHeader.startsWith("Bearer ")) ? authHeader.substring(7) : null;
        return SessionManager.validate(token);
    }

    private static synchronized boolean updatePasswordKeepSalt(String username, String newHash) {
        try {
            String oldHash = UserStore.findHash(username);
            String status = UserStore.getStatus(username);
            com.pbl4.mailserver.core.User u = UserStore.getUser(username);
            if (u != null) {
                for (java.lang.reflect.Field f : u.getClass().getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        Object val = f.get(u);
                        String fn = f.getName().toLowerCase(Locale.ROOT);
                        if ((oldHash != null && oldHash.equals(val)) || fn.contains("pass") || fn.contains("hash")) {
                            f.set(u, newHash);
                        }
                    }
                }
            }
            UserStore.setStatus(username, status != null ? status : "ACTIVE");

            File usersFile = new File(ServerConfig.BASE_DATA_DIR, "users.txt");
            if (!usersFile.exists()) usersFile = new File("data/users.txt");
            if (usersFile.exists() && oldHash != null && !oldHash.isEmpty()) {
                String content = Files.readString(usersFile.toPath(), StandardCharsets.UTF_8);
                if (content.contains(oldHash)) {
                    Files.writeString(usersFile.toPath(), content.replace(oldHash, newHash), StandardCharsets.UTF_8);
                }
            }
            return true;
        } catch (Exception e) {
            System.err.println("[AUTH] Lỗi cập nhật mật khẩu: " + e.getMessage());
            return false;
        }
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
