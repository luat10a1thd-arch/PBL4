package com.pbl4.mailserver.outbound;

/** Kết quả giao thư cho TỪNG người nhận. */
public enum RcptStatus {
    /** Máy chủ đích đã nhận thư (2xx sau DATA). */
    DELIVERED,
    /** Lỗi tạm thời (4xx, lỗi mạng, timeout, DNS tạm): hàng đợi nên thử lại sau. */
    DEFERRED,
    /** Lỗi vĩnh viễn (5xx, domain không tồn tại, null-MX): hàng đợi nên sinh thư báo lỗi (bounce). */
    FAILED
}
