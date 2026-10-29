package com.pbl4.mailserver.auth;

/**
 * Kết quả đánh giá DMARC.
 * result: pass | fail | none | temperror | permerror.
 * policy: chính sách áp dụng (p hoặc sp) hoặc null nếu chưa biết (lỗi tra DNS, không có bản ghi).
 * disposition: none | quarantine | reject - sau khi áp pct.
 */
public record DmarcResult(String result, String disposition, String fromDomain, String recordDomain,
                          String policy, boolean spfAligned, boolean dkimAligned, String reason) {

    static DmarcResult of(String result, String fromDomain, String reason) {
        return new DmarcResult(result, "none", fromDomain, null, null, false, false, reason);
    }
}