package com.pbl4.mailserver.smtp.wire;

public class DataLineTooLongException extends DataTooLargeException {
    private static final long serialVersionUID = 1L;
    private final int limit;

    public DataLineTooLongException(int limit) {
        super("Dòng trong DATA vượt quá " + limit + " byte");
        this.limit = limit;
    }

    public int getLimit() {
        return limit;
    }
}
