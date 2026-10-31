package com.pbl4.mailserver.smtp.wire;

import java.io.IOException;

/**
 * Thư vượt hạn mức kích thước (hoặc có dòng quá dài, xem {@link DataLineTooLongException}).
 * Khi ném ra, SmtpConnection ĐÃ nuốt hết phần còn lại tới CRLF.CRLF nên phiên vẫn đồng bộ:
 * handler cứ trả "552 5.3.4 ..." rồi đọc lệnh tiếp.
 */
public class DataTooLargeException extends IOException {
    private static final long serialVersionUID = 1L;

    public DataTooLargeException() {
        super();
    }

    public DataTooLargeException(String message) {
        super(message);
    }
}