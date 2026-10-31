package com.pbl4.mailserver.dns;

/**
 * Một bản ghi MX. Theo CONTRACT: null-MX (RFC 7505) được biểu diễn bằng host ".".
 */
public record MxRecord(int preference, String host) {}
