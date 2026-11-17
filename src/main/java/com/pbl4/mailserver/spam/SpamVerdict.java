// FILE: src/main/java/com/pbl4/mailserver/spam/SpamVerdict.java
package com.pbl4.mailserver.spam;

import java.util.List;

/** Kết quả phân loại. reasons: tối đa 5 lý do ngắn bằng tiếng Việt, không chứa nội dung thư. */
public record SpamVerdict(double score, boolean spam, List<String> reasons) {
    public SpamVerdict {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}