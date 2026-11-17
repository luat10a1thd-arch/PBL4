// FILE: src/main/java/com/pbl4/mailserver/spam/HtmlLinks.java
package com.pbl4.mailserver.spam;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Rút liên kết từ HTML/văn bản bằng quét tuyến tính (không regex phức tạp). */
final class HtmlLinks {
    private HtmlLinks() {
    }

    record Link(String href, String text) {
    }

    static final Set<String> SHORTENERS = Set.of("bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "ow.ly",
            "buff.ly", "rebrand.ly", "cutt.ly", "shorturl.at", "tiny.cc", "s.id", "bom.to", "rb.gy", "v.gd",
            "t.ly", "lnkd.in", "soo.gd", "qr.ae", "adf.ly");

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f';
    }

    static List<Link> anchors(String html, int max) {
        List<Link> out = new ArrayList<>();
        int n = html.length();
        int i = 0;
        while (i < n && out.size() < max) {
            int lt = html.indexOf('<', i);
            if (lt < 0 || lt + 2 >= n) break;
            char c1 = html.charAt(lt + 1);
            if ((c1 == 'a' || c1 == 'A') && isSpace(html.charAt(lt + 2))) {
                int gt = html.indexOf('>', lt + 2);
                if (gt < 0 || gt - lt > 4000) {
                    i = lt + 2;
                    continue;
                }
                String tag = html.substring(lt, gt + 1);
                String href = attr(tag, "href");
                int close = indexOfIgnoreCase(html, "</a", gt + 1, Math.min(n, gt + 1 + 3000));
                String inner = close < 0 ? "" : stripTags(html.substring(gt + 1, close));
                if (href != null) {
                    out.add(new Link(href.trim().replace("&amp;", "&"), inner));
                }
                i = gt + 1;
            } else {
                i = lt + 1;
            }
        }
        return out;
    }

    private static String attr(String tag, String name) {
        String low = tag.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int i = low.indexOf(name, from);
            if (i < 0) return null;
            from = i + name.length();
            if (i == 0) continue;
            char p = low.charAt(i - 1);
            if (!(isSpace(p) || p == '"' || p == '\'')) continue;
            int j = from;
            while (j < low.length() && isSpace(low.charAt(j))) j++;
            if (j >= low.length() || low.charAt(j) != '=') continue;
            j++;
            while (j < tag.length() && isSpace(tag.charAt(j))) j++;
            if (j >= tag.length()) return "";
            char q = tag.charAt(j);
            if (q == '"' || q == '\'') {
                int e = tag.indexOf(q, j + 1);
                return tag.substring(j + 1, e < 0 ? tag.length() : e);
            }
            int e = j;
            while (e < tag.length() && !isSpace(tag.charAt(e)) && tag.charAt(e) != '>') e++;
            return tag.substring(j, e);
        }
    }

    private static int indexOfIgnoreCase(String s, String needle, int from, int to) {
        int max = Math.min(to, s.length()) - needle.length();
        for (int i = Math.max(0, from); i <= max; i++) {
            if (s.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    static String stripTags(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean in = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') in = true;
            else if (c == '>' && in) in = false;
            else if (!in) sb.append(c);
        }
        return sb.toString().replace("&nbsp;", " ").replace("&amp;", "&").trim();
    }

    static int countIgnoreCase(String s, String needle) {
        int c = 0;
        int from = 0;
        while (c < 100000) {
            int i = indexOfIgnoreCase(s, needle, from, s.length());
            if (i < 0) break;
            c++;
            from = i + needle.length();
        }
        return c;
    }

    /** Các URL http(s) dạng văn bản thuần (tối đa max). */
    static List<String> textUrls(String text, int max) {
        List<String> out = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n && out.size() < max) {
            int h = text.indexOf("http", i);
            if (h < 0) break;
            boolean ok = text.regionMatches(true, h, "http://", 0, 7) || text.regionMatches(true, h, "https://", 0, 8);
            if (!ok) {
                i = h + 4;
                continue;
            }
            int e = h;
            while (e < n && e - h < 500) {
                char c = text.charAt(e);
                if (isSpace(c) || c == '<' || c == '>' || c == '"' || c == '\'' || c == ')') break;
                e++;
            }
            String url = text.substring(h, e);
            while (!url.isEmpty() && ".,;:!?".indexOf(url.charAt(url.length() - 1)) >= 0) {
                url = url.substring(0, url.length() - 1);
            }
            out.add(url);
            i = Math.max(e, h + 4);
        }
        return out;
    }

    static boolean isWebUrl(String href) {
        if (href == null) return false;
        String h = href.trim();
        return h.regionMatches(true, 0, "http://", 0, 7) || h.regionMatches(true, 0, "https://", 0, 8)
                || h.startsWith("//");
    }

    /** Host chữ thường của URL (bỏ userinfo, cổng, dấu chấm cuối); "" nếu không rút được. */
    static String host(String url) {
        if (url == null) return "";
        String u = url.trim();
        int se = u.indexOf("://");
        if (se >= 0) u = u.substring(se + 3);
        else if (u.startsWith("//")) u = u.substring(2);
        int end = u.length();
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == '\\') {
                end = i;
                break;
            }
        }
        String auth = u.substring(0, end);
        int at = auth.lastIndexOf('@');
        if (at >= 0) auth = auth.substring(at + 1);
        if (auth.startsWith("[")) {
            int rb = auth.indexOf(']');
            return rb > 0 ? auth.substring(0, rb + 1).toLowerCase(Locale.ROOT) : "";
        }
        int colon = auth.indexOf(':');
        if (colon >= 0) auth = auth.substring(0, colon);
        while (auth.endsWith(".")) auth = auth.substring(0, auth.length() - 1);
        return auth.toLowerCase(Locale.ROOT);
    }

    static boolean isIpHost(String host) {
        if (host == null || host.isEmpty()) return false;
        if (host.startsWith("[")) return true;
        boolean digitsDots = true;
        int dots = 0;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') dots++;
            else if (c < '0' || c > '9') {
                digitsDots = false;
                break;
            }
        }
        if (digitsDots && dots == 3) return true;
        if (digitsDots && dots == 0 && host.length() >= 8) return true;
        return host.startsWith("0x") && host.length() > 4 && host.indexOf('.') < 0;
    }

    static boolean looksLikeDomain(String h) {
        if (h == null || h.length() < 4 || h.length() > 255 || h.indexOf('.') <= 0) return false;
        if (h.startsWith(".") || h.endsWith(".") || h.contains("..")) return false;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-')) return false;
        }
        String tld = h.substring(h.lastIndexOf('.') + 1);
        if (tld.length() < 2 || tld.length() > 24) return false;
        for (int i = 0; i < tld.length(); i++) {
            char c = tld.charAt(i);
            if (c < 'a' || c > 'z') return false;
        }
        return true;
    }

    /** Nếu chữ hiển thị của liên kết trông như một URL/domain thì trả host của nó, ngược lại null. */
    static String textHost(String t) {
        if (t == null) return null;
        String s = t.trim();
        if (s.isEmpty() || s.length() > 120) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isSpace(c) || c == '@') return null;
        }
        String low = s.toLowerCase(Locale.ROOT);
        String h;
        if (low.startsWith("http://") || low.startsWith("https://")) h = host(s);
        else h = host("http://" + s);
        return looksLikeDomain(h) ? h : null;
    }
}