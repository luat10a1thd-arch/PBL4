// FILE: src/main/java/com/pbl4/mailserver/spam/SpamAddr.java
package com.pbl4.mailserver.spam;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Tiện ích địa chỉ email/domain. Không dùng regex lồng lặp: chỉ quét tuyến tính. */
final class SpamAddr {
    private SpamAddr() {
    }

    private static final Set<String> SECOND_LEVEL = Set.of("com", "co", "net", "org", "gov", "edu", "ac", "or", "ne");

    static boolean isLocalChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '%' || c == '+' || c == '=' || c == '-' || c == '\'';
    }

    static boolean isDomainChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-';
    }

    /** Địa chỉ thuần chữ thường từ "Tên <a@b>", "a@b (Tên)" hoặc "a@b"; "" nếu không hợp lệ. */
    static String extract(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty() || s.length() > 1000) return "";
        int lt = s.lastIndexOf('<');
        int gt = s.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            s = s.substring(lt + 1, gt);
        } else {
            int at = s.indexOf('@');
            if (at < 0) return "";
            int a = at;
            while (a > 0 && isLocalChar(s.charAt(a - 1))) a--;
            int b = at + 1;
            while (b < s.length() && isDomainChar(s.charAt(b))) b++;
            s = s.substring(a, b);
        }
        s = s.trim().toLowerCase(Locale.ROOT);
        return valid(s) ? s : "";
    }

    static boolean valid(String a) {
        int at = a.indexOf('@');
        if (at < 1 || at > 64 || at != a.lastIndexOf('@')) return false;
        String dom = a.substring(at + 1);
        if (dom.isEmpty() || dom.length() > 255) return false;
        for (int i = 0; i < at; i++) {
            if (!isLocalChar(a.charAt(i))) return false;
        }
        for (int i = 0; i < dom.length(); i++) {
            if (!isDomainChar(dom.charAt(i))) return false;
        }
        return !(dom.startsWith(".") || dom.endsWith(".") || dom.startsWith("-") || dom.endsWith("-")
                || dom.contains(".."));
    }

    static String domainOf(String addr) {
        int at = addr.lastIndexOf('@');
        return at < 0 ? "" : addr.substring(at + 1);
    }

    static String localOf(String addr) {
        int at = addr.lastIndexOf('@');
        return at < 0 ? addr : addr.substring(0, at);
    }

    /** Các domain của địa chỉ email xuất hiện trong chuỗi (tối đa 5, chữ thường, phải có dấu chấm). */
    static List<String> domainsIn(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        int n = Math.min(text.length(), 1000);
        int from = 0;
        while (out.size() < 5) {
            int at = text.indexOf('@', from);
            if (at < 0 || at >= n) break;
            from = at + 1;
            int a = at;
            while (a > 0 && isLocalChar(text.charAt(a - 1))) a--;
            int b = at + 1;
            while (b < n && isDomainChar(text.charAt(b))) b++;
            if (a == at) continue;
            String dom = text.substring(at + 1, b).toLowerCase(Locale.ROOT);
            while (dom.endsWith(".")) dom = dom.substring(0, dom.length() - 1);
            if (dom.indexOf('.') > 0) out.add(dom);
        }
        return out;
    }

    /** Tên hiển thị của giá trị header From (đã giải mã); "" nếu không có. */
    static String displayName(String from) {
        if (from == null) return "";
        String d = "";
        int lt = from.lastIndexOf('<');
        if (lt > 0) {
            d = from.substring(0, lt);
        } else {
            int p = from.indexOf('(');
            int q = from.lastIndexOf(')');
            if (p >= 0 && q > p) d = from.substring(p + 1, q);
        }
        d = d.trim();
        if (d.length() >= 2 && d.charAt(0) == '"' && d.charAt(d.length() - 1) == '"') {
            d = d.substring(1, d.length() - 1).trim();
        }
        return d;
    }

    /** Domain đăng ký gần đúng: 2 nhãn cuối, hoặc 3 nhãn khi dạng com.vn / co.uk / edu.vn... */
    static String baseDomain(String host) {
        if (host == null) return "";
        String h = host.toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        String[] l = h.split("\\.");
        int n = l.length;
        if (n <= 2) return h;
        if (l[n - 1].length() == 2 && SECOND_LEVEL.contains(l[n - 2])) {
            return l[n - 3] + "." + l[n - 2] + "." + l[n - 1];
        }
        return l[n - 2] + "." + l[n - 1];
    }
}