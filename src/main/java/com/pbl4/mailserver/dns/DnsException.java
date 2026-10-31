package com.pbl4.mailserver.dns;

/**
 * Lỗi tra cứu DNS.
 * temporary() = true: lỗi tạm (SERVFAIL, timeout, mạng) -> nên thử lại sau.
 * temporary() = false: lỗi vĩnh viễn (tên không hợp lệ, dữ liệu sai).
 * Lưu ý: NXDOMAIN / không có bản ghi KHÔNG phải lỗi, các hàm tra cứu trả danh sách rỗng.
 */
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
