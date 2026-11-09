package com.pbl4.mailserver.auth;

public record InboundContext(String clientIp, String heloName, String envelopeFrom, boolean tlsUsed) {}
