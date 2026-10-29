package com.pbl4.mailserver.smtp.wire;

/**
 * Trong phần DATA có dòng dài hơn SMTP_MAX_DATA_LINE_BYTES. Là con của {@link DataTooLargeException}
 * để handler chỉ biết DataTooLargeException vẫn xử lý đúng (trả 552). Handler muốn chính xác hơn
 * có thể bắt riêng lớp này và trả "554 5.6.0 Line too long".
 * Phiên vẫn đồng bộ (đã nuốt hết dữ liệu tới CRLF.CRLF).
 */
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