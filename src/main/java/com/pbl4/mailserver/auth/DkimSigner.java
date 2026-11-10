package com.pbl4.mailserver.auth;

import com.pbl4.mailserver.auth.DkimCommon.Hdr;
import com.pbl4.mailserver.auth.DkimCommon.Parsed;
import com.pbl4.mailserver.config.ServerConfig;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

public final class DkimSigner {

    private static final Logger LOG = Logger.getLogger(DkimSigner.class.getName());

    private static final String[] SIGNED_HEADERS = {
        "from", "to", "cc", "subject", "date", "message-id", "mime-version", "content-type", "reply-to"
    };

    private DkimSigner() {}

    public static byte[] sign(byte[] raw, String domain) {
        return sign(raw, ServerConfig.MAIL_DOMAIN, ServerConfig.DKIM_SELECTOR, Paths.get(ServerConfig.DKIM_KEY_DIR));
    }

    public static byte[] sign(byte[] raw, String domain, String selector, Path keyDir) {
        if (raw == null) {
            return null;
        }
        try {
            return signCore(raw, domain, selector, keyDir, true, true, System.currentTimeMillis() / 1000);
        } catch (Exception e) {
            LOG.warning("Không ký được DKIM, gửi thư không chữ ký: " + e);
            return raw;
        }
    }

    public static String publicKeyRecord(String domain, String selector, Path keyDir) {
        try {
            RSAPrivateCrtKey key = DkimKeys.loadOrCreate(keyDir, domain, selector);
            return "v=DKIM1; k=rsa; p=" + DkimKeys.publicKeyBase64(key);
        } catch (Exception e) {
            throw new IllegalStateException("Không đọc/tạo được khóa DKIM: " + e, e);
        }
    }

    public static String publicKeyRecord() {
        return publicKeyRecord(ServerConfig.MAIL_DOMAIN, ServerConfig.DKIM_SELECTOR, Paths.get(ServerConfig.DKIM_KEY_DIR));
    }

    static byte[] signCore(byte[] raw, String domain, String selector, Path keyDir,
                           boolean hRelaxed, boolean bRelaxed, long nowSec) throws Exception {
        Parsed p = DkimCommon.parse(raw);

        List<String> hnames = new ArrayList<>();
        for (String nm : SIGNED_HEADERS) {
            int count = DkimCommon.countHeaders(p.headers(), nm);
            for (int i = 0; i < count; i++) {
                hnames.add(nm);
            }
            if (nm.equals("from")) {
                if (count == 0) {
                    return raw;
                }
                hnames.add(nm);
            }
        }

        byte[] body = DkimCommon.canonBody(raw, p.bodyStart(), bRelaxed);
        String bh = Base64.getEncoder().encodeToString(DkimCommon.sha256(body));
        String canon = (hRelaxed ? "relaxed" : "simple") + "/" + (bRelaxed ? "relaxed" : "simple");

        String sigField = "DKIM-Signature: v=1; a=rsa-sha256; c=" + canon + "; d=" + domain + ";\r\n"
                + "\ts=" + selector + "; t=" + nowSec + ";\r\n"
                + "\th=" + String.join(":", hnames) + ";\r\n"
                + "\tbh=" + bh + ";\r\n"
                + "\tb=";

        List<Hdr> selected = DkimCommon.selectHeaders(p.headers(), hnames);
        byte[] headerData = DkimCommon.headerHashInput(selected, hRelaxed);
        String sigCanon = hRelaxed ? DkimCommon.canonHeaderRelaxed(sigField) : sigField;
        byte[] sigBytes = sigCanon.getBytes(DkimCommon.L1);

        byte[] data = new byte[headerData.length + sigBytes.length];
        System.arraycopy(headerData, 0, data, 0, headerData.length);
        System.arraycopy(sigBytes, 0, data, headerData.length, sigBytes.length);

        RSAPrivateCrtKey key = DkimKeys.loadOrCreate(keyDir, domain, selector);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(data);
        String b64 = Base64.getEncoder().encodeToString(sig.sign());

        StringBuilder full = new StringBuilder(sigField.length() + b64.length() + 16);
        full.append(sigField);
        for (int i = 0; i < b64.length(); i += 64) {
            if (i > 0) {
                full.append("\r\n\t");
            }
            full.append(b64, i, Math.min(b64.length(), i + 64));
        }
        full.append("\r\n");

        byte[] head = full.toString().getBytes(DkimCommon.L1);
        byte[] out = new byte[head.length + raw.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(raw, 0, out, head.length, raw.length);
        return out;
    }
}
