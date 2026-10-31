package com.pbl4.mailserver.auth;

/** Ngữ cảnh phiên SMTP nhận thư: IP máy gửi, tên HELO, MAIL FROM ("" = null sender) và đã dùng TLS hay chưa. */
public record InboundContext(String clientIp, String heloName, String envelopeFrom, boolean tlsUsed) {}