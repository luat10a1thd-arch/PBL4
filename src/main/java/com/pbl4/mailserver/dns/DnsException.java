package com.pbl4.mailserver.dns;

public class DnsException extends Exception {
    private static final long serialVersionUID = 1L;

    private final boolean temporary;

    public DnsException(String message, boolean temporary) {
        super(message);
        this.temporary = temporary;
    }

    public DnsException(String message, boolean temporary, Throwable cause) {
        super(message, cause);
        this.temporary = temporary;
    }

    public boolean temporary() {
        return temporary;
    }
}
