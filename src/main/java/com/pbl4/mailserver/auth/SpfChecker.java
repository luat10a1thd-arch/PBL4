package com.pbl4.mailserver.auth;

import com.pbl4.mailserver.dns.DnsException;
import com.pbl4.mailserver.dns.DnsLookup;
import com.pbl4.mailserver.dns.MxRecord;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kiểm tra SPF theo RFC 7208.
 * Hỗ trợ: all, a, mx, ip4, ip6, include, exists, redirect; qualifier + - ~ ?; CIDR; macro %{s l o d i h v p c}
 * kèm bộ biến đổi (số, r, dấu phân cách) và %% %_ %-.
 * Giới hạn: tối đa 10 lượt tra DNS (include/a/mx/ptr/exists/redirect), tối đa 2 lượt tra rỗng (void),
 * tối đa 10 tên MX mỗi mechanism mx. "ptr" được phân tích cú pháp nhưng luôn coi là KHÔNG khớp (không tra DNS);
 * "exp" bị bỏ qua. Lỗi DNS tạm = TEMPERROR; cú pháp sai / vượt giới hạn = PERMERROR.
 */
public final class SpfChecker {

    static final int MAX_DNS_LOOKUPS = 10;
    static final int MAX_VOID_LOOKUPS = 2;
    static final int MAX_MX_NAMES = 10;

