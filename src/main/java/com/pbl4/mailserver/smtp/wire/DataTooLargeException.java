package com.pbl4.mailserver.smtp.wire;

import java.io.IOException;

public class DataTooLargeException extends IOException {
    private static final long serialVersionUID = 1L;

    public DataTooLargeException() {
        super();
    }

    public DataTooLargeException(String message) {
        super(message);
    }
}
