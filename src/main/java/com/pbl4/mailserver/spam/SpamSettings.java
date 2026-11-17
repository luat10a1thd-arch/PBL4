// FILE: src/main/java/com/pbl4/mailserver/spam/SpamSettings.java
package com.pbl4.mailserver.spam;

import java.util.Locale;

/**
 * level: LOW | MEDIUM | HIGH (giá trị lạ được chuẩn hóa về MEDIUM).
 * learning=false: không học thêm VÀ không dùng điểm Bayes.
 */
public record SpamSettings(boolean enabled, String level, boolean learning) {
    public static final SpamSettings DEFAULT = new SpamSettings(true, "MEDIUM", true);

    public SpamSettings {
        level = normLevel(level);
    }

    static String normLevel(String l) {
        if (l == null) return "MEDIUM";
        String u = l.trim().toUpperCase(Locale.ROOT);
        return (u.equals("LOW") || u.equals("MEDIUM") || u.equals("HIGH")) ? u : "MEDIUM";
    }

    /** Ngưỡng điểm để coi là thư rác: LOW 8, MEDIUM 5, HIGH 3. */
    public double threshold() {
        return switch (level) {
            case "LOW" -> 8.0;
            case "HIGH" -> 3.0;
            default -> 5.0;
        };
    }
}