package com.pbl4.mailserver.auth;

import com.pbl4.mailserver.auth.DkimCommon.Hdr;
import com.pbl4.mailserver.auth.DkimCommon.Parsed;
import com.pbl4.mailserver.dns.DnsException;
import com.pbl4.mailserver.dns.DnsLookup;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Xác thực DKIM (RFC 6376 + RFC 8301): rsa-sha256, canonicalization simple/relaxed cho cả header và thân.
 * Kiểm tra bh (băm thân) và b (chữ ký), lấy khóa công khai từ DNS TXT selector._domainkey.domain.
 *
 * Quy ước kết quả:
 *   none      - thư không có chữ ký DKIM nào
 *   pass      - chữ ký hợp lệ
 *   fail      - chữ ký có cú pháp đúng nhưng sai (băm thân/chữ ký không khớp, hết hạn, khóa bị thu hồi, rsa-sha1, khóa &lt; 1024 bit)
 *   temperror - lỗi tạm khi tra DNS
 *   permerror - chữ ký/khóa sai cú pháp, thiếu thẻ bắt buộc, không tìm thấy bản ghi khóa
 */
public final class DkimVerifier {

    /** Số chữ ký tối đa được xét (từ trên xuống); phần còn lại bị bỏ qua để chống lạm dụng CPU/DNS. */
    static final int MAX_SIGNATURES = 5;
    /** Phần header lớn hơn mức này => permerror duy nhất. */
    static final int MAX_HEADER_BYTES = 256 * 1024;
    /** Một header DKIM-Signature dài hơn mức này bị coi là không hợp lệ. */
    static final int MAX_SIGNATURE_HEADER_CHARS = 16 * 1024;
    static final int MIN_KEY_BITS = 1024;

    private DkimVerifier() {}

    public static List<DkimResult> verify(byte[] raw) {
        return verify(raw, DnsLookup.get(), System.currentTimeMillis() / 1000);
    }

    static List<DkimResult> verify(byte[] raw, DnsLookup dns, long nowSec) {
        if (raw == null) {
            return List.of(new DkimResult("none", null, null, "không có thư"));
        }
        Parsed p = DkimCommon.parse(raw);
        if (p.headerBytes() > MAX_HEADER_BYTES) {
            return List.of(new DkimResult("permerror", null, null,
                    "phần header quá lớn (" + p.headerBytes() + " byte)"));
        }
        List<Hdr> sigs = new ArrayList<>();
        for (Hdr h : p.headers()) {
            if (h.lc().equals("dkim-signature")) {
                sigs.add(h);
            }
        }
        if (sigs.isEmpty()) {
            return List.of(new DkimResult("none", null, null, "thư không có chữ ký DKIM"));
        }
        List<DkimResult> out = new ArrayList<>();
        Map<String, byte[]> bodyCache = new HashMap<>();
        int n = 0;
        for (Hdr h : sigs) {
            if (n++ >= MAX_SIGNATURES) {
                break;
            }
            DkimResult r;
            try {
                r = verifyOne(raw, p, h, dns, nowSec, bodyCache);
            } catch (Exception e) {
                r = new DkimResult("permerror", null, null, "lỗi khi xác thực: " + e);
            }
            out.add(r);
        }
        return out;
    }

    private static DkimResult verifyOne(byte[] raw, Parsed p, Hdr sigHdr, DnsLookup dns, long nowSec,
                                        Map<String, byte[]> bodyCache) throws Exception {
        String rawHeader = sigHdr.raw();
        if (rawHeader.length() > MAX_SIGNATURE_HEADER_CHARS) {
            return res("permerror", null, null, "header DKIM-Signature quá dài");
        }
        Map<String, String> tags;
        try {
            tags = DkimCommon.parseTags(rawHeader.substring(rawHeader.indexOf(':') + 1));
        } catch (IllegalArgumentException e) {
            return res("permerror", null, null, "cú pháp chữ ký sai: " + e.getMessage());
        }
        String d = tags.get("d");
        String s = tags.get("s");
        for (String req : new String[] {"v", "a", "b", "bh", "d", "h", "s"}) {
            if (!tags.containsKey(req) || tags.get(req).isEmpty() && !req.equals("b")) {
                return res("permerror", d, s, "thiếu thẻ bắt buộc " + req + "=");
            }
        }
        if (!"1".equals(tags.get("v"))) {
            return res("permerror", d, s, "phiên bản chữ ký không hỗ trợ: " + tags.get("v"));
        }
        if (!validName(d) || !validName(s)) {
            return res("permerror", d, s, "d= hoặc s= chứa ký tự không hợp lệ");
        }
        d = d.toLowerCase(Locale.ROOT);

        String alg = tags.get("a").toLowerCase(Locale.ROOT);
        if (alg.equals("rsa-sha1")) {
            return res("fail", d, s, "rsa-sha1 không còn được chấp nhận (RFC 8301)");
        }
        if (!alg.equals("rsa-sha256")) {
            return res("permerror", d, s, "thuật toán không hỗ trợ: " + alg);
        }

        String[] canon = tags.getOrDefault("c", "simple/simple").toLowerCase(Locale.ROOT).split("/", -1);
        String hc = canon[0];
        String bc = canon.length > 1 ? canon[1] : "simple";
        if (canon.length > 2 || !(hc.equals("simple") || hc.equals("relaxed"))
                || !(bc.equals("simple") || bc.equals("relaxed"))) {
            return res("permerror", d, s, "canonicalization không hợp lệ: " + tags.get("c"));
        }
        boolean hRelaxed = hc.equals("relaxed");
        boolean bRelaxed = bc.equals("relaxed");

        String q = tags.get("q");
        if (q != null && !Arrays.asList(q.toLowerCase(Locale.ROOT).split(":")).contains("dns/txt")) {
            return res("permerror", d, s, "phương thức lấy khóa không hỗ trợ: " + q);
        }

        List<String> hnames = new ArrayList<>();
        for (String x : DkimCommon.stripWs(tags.get("h")).split(":")) {
            if (!x.isEmpty()) {
                hnames.add(x);
            }
        }
        if (!hnames.contains("from") && hnames.stream().noneMatch(x -> x.equalsIgnoreCase("from"))) {
            return res("permerror", d, s, "h= không bao gồm From");
        }

        String identity = tags.get("i");
        String identityDomain = null;
        if (identity != null) {
            int at = identity.lastIndexOf('@');
            identityDomain = (at >= 0 ? identity.substring(at + 1) : identity).toLowerCase(Locale.ROOT);
            if (!(identityDomain.equals(d) || identityDomain.endsWith("." + d))) {
                return res("permerror", d, s, "i= không thuộc domain d=");
            }
        }

        String x = tags.get("x");
        if (x != null) {
            try {
                if (nowSec > Long.parseLong(x.trim())) {
                    return res("fail", d, s, "chữ ký đã hết hạn (x=)");
                }
            } catch (NumberFormatException e) {
                return res("permerror", d, s, "x= không phải số");
            }
        }

        byte[] bh;
        byte[] b;
        try {
            bh = Base64.getDecoder().decode(DkimCommon.stripWs(tags.get("bh")));
            b = Base64.getDecoder().decode(DkimCommon.stripWs(tags.get("b")));
        } catch (IllegalArgumentException e) {
            return res("permerror", d, s, "bh= hoặc b= không phải Base64 hợp lệ");
        }
        if (b.length == 0) {
            return res("permerror", d, s, "b= rỗng");
        }

        // ---- băm thân thư
        byte[] body = bodyCache.computeIfAbsent(bc, k -> DkimCommon.canonBody(raw, p.bodyStart(), bRelaxed));
        String l = tags.get("l");
        if (l != null) {
            long len;
            try {
                len = Long.parseLong(l.trim());
            } catch (NumberFormatException e) {
                return res("permerror", d, s, "l= không phải số");
            }
            if (len < 0) {
                return res("permerror", d, s, "l= âm");
            }
            if (len > body.length) {
                return res("fail", d, s, "l= lớn hơn độ dài thân thư");
            }
            body = Arrays.copyOf(body, (int) len);
        }
        if (!MessageDigest.isEqual(DkimCommon.sha256(body), bh)) {
            return res("fail", d, s, "băm thân thư (bh) không khớp");
        }

        // ---- khóa công khai từ DNS
        String keyName = s + "._domainkey." + d;
        List<String> txts;
        try {
            txts = dns.txt(keyName);
        } catch (DnsException e) {
            return res(e.temporary() ? "temperror" : "permerror", d, s,
                    "tra DNS " + keyName + " thất bại: " + e.getMessage());
        }
        Map<String, String> kt = null;
        for (String t : txts) {
            try {
                Map<String, String> m = DkimCommon.parseTags(t);
                if (m.containsKey("p") && (!m.containsKey("v") || "DKIM1".equals(m.get("v")))) {
                    kt = m;
                    break;
                }
            } catch (IllegalArgumentException ignored) {
                // bản ghi TXT khác (không phải khóa DKIM): bỏ qua
            }
        }
        if (kt == null) {
            return res("permerror", d, s, "không tìm thấy bản ghi khóa DKIM tại " + keyName);
        }
        if (!kt.getOrDefault("k", "rsa").equalsIgnoreCase("rsa")) {
            return res("permerror", d, s, "loại khóa chưa hỗ trợ: k=" + kt.get("k"));
        }
        String keyHashes = kt.get("h");
        if (keyHashes != null && !Arrays.asList(keyHashes.toLowerCase(Locale.ROOT).split(":")).contains("sha256")) {
            return res("permerror", d, s, "khóa không cho phép sha256 (h=" + keyHashes + ")");
        }
        String services = kt.get("s");
        if (services != null) {
            List<String> sv = Arrays.asList(services.toLowerCase(Locale.ROOT).split(":"));
            if (!sv.contains("*") && !sv.contains("email")) {
                return res("permerror", d, s, "khóa không dùng cho email (s=" + services + ")");
            }
        }
        String flags = kt.get("t");
        if (flags != null && identityDomain != null
                && Arrays.asList(flags.toLowerCase(Locale.ROOT).split(":")).contains("s")
                && !identityDomain.equals(d)) {
            return res("fail", d, s, "khóa t=s yêu cầu i= trùng d=");
        }
        String pb64 = DkimCommon.stripWs(kt.get("p"));
        if (pb64.isEmpty()) {
            return res("fail", d, s, "khóa đã bị thu hồi (p= rỗng)");
        }
        RSAPublicKey pub;
        try {
            byte[] der = Base64.getDecoder().decode(pb64);
            pub = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException | ClassCastException e) {
            return res("permerror", d, s, "khóa công khai trong DNS không đọc được");
        }
        if (pub.getModulus().bitLength() < MIN_KEY_BITS) {
            return res("fail", d, s, "khóa RSA quá ngắn (" + pub.getModulus().bitLength() + " bit)");
        }

        // ---- kiểm tra chữ ký
        List<Hdr> selected = DkimCommon.selectHeaders(p.headers(), hnames);
        byte[] headerData = DkimCommon.headerHashInput(selected, hRelaxed);
        String sigText = DkimCommon.stripBValue(rawHeader);
        if (hRelaxed) {
            sigText = DkimCommon.canonHeaderRelaxed(sigText);
        }
        byte[] sigBytes = sigText.getBytes(DkimCommon.L1);
        byte[] data = new byte[headerData.length + sigBytes.length];
        System.arraycopy(headerData, 0, data, 0, headerData.length);
        System.arraycopy(sigBytes, 0, data, headerData.length, sigBytes.length);

        boolean ok;
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(pub);
            sig.update(data);
            ok = sig.verify(b);
        } catch (java.security.SignatureException e) {
            ok = false;
        }
        if (!ok) {
            return res("fail", d, s, "chữ ký (b) không khớp với header đã ký hoặc khóa trong DNS");
        }
        return res("pass", d, s, "chữ ký hợp lệ");
    }

    private static boolean validName(String v) {
        return v != null && v.length() <= 253 && v.matches("[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?");
    }

    private static DkimResult res(String result, String d, String s, String reason) {
        return new DkimResult(result, d, s, reason);
    }
}
