package com.pbl4.mailserver.auth;

import com.pbl4.mailserver.auth.InboundVerdict.Action;
import com.pbl4.mailserver.config.ServerConfig;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Xác thực thư đến: SPF + DKIM + DMARC, chèn Authentication-Results, ra quyết định.
 *
 * Chính sách (theo thứ tự):
 *  1. DMARC_ENFORCE và (nhiều header From) -> REJECT 550 5.7.1
 *  2. DMARC_ENFORCE và DMARC lỗi tạm mà chính sách có thể là quarantine/reject -> TEMPFAIL 451 4.7.1
 *  3. DMARC_ENFORCE và DMARC fail với p=reject -> REJECT 550 5.7.1
 *  4. SPF_MODE=ENFORCE: SPF fail -> REJECT 550 5.7.23; SPF temperror (DMARC không pass) -> TEMPFAIL 451 4.7.1
 *  5. DMARC_ENFORCE và DMARC fail với p=quarantine -> SPAM
 *  6. còn lại ACCEPT.
 * Mọi Authentication-Results có authserv-id trùng MAIL_HOSTNAME của mình trong thư đến đều bị gỡ (chống giả mạo).
 * Thông điệp SMTP chỉ dùng ASCII (RFC 5321).
 */
public final class DefaultInboundAuthenticator implements InboundAuthenticator {

    private static final Logger LOG = Logger.getLogger(DefaultInboundAuthenticator.class.getName());
    private static final Pattern DOMAIN_AT = Pattern.compile("@([A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?)");
    private static final Pattern COMMENT = Pattern.compile("\\([^)]*\\)");
    private static final Pattern QUOTED = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    /** Cấu hình lúc chạy. spfMode: OFF | MARK | ENFORCE. */
    public record Settings(String authservId, String spfMode, boolean dmarcEnforce) {

        /**
         * authservId lấy từ ServerConfig.MAIL_HOSTNAME (cùng nguồn với Received và HELO);
         * spfMode và dmarcEnforce vẫn đọc từ biến môi trường PBL4_*.
         */
        static Settings fromEnv() {
            String host = ServerConfig.MAIL_HOSTNAME;
            String spf = env("PBL4_SPF_MODE", "MARK").toUpperCase(Locale.ROOT);
            boolean dmarc = !"false".equalsIgnoreCase(env("PBL4_DMARC_ENFORCE", "true"));
            return new Settings(host, spf, dmarc);
        }

        private static String env(String k, String def) {
            String v = System.getenv(k);
            return v == null || v.isBlank() ? def : v.trim();
        }
    }

    private final Supplier<Settings> settings;

    public DefaultInboundAuthenticator() {
        this(Settings::fromEnv);
    }

    DefaultInboundAuthenticator(Supplier<Settings> settings) {
        this.settings = settings;
    }

    @Override
    public InboundVerdict evaluate(InboundContext ctx, byte[] raw) {
        try {
            return doEvaluate(ctx, raw);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Lỗi bất ngờ khi xác thực thư đến, cho qua (ACCEPT): " + e, e);
            return accept(raw);
        }
    }