    private static final Pattern DOMAIN_RE = Pattern.compile(
            "[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?(\\.[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?)*");
    private static final Pattern MODIFIER = Pattern.compile("^([A-Za-z][A-Za-z0-9._-]*)=(.*)$");
    private static final Pattern CIDR = Pattern.compile("^(?:/(\\d{1,3}))?(?://(\\d{1,3}))?$");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]+");

    private SpfChecker() {}

    public static SpfResult check(String clientIp, String heloName, String envelopeFrom) {
        return check(clientIp, heloName, envelopeFrom, DnsLookup.get());
    }

    static SpfResult check(String clientIp, String heloName, String envelopeFrom, DnsLookup dns) {
        try {
            InetAddress ip = parseIp(clientIp);
            if (ip == null) {
                return SpfResult.NONE;
            }
            String helo = heloName == null ? "" : heloName.trim();
            String from = envelopeFrom == null ? "" : envelopeFrom.trim();
            if (from.startsWith("<") && from.endsWith(">")) {
                from = from.substring(1, from.length() - 1).trim();
            }
            String local;
            String domain;
            if (from.isEmpty()) {                       // null sender: kiểm tra theo HELO (RFC 7208 2.4)
                local = "postmaster";
                domain = helo;
            } else {
                int at = from.lastIndexOf('@');
                if (at < 0) {
                    local = "postmaster";
                    domain = from;
                } else {
                    local = at == 0 ? "postmaster" : from.substring(0, at);
                    domain = from.substring(at + 1);
                }
            }
            domain = stripDot(domain);
            if (!validDomain(domain)) {
                return SpfResult.NONE;
            }
            return new Eval(dns, ip, local, domain, helo).checkHost(domain);
        } catch (SpfError e) {
            return e.result;
        } catch (RuntimeException e) {
            return SpfResult.TEMPERROR;
        }
    }

    // ------------------------------------------------------------------ tiện ích

    static InetAddress parseIp(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.startsWith("[") && t.endsWith("]")) {
            t = t.substring(1, t.length() - 1);
        }
        if (t.regionMatches(true, 0, "IPv6:", 0, 5)) {
            t = t.substring(5);
        }
        try {
            if (IPV4.matcher(t).matches() || (t.indexOf(':') >= 0 && IPV6.matcher(t).matches())) {
                return InetAddress.getByName(t);   // chỉ literal nên không gây tra DNS
            }
        } catch (UnknownHostException ignored) {
            // không hợp lệ
        }
        return null;
    }

    private static String stripDot(String d) {
        return d.endsWith(".") ? d.substring(0, d.length() - 1) : d;
    }

    static boolean validDomain(String d) {
        return d != null && !d.isEmpty() && d.length() <= 253 && DOMAIN_RE.matcher(d).matches();
    }

    static boolean prefixMatch(byte[] a, byte[] b, int bits) {
        if (a.length != b.length) {
            return false;
        }
        int full = bits / 8;
        int rem = bits % 8;
        for (int i = 0; i < full; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        if (rem > 0) {
            int mask = (0xFF << (8 - rem)) & 0xFF;
            return (a[full] & mask) == (b[full] & mask);
        }
        return true;
    }

    private static SpfResult qualifierResult(char q) {
        switch (q) {
            case '-': return SpfResult.FAIL;
            case '~': return SpfResult.SOFTFAIL;
            case '?': return SpfResult.NEUTRAL;
            default: return SpfResult.PASS;
        }
    }

    private static int firstSlashOutsideBraces(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == '/' && depth <= 0) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isSpfRecord(String t) {
        if (t == null || t.length() < 6 || !t.regionMatches(true, 0, "v=spf1", 0, 6)) {
            return false;
        }
        return t.length() == 6 || Character.isWhitespace(t.charAt(6));
    }

    private static SpfError perm() {
        return new SpfError(SpfResult.PERMERROR);
    }

    /** Lỗi điều khiển luồng, không có stack trace. */
    private static final class SpfError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient SpfResult result;

        SpfError(SpfResult result) {
            super(result.name(), null, false, false);
            this.result = result;
        }
    }

    private static final class Term {
        final char qualifier;
        final String name;
        final String arg;        // domain-spec (có thể chứa macro) hoặc null
        final int c4;            // CIDR v4 cho a/mx
        final int c6;            // CIDR v6 cho a/mx
        final byte[] net;        // ip4/ip6
        final int bits;          // độ dài tiền tố ip4/ip6

        Term(char q, String name, String arg, int c4, int c6, byte[] net, int bits) {
            this.qualifier = q;
            this.name = name;
            this.arg = arg;
            this.c4 = c4;
            this.c6 = c6;
            this.net = net;
            this.bits = bits;
        }
    }

    private static final class Parsed {
        final List<Term> terms = new ArrayList<>();
        String redirect;
    }

    // ------------------------------------------------------------------ phân tích bản ghi

    private static Parsed parse(String record) {
        Parsed out = new Parsed();
        String body = record.substring(6).trim();
        boolean exp = false;
        if (body.isEmpty()) {
            return out;
        }
        for (String tok : body.split("\\s+")) {
            Matcher m = MODIFIER.matcher(tok);
            if (m.matches()) {
                String n = m.group(1).toLowerCase(Locale.ROOT);
                if (n.equals("redirect")) {
                    if (out.redirect != null || m.group(2).isEmpty()) {
                        throw perm();
                    }
                    out.redirect = m.group(2);
                } else if (n.equals("exp")) {
                    if (exp) {
                        throw perm();
                    }
                    exp = true;
                }
                continue;    // modifier lạ: bỏ qua theo RFC
            }
            out.terms.add(parseMechanism(tok));
        }
        return out;
    }

    private static Term parseMechanism(String tok) {
        char q = '+';
        String s = tok;
        if ("+-~?".indexOf(s.charAt(0)) >= 0) {
            q = s.charAt(0);
            s = s.substring(1);
        }
        int e = 0;
        while (e < s.length() && Character.isLetterOrDigit(s.charAt(e))) {
            e++;
        }
        String name = s.substring(0, e).toLowerCase(Locale.ROOT);
        String tail = s.substring(e);
        switch (name) {
            case "all":
                if (!tail.isEmpty()) {
                    throw perm();
                }
                return new Term(q, name, null, 32, 128, null, 0);
            case "include":
            case "exists":
                if (!tail.startsWith(":") || tail.length() == 1) {
                    throw perm();
                }
                return new Term(q, name, tail.substring(1), 32, 128, null, 0);
            case "a":
            case "mx":
                return domainCidr(q, name, tail);
            case "ptr":
                if (!tail.isEmpty() && !tail.startsWith(":")) {
                    throw perm();
                }
                return new Term(q, name, null, 32, 128, null, 0);
            case "ip4":
            case "ip6":
                return ipTerm(q, name, tail);
            default:
                throw perm();
        }
    }

    private static Term domainCidr(char q, String name, String tail) {
        String spec = null;
        String cidr = "";
        if (tail.startsWith(":")) {
            String r = tail.substring(1);
            int slash = firstSlashOutsideBraces(r);
            if (slash >= 0) {
                cidr = r.substring(slash);
                r = r.substring(0, slash);
            }
            if (r.isEmpty()) {
                throw perm();
            }
            spec = r;
        } else if (tail.startsWith("/")) {
            cidr = tail;
        } else if (!tail.isEmpty()) {
            throw perm();
        }
        Matcher m = CIDR.matcher(cidr);
        if (!m.matches()) {
            throw perm();
        }
        int c4 = m.group(1) == null ? 32 : Integer.parseInt(m.group(1));
        int c6 = m.group(2) == null ? 128 : Integer.parseInt(m.group(2));
        if (c4 > 32 || c6 > 128) {
            throw perm();
        }
        return new Term(q, name, spec, c4, c6, null, 0);
    }

    private static Term ipTerm(char q, String name, String tail) {
        if (!tail.startsWith(":") || tail.length() == 1) {
            throw perm();
        }
        String r = tail.substring(1);
        int slash = r.indexOf('/');
        String addr = slash < 0 ? r : r.substring(0, slash);
        String len = slash < 0 ? null : r.substring(slash + 1);
        boolean v4 = name.equals("ip4");
        if (v4 ? !IPV4.matcher(addr).matches() : (addr.indexOf(':') < 0 || !IPV6.matcher(addr).matches())) {
            throw perm();
        }
        byte[] net;
        try {
            net = InetAddress.getByName(addr).getAddress();
        } catch (UnknownHostException e) {
            throw perm();
        }
        int max = net.length * 8;
        int bits = max;
        if (len != null) {
            if (!len.matches("\\d{1,3}")) {
                throw perm();
            }
            bits = Integer.parseInt(len);
            if (bits > max) {
                throw perm();
            }
        }
        return new Term(q, name, null, 32, 128, net, bits);
    }

    // ------------------------------------------------------------------ đánh giá

    private static final class Eval {
        final DnsLookup dns;
        final byte[] ip;
        final String ipText;
        final String local;
        final String senderDomain;
        final String helo;
        int lookups;
        int voids;

        Eval(DnsLookup dns, InetAddress ip, String local, String senderDomain, String helo) {
            this.dns = dns;
            this.ip = ip.getAddress();
            this.ipText = ip.getHostAddress();
            this.local = local;
            this.senderDomain = senderDomain;
            this.helo = helo;
        }

        SpfResult checkHost(String domain) {
            List<String> txts;
            try {
                txts = dns.txt(domain);
            } catch (DnsException e) {
                if (e.temporary()) {
                    throw new SpfError(SpfResult.TEMPERROR);
                }
                return SpfResult.NONE;
            }
            String rec = null;
            int count = 0;
            if (txts != null) {
                for (String t : txts) {
                    if (isSpfRecord(t)) {
                        count++;
                        rec = t;
                    }
                }
            }
            if (count == 0) {
                return SpfResult.NONE;
            }
            if (count > 1) {
                throw perm();
            }
            Parsed p = parse(rec);       // phân tích toàn bộ trước: cú pháp sai ở đâu cũng là PERMERROR
            for (Term t : p.terms) {
                if (matches(t, domain)) {
                    return qualifierResult(t.qualifier);
                }
            }
            if (p.redirect != null) {
                countLookup();
                String target = target(expand(p.redirect, domain));
                if (target == null) {
                    throw perm();
                }
                SpfResult r = checkHost(target);
                if (r == SpfResult.NONE) {
                    throw perm();
                }
                return r;
            }
            return SpfResult.NEUTRAL;
        }

        private boolean matches(Term t, String domain) {
            switch (t.name) {
                case "all":
                    return true;
                case "ip4":
                case "ip6":
                    return t.net.length == ip.length && prefixMatch(t.net, ip, t.bits);
                case "include": {
                    countLookup();
                    String target = target(expand(t.arg, domain));
                    if (target == null) {
                        throw perm();
                    }
                    SpfResult r = checkHost(target);
                    switch (r) {
                        case PASS:
                            return true;
                        case FAIL:
                        case SOFTFAIL:
                        case NEUTRAL:
                            return false;
                        case TEMPERROR:
                            throw new SpfError(SpfResult.TEMPERROR);
                        default:      // NONE, PERMERROR
                            throw perm();
                    }
                }
                case "a": {
                    String target = t.arg == null ? domain : target(expand(t.arg, domain));
                    if (target == null) {
                        return false;
                    }
                    countLookup();
                    return anyMatch(addrs(target), t);
                }
                case "mx": {
                    String target = t.arg == null ? domain : target(expand(t.arg, domain));
                    if (target == null) {
                        return false;
                    }
                    countLookup();
                    return mxMatch(target, t);
                }
                case "exists": {
                    String target = target(expand(t.arg, domain));
                    if (target == null) {
                        return false;
                    }
                    countLookup();
                    List<InetAddress> l = addrs(target);
                    for (InetAddress a : l) {
                        if (a instanceof Inet4Address) {
                            return true;
                        }
                    }
                    return false;
                }
                case "ptr":
                    return false;    // theo yêu cầu: coi như không khớp
                default:
                    throw perm();
            }
        }

        private boolean mxMatch(String target, Term t) {
            List<MxRecord> mxs;
            try {
                mxs = dns.mx(target);
            } catch (DnsException e) {
                if (e.temporary()) {
                    throw new SpfError(SpfResult.TEMPERROR);
                }
                mxs = List.of();
            }
            if (mxs == null || mxs.isEmpty()) {
                countVoid();
                return false;
            }
            int n = 0;
            for (MxRecord mx : mxs) {
                if (mx.host() == null || mx.host().equals(".") || mx.host().isEmpty()) {
                    continue;            // null-MX
                }
                if (++n > MAX_MX_NAMES) {
                    throw perm();
                }
                if (anyMatch(addrs(stripDot(mx.host())), t)) {
                    return true;
                }
            }
            return false;
        }

        private boolean anyMatch(List<InetAddress> l, Term t) {
            int bits = ip.length == 4 ? t.c4 : t.c6;
            for (InetAddress a : l) {
                byte[] b = a.getAddress();
                if (b.length == ip.length && prefixMatch(b, ip, bits)) {
                    return true;
                }
            }
            return false;
        }

        /** Tra A+AAAA; danh sách rỗng được tính là void lookup. */
        private List<InetAddress> addrs(String host) {
            List<InetAddress> l;
            try {
                l = dns.addresses(host);
            } catch (DnsException e) {
                if (e.temporary()) {
                    throw new SpfError(SpfResult.TEMPERROR);
                }
                l = List.of();
            }
            if (l == null || l.isEmpty()) {
                countVoid();
                return List.of();
            }
            return l;
        }

        private void countLookup() {
            if (++lookups > MAX_DNS_LOOKUPS) {
                throw perm();
            }
        }

        private void countVoid() {
            if (++voids > MAX_VOID_LOOKUPS) {
                throw perm();
            }
        }

        private static String target(String expanded) {
            String d = stripDot(expanded);
            return validDomain(d) ? d : null;
        }

        // -------------------------------------------------------------- macro

        String expand(String spec, String domain) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < spec.length(); i++) {
                char c = spec.charAt(i);
                if (c != '%') {
                    sb.append(c);
                    continue;
                }
                if (++i >= spec.length()) {
                    throw perm();
                }
                char n = spec.charAt(i);
                if (n == '%') {
                    sb.append('%');
                } else if (n == '_') {
                    sb.append(' ');
                } else if (n == '-') {
                    sb.append("%20");
                } else if (n == '{') {
                    int close = spec.indexOf('}', i);
                    if (close < 0) {
                        throw perm();
                    }
                    sb.append(macro(spec.substring(i + 1, close), domain));
                    i = close;
                } else {
                    throw perm();
                }
            }
            String r = sb.toString();
            while (r.length() > 253) {          // RFC 7208 7.3: cắt nhãn bên trái cho tới khi đủ ngắn
                int dot = r.indexOf('.');
                if (dot < 0) {
                    break;
                }
                r = r.substring(dot + 1);
            }
            return r;
        }

        private String macro(String body, String domain) {
            if (body.isEmpty()) {
                throw perm();
            }
            char letter = body.charAt(0);
            String rest = body.substring(1);
            int p = 0;
            while (p < rest.length() && Character.isDigit(rest.charAt(p))) {
                p++;
            }
            Integer keep = null;
            if (p > 0) {
                try {
                    keep = Integer.parseInt(rest.substring(0, p));
                } catch (NumberFormatException e) {
                    throw perm();
                }
                if (keep == 0) {
                    throw perm();
                }
            }
            boolean reverse = false;
            if (p < rest.length() && (rest.charAt(p) == 'r' || rest.charAt(p) == 'R')) {
                reverse = true;
                p++;
            }
            String delims = rest.substring(p);
            for (char d : delims.toCharArray()) {
                if (".-+,/_=".indexOf(d) < 0) {
                    throw perm();
                }
            }
            if (delims.isEmpty()) {
                delims = ".";
            }
            String value;
            switch (Character.toLowerCase(letter)) {
                case 's': value = local + "@" + senderDomain; break;
                case 'l': value = local; break;
                case 'o': value = senderDomain; break;
                case 'd': value = domain; break;
                case 'i': value = dottedIp(); break;
                case 'h': value = helo.isEmpty() ? "unknown" : helo; break;
                case 'v': value = ip.length == 4 ? "in-addr" : "ip6"; break;
                case 'p': value = "unknown"; break;
                case 'c': value = ipText; break;
                case 'r': value = "unknown"; break;
                case 't': value = Long.toString(System.currentTimeMillis() / 1000); break;
                default: throw perm();
            }
            List<String> parts = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (char ch : value.toCharArray()) {
                if (delims.indexOf(ch) >= 0) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(ch);
                }
            }
            parts.add(cur.toString());
            if (reverse) {
                Collections.reverse(parts);
            }
            if (keep != null && keep < parts.size()) {
                parts = parts.subList(parts.size() - keep, parts.size());
            }
            String joined = String.join(".", parts);
            return Character.isUpperCase(letter) ? urlEncode(joined) : joined;
        }

        private String dottedIp() {
            if (ip.length == 4) {
                return ipText;
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : ip) {
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append('.').append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        }

        private static String urlEncode(String s) {
            StringBuilder sb = new StringBuilder();
            for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                char c = (char) (b & 0xFF);
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "-_.~".indexOf(c) >= 0) {
                    sb.append(c);
                } else {
                    sb.append(String.format("%%%02X", b & 0xFF));
                }
            }
            return sb.toString();
        }
    }
}