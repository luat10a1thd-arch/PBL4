package com.pbl4.mailserver.outbound;

import java.util.List;

/** Giao MỘT thư tới MỘT domain đích. */
public interface Deliverer {
    /**
     * @param envelopeFrom người gửi phong bì ("" = null sender, dùng cho thư báo lỗi)
     * @param domain       domain đích (tất cả rcpts thuộc domain này)
     * @param rcpts        danh sách người nhận
     * @param raw          thư RFC 5322 (CRLF), đã ký DKIM; không bị sửa nội dung khi gửi
     * @return đúng một RcptResult cho mỗi phần tử của rcpts, cùng thứ tự; KHÔNG BAO GIỜ ném exception
     */
    List<RcptResult> deliver(String envelopeFrom, String domain, List<String> rcpts, byte[] raw);
}