    private InboundVerdict doEvaluate(InboundContext ctx, byte[] raw) {
        if (raw == null) {
            return accept(null);
        }
        Settings st = settings.get();
        String spfMode = st.spfMode() == null ? "MARK" : st.spfMode().toUpperCase(Locale.ROOT);
        boolean spfOff = spfMode.equals("OFF");
        boolean spfEnforce = spfMode.equals("ENFORCE");
        String authserv = st.authservId().toLowerCase(Locale.ROOT);

        // ---- tách header / thân (header được xem là Latin-1 để không mất byte nào)
        int hdrEnd;
        if (raw.length >= 2 && raw[0] == '\r' && raw[1] == '\n') {
            hdrEnd = 0;
        } else {
            int i = indexOfBlankLine(raw);
            hdrEnd = i < 0 ? raw.length : i + 2;
        }
        List<Field> fields = splitHeaders(new String(raw, 0, hdrEnd, StandardCharsets.ISO_8859_1));
        List<Field> kept = new ArrayList<>();
        List<String> fromValues = new ArrayList<>();
                for (Field f : fields) {
            if (f.name.equalsIgnoreCase("authentication-results")) {
                continue;     // thư từ ngoài vào cổng MX: gỡ MỌI Authentication-Results có sẵn (người gửi có thể tự chèn giả)
            }
            if (f.name.equalsIgnoreCase("from")) {
                fromValues.add(f.value());
            }
            kept.add(f);
        }

        // ---- danh tính
        String helo = ctx.heloName() == null ? "" : ctx.heloName().trim();
        String env = ctx.envelopeFrom() == null ? "" : ctx.envelopeFrom().trim();
        if (env.startsWith("<") && env.endsWith(">")) {
            env = env.substring(1, env.length() - 1).trim();
        }
        String spfDomain = env.isEmpty() ? helo : (env.indexOf('@') >= 0 ? env.substring(env.lastIndexOf('@') + 1) : env);

        // ---- SPF, DKIM
        SpfResult spf = spfOff ? null : SpfChecker.check(ctx.clientIp(), helo, env);
        List<DkimResult> dkim = DkimVerifier.verify(raw);
        if (dkim == null) {
            dkim = List.of();
        }

        // ---- DMARC
        Set<String> fromDomains = new LinkedHashSet<>();
        for (String v : fromValues) {
            fromDomains.addAll(extractDomains(v));
        }
        boolean multiFrom = fromValues.size() > 1 || fromDomains.size() > 1;
        DmarcResult dmarc;
        if (multiFrom) {
            dmarc = DmarcResult.of("permerror", null, "From mơ hồ (nhiều header/địa chỉ)");
        } else if (fromDomains.isEmpty()) {
            dmarc = DmarcResult.of("none", null, "không có From hợp lệ");
        } else {
            dmarc = DmarcEvaluator.evaluate(fromDomains.iterator().next(), spf == null ? SpfResult.NONE : spf,
                    spfDomain, dkim);
        }

        // ---- quyết định
        boolean enforce = st.dmarcEnforce();
        InboundVerdict verdict = null;
        String fd = dmarc.fromDomain() == null ? "" : clean(dmarc.fromDomain());
        if (enforce && multiFrom) {
            verdict = new InboundVerdict(Action.REJECT, 550,
                    "5.7.1 Message rejected: ambiguous From header", raw);
        } else if (enforce && dmarc.result().equals("temperror") && !"none".equals(dmarc.policy())) {
            verdict = tempfail(raw);
        } else if (enforce && dmarc.result().equals("fail") && dmarc.disposition().equals("reject")) {
            verdict = new InboundVerdict(Action.REJECT, 550,
                    "5.7.1 Message rejected by DMARC policy of " + fd + " (p=reject)", raw);
        } else if (spfEnforce && spf == SpfResult.FAIL) {
            verdict = new InboundVerdict(Action.REJECT, 550,
                    "5.7.23 SPF validation failed: " + clean(ctx.clientIp()) + " is not allowed to send for "
                            + clean(spfDomain), raw);
        } else if (spfEnforce && spf == SpfResult.TEMPERROR && !dmarc.result().equals("pass")) {
            verdict = tempfail(raw);
        }
        if (verdict != null) {
            return verdict;
        }

        // ---- chèn Authentication-Results rồi ghép lại thư
        String ar = buildAuthResults(st.authservId(), spf, env, helo, dkim, dmarc, enforce);
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length + ar.length() + 64);
        out.writeBytes(ar.getBytes(StandardCharsets.ISO_8859_1));
        StringBuilder last = new StringBuilder();
        for (Field f : kept) {
            out.writeBytes(f.text.getBytes(StandardCharsets.ISO_8859_1));
            last.setLength(0);
            last.append(f.text);
        }
        if (hdrEnd == raw.length && last.length() > 0 && last.charAt(last.length() - 1) != '\n') {
            out.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        out.write(raw, hdrEnd, raw.length - hdrEnd);
        byte[] result = out.toByteArray();

        if (enforce && dmarc.result().equals("fail") && dmarc.disposition().equals("quarantine")) {
            return new InboundVerdict(Action.SPAM, 250, "2.0.0 OK", result);
        }
        return new InboundVerdict(Action.ACCEPT, 250, "2.0.0 OK", result);
    }

    // ------------------------------------------------------------------ Authentication-Results

    private static String buildAuthResults(String authserv, SpfResult spf, String env, String helo,
                                           List<DkimResult> dkim, DmarcResult dmarc, boolean enforce) {
        List<String> methods = new ArrayList<>();
        if (spf != null) {
            String who = env.isEmpty() ? "smtp.helo=" + token(helo) : "smtp.mailfrom=" + token(env);
            methods.add("spf=" + spf.name().toLowerCase(Locale.ROOT) + " " + who);
        }
        if (dkim.isEmpty()) {
            methods.add("dkim=none");
        }
        for (DkimResult r : dkim) {
            StringBuilder m = new StringBuilder("dkim=").append(token(r.result()));
            if (r.domain() != null && !"none".equals(r.result())) {
                m.append(" header.d=").append(token(r.domain()));
            }
            if (r.selector() != null && !"none".equals(r.result())) {
                m.append(" header.s=").append(token(r.selector()));
            }
            methods.add(m.toString());
        }
        StringBuilder d = new StringBuilder("dmarc=").append(dmarc.result());
        if (dmarc.policy() != null) {
            d.append(" (p=").append(dmarc.policy()).append(" dis=").append(dmarc.disposition());
            if (!enforce) {
                d.append("; enforcement off");
            }
            d.append(')');
        }
        if (dmarc.fromDomain() != null) {
            d.append(" header.from=").append(token(dmarc.fromDomain()));
        }
        methods.add(d.toString());

        StringBuilder sb = new StringBuilder("Authentication-Results: ").append(token(authserv));
        for (String m : methods) {
            sb.append(";\r\n\t").append(m);
        }
        return sb.append("\r\n").toString();
    }

    private static String token(String s) {
        if (s == null || s.isEmpty()) {
            return "unknown";
        }
        String c = s.length() > 200 ? s.substring(0, 200) : s;
        return c.replaceAll("[^A-Za-z0-9@._+=/-]", "_");
    }

    private static String clean(String s) {
        return token(s);
    }

    // ------------------------------------------------------------------ header

    private record Field(String name, String text) {
        /** Giá trị đã gỡ gấp dòng, bỏ phần "Tên:". */
        String value() {
            int c = text.indexOf(':');
            String v = c < 0 ? "" : text.substring(c + 1);
            return v.replace("\r", "").replace("\n", "").trim();
        }
    }

    private static List<Field> splitHeaders(String h) {
        List<StringBuilder> blocks = new ArrayList<>();
        int i = 0;
        while (i < h.length()) {
            int e = h.indexOf('\n', i);
            int end = e < 0 ? h.length() : e + 1;
            String line = h.substring(i, end);
            if (!blocks.isEmpty() && (line.startsWith(" ") || line.startsWith("\t"))) {
                blocks.get(blocks.size() - 1).append(line);
            } else {
                blocks.add(new StringBuilder(line));
            }
            i = end;
        }
        List<Field> out = new ArrayList<>();
        for (StringBuilder b : blocks) {
            String t = b.toString();
            int c = t.indexOf(':');
            out.add(new Field(c < 0 ? "" : t.substring(0, c).trim(), t));
        }
        return out;
    }

    /** authserv-id (chữ thường) của một Authentication-Results; bỏ qua comment đứng đầu. */
    private static String authservOf(String value) {
        String v = value.trim();
        while (v.startsWith("(")) {
            int close = v.indexOf(')');
            if (close < 0) {
                return "";
            }
            v = v.substring(close + 1).trim();
        }
        int end = 0;
        while (end < v.length() && !Character.isWhitespace(v.charAt(end)) && v.charAt(end) != ';') {
            end++;
        }
        return v.substring(0, end).toLowerCase(Locale.ROOT);
    }

    private static Set<String> extractDomains(String fromValue) {
        String s = QUOTED.matcher(fromValue).replaceAll(" ");
        s = COMMENT.matcher(s).replaceAll(" ");
        Set<String> out = new LinkedHashSet<>();
        Matcher m = DOMAIN_AT.matcher(s);
        while (m.find()) {
            out.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static int indexOfBlankLine(byte[] b) {
        for (int i = 0; i + 3 < b.length; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static InboundVerdict tempfail(byte[] raw) {
        return new InboundVerdict(Action.TEMPFAIL, 451,
                "4.7.1 Temporary authentication failure, please try again later", raw);
    }

    private static InboundVerdict accept(byte[] raw) {
        return new InboundVerdict(Action.ACCEPT, 250, "2.0.0 OK", raw);
    }
}