package com.pbl4.mailserver.outbound;

import java.util.Objects;

public record RcptResult(String rcpt, RcptStatus status, String diagnostic) {
    public RcptResult {
        Objects.requireNonNull(status, "status");
        if (rcpt == null) rcpt = "";
        if (diagnostic == null) diagnostic = "";
    }
}
