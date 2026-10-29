package com.pbl4.mailserver.outbound;

import java.util.Objects;

/**
 * Kết quả cho một người nhận.
 *
 * @param rcpt       địa chỉ người nhận đúng như được truyền vào Deliverer.deliver
 * @param status     DELIVERED / DEFERRED / FAILED
 * @param diagnostic dòng phản hồi SMTP gốc của máy chủ đích (phản hồi nhiều dòng được nối bằng '\n');
 *                   với lỗi không phải phản hồi SMTP (mạng, DNS...) là mô tả tiếng Việt
 */
public record RcptResult(String rcpt, RcptStatus status, String diagnostic) {
    public RcptResult {
        Objects.requireNonNull(status, "status");
        if (rcpt == null) rcpt = "";
        if (diagnostic == null) diagnostic = "";
    }
}
