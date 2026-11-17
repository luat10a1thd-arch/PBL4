// FILE: src/main/java/com/pbl4/mailserver/forward/ForwardRule.java
package com.pbl4.mailserver.forward;

/** Một luật tự động chuyển tiếp của một user (chỉ phần công khai, không có mã xác minh). */
public record ForwardRule(String id, String target, boolean verified, boolean enabled, boolean keepCopy,
                          String fromContains, String subjectContains, long createdAt) {
}