package com.pbl4.mailserver.auth;

/**
 * Kết quả xác thực một chữ ký DKIM.
 * result: pass | fail | none | temperror | permerror.
 * domain/selector có thể null (ví dụ khi thư không có chữ ký hoặc thẻ d=/s= thiếu).
 */
public record DkimResult(String result, String domain, String selector, String reason) {}
