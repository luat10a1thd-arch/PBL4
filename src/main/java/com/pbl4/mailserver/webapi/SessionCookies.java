// FILE: src/main/java/com/pbl4/mailserver/webapi/SessionCookies.java
package com.pbl4.mailserver.webapi;

import com.pbl4.mailserver.config.ServerConfig;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsExchange;

import java.util.List;

/**
 * Cookie phiên phía server.
 * - pbl4_session: chứa token phiên; HttpOnly (JS không đọc được).
 * - pbl4_csrf: chứa mã CSRF; KHÔNG HttpOnly để JS đọc rồi gửi lại qua header X-CSRF-Token.
 * Cả hai: Path=/, SameSite=Strict, Secure (xem isSecure), Max-Age chỉ khi "ghi nhớ" (rememberSeconds > 0).
 */
public final class SessionCookies {

    public static final String SESSION_COOKIE = "pbl4_session";
    public static final String CSRF_COOKIE = "pbl4_csrf";

    static final int MAX_TOKEN_LEN = 128;
    private static final int MAX_COOKIE_HEADER_CHARS = 8192;
    private static final String EPOCH = "Thu, 01 Jan 1970 00:00:00 GMT";

    private SessionCookies() {}

    /** Token hợp lệ về hình thức: 1..128 ký tự thuộc [A-Za-z0-9_-]. */
    static boolean isWellFormedToken(String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_TOKEN_LEN) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /** Đọc giá trị cookie đầu tiên có tên name; null nếu không có. Cookie rác thì bỏ qua, không ném lỗi. */
    static String readCookie(HttpExchange ex, String name) {
        try {
            List<String> headers = ex.getRequestHeaders().get("Cookie");
            if (headers == null) return null;
            for (String h : headers) {
                if (h == null || h.length() > MAX_COOKIE_HEADER_CHARS) continue;
                for (String part : h.split(";")) {
                    int eq = part.indexOf('=');
                    if (eq <= 0) continue;
                    if (part.substring(0, eq).trim().equals(name)) {
                        return part.substring(eq + 1).trim();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // cookie lỗi: coi như không có
        }
        return null;
    }

    /** Token trong cookie pbl4_session nếu hợp lệ về hình thức, ngược lại null. */
    static String readSessionToken(HttpExchange ex) {
        String v = readCookie(ex, SESSION_COOKIE);
        return isWellFormedToken(v) ? v : null;
    }

    static boolean isSecure(HttpExchange ex) {
        String mode = ServerConfig.COOKIE_SECURE;
        if ("TRUE".equals(mode)) return true;
        if ("FALSE".equals(mode)) return false;
        return ServerConfig.PRODUCTION || ex instanceof HttpsExchange;
    }

    /** Đặt cả hai cookie. rememberSeconds <= 0: cookie phiên (không Max-Age). */
    public static void set(HttpExchange exchange, String token, String csrf, long rememberSeconds) {
        if (!isWellFormedToken(token) || !isWellFormedToken(csrf)) {
            throw new IllegalArgumentException("token/csrf không hợp lệ");
        }
        boolean secure = isSecure(exchange);
        long maxAge = Math.max(0, rememberSeconds);
        Headers h = exchange.getResponseHeaders();
        h.add("Set-Cookie", build(SESSION_COOKIE, token, true, secure, maxAge, false));
        h.add("Set-Cookie", build(CSRF_COOKIE, csrf, false, secure, maxAge, false));
        h.set("Cache-Control", "no-store");
    }

    /** Xóa cả hai cookie (Max-Age=0 + Expires quá khứ, cùng Path/SameSite). */
    public static void clear(HttpExchange exchange) {
        boolean secure = isSecure(exchange);
        Headers h = exchange.getResponseHeaders();
        h.add("Set-Cookie", build(SESSION_COOKIE, "", true, secure, 0, true));
        h.add("Set-Cookie", build(CSRF_COOKIE, "", false, secure, 0, true));
    }

    private static String build(String name, String value, boolean httpOnly, boolean secure,
                                long maxAge, boolean delete) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(name).append('=').append(value).append("; Path=/; SameSite=Strict");
        if (httpOnly) sb.append("; HttpOnly");
        if (secure) sb.append("; Secure");
        if (delete) {
            sb.append("; Max-Age=0; Expires=").append(EPOCH);
        } else if (maxAge > 0) {
            sb.append("; Max-Age=").append(maxAge);
        }
        return sb.toString();
    }
}