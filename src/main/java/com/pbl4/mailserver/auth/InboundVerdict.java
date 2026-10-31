package com.pbl4.mailserver.auth;

public record InboundVerdict(Action action, int smtpCode, String smtpMessage, byte[] rawWithHeaders) {
    public enum Action { ACCEPT, SPAM, REJECT, TEMPFAIL }
}