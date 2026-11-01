package com.pbl4.mailserver.outbound;

import java.util.List;

/**
 * Ảnh chụp (snapshot) một job trong hàng đợi gửi thư ra ngoài: một (thư, domain đích).
 *
 * @param id                 mã job, dạng "<msgId>.<n>"
 * @param envelopeFrom       người gửi phong bì ("" = null sender)
 * @param domain             domain đích
 * @param rcpts              các người nhận CÒN PHẢI gửi (người đã DELIVERED/FAILED đã bị loại)
 * @param attempts           số lần đã thử giao
 * @param nextAttemptEpochMs thời điểm thử kế tiếp (epoch ms)
 * @param lastError          chẩn đoán của lần hoãn gần nhất ("" nếu chưa có)
 */
public record QueueEntry(String id, String envelopeFrom, String domain, List<String> rcpts,
                         int attempts, long nextAttemptEpochMs, String lastError) {
    public QueueEntry {
        rcpts = (rcpts == null) ? List.of() : List.copyOf(rcpts);
        if (envelopeFrom == null) envelopeFrom = "";
        if (lastError == null) lastError = "";
    }
}