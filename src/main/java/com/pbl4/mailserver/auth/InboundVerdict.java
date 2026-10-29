package com.pbl4.mailserver.auth;

/**
 * Quyết định cho một thư đến.
 * smtpMessage luôn là ASCII và bắt đầu bằng mã nâng cao (ví dụ "5.7.1 ..."), handler ghép: smtpCode + " " + smtpMessage.
 * rawWithHeaders: ACCEPT/SPAM = thư đã chèn Authentication-Results; REJECT/TEMPFAIL = thư gốc.
 */
public record InboundVerdict(Action action, int smtpCode, String smtpMessage, byte[] rawWithHeaders) {
    public enum Action { ACCEPT, SPAM, REJECT, TEMPFAIL }
}