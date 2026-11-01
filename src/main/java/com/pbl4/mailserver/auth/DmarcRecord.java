package com.pbl4.mailserver.auth;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Bản ghi DMARC đã phân tích. domain = tên miền nơi tìm thấy bản ghi. */
public record DmarcRecord(String domain, String p, String sp, boolean adkimStrict, boolean aspfStrict, int pct) {

    static boolean looksLikeDmarc(String txt) {
        if (txt == null) {
            return false;
        }
        String t = txt.stripLeading();
        return t.length() >= 8 && t.regionMatches(true, 0, "v=DMARC1", 0, 8)
                && (t.length() == 8 || t.charAt(8) == ';' || Character.isWhitespace(t.charAt(8)));
    }

    /** Trả null nếu không phải bản ghi DMARC hợp lệ (thiếu p= và không có rua= thì bỏ qua, RFC 7489 6.6.3). */
    static DmarcRecord parse(String txt, String domain) {
        if (!looksLikeDmarc(txt)) {
            return null;
        }
        Map<String, String> tags = new HashMap<>();
        for (String part : txt.split(";")) {
            String s = part.trim();
            int eq = s.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            tags.putIfAbsent(s.substring(0, eq).trim().toLowerCase(Locale.ROOT), s.substring(eq + 1).trim());
        }
        String p = policy(tags.get("p"));
        if (p == null) {
            if (!tags.containsKey("rua")) {
                return null;
            }
            p = "none";
        }
        String sp = policy(tags.get("sp"));
        if (sp == null) {
            sp = p;
        }
        int pct = 100;
        String pc = tags.get("pct");
        if (pc != null) {
            try {
                int v = Integer.parseInt(pc.trim());
                if (v >= 0 && v <= 100) {
                    pct = v;
                }
            } catch (NumberFormatException ignored) {
                // pct sai: dùng 100
            }
        }
        return new DmarcRecord(domain, p, sp, "s".equalsIgnoreCase(tags.get("adkim")),
                "s".equalsIgnoreCase(tags.get("aspf")), pct);
    }

    private static String policy(String v) {
        if (v == null) {
            return null;
        }
        String x = v.trim().toLowerCase(Locale.ROOT);
        return x.equals("none") || x.equals("quarantine") || x.equals("reject") ? x : null;
    }
}