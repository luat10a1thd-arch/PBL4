package com.pbl4.mailserver.auth;

public record DkimResult(String result, String domain, String selector, String reason) {}
