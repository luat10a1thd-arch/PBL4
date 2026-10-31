package com.pbl4.mailserver.auth;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Tên miền gốc (organizational domain) cho DMARC.
 *
 * GIỚI HẠN: không dùng Public Suffix List đầy đủ. Quy tắc: lấy 2 nhãn cuối; nếu 2 nhãn cuối nằm trong
 * danh sách suffix hai nhãn rút gọn bên dưới (co.uk, com.au, com.vn, ...) thì lấy 3 nhãn cuối.
 * Suffix không có trong danh sách (ví dụ *.pvt.k12.ma.us, tên miền nền tảng như github.io) sẽ được coi sai.
 */
public final class DmarcOrgDomain {

    private static final Set<String> TWO_LABEL_SUFFIXES = new HashSet<>(Arrays.asList(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk",
            "com.au", "net.au", "org.au", "edu.au", "gov.au",
            "co.nz", "org.nz", "net.nz", "govt.nz",
            "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp",
            "com.br", "net.br", "org.br", "gov.br",
            "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
            "com.hk", "com.tw", "org.tw", "com.sg", "edu.sg",
            "co.in", "net.in", "org.in", "co.kr", "or.kr", "com.mx", "co.za", "com.tr", "com.ar", "com.ua",
            "com.vn", "net.vn", "org.vn", "edu.vn", "gov.vn", "ac.vn", "info.vn", "biz.vn", "name.vn",
            "com.my", "co.id", "co.th", "com.ph", "com.pk", "com.eg", "com.sa", "co.il", "com.co", "com.pe"));

    private DmarcOrgDomain() {}

    public static String of(String domain) {
        String d = normalize(domain);
        String[] labels = d.split("\\.");
        if (labels.length <= 2) {
            return d;
        }
        String last2 = labels[labels.length - 2] + "." + labels[labels.length - 1];
        int keep = TWO_LABEL_SUFFIXES.contains(last2) ? 3 : 2;
        if (labels.length <= keep) {
            return d;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = labels.length - keep; i < labels.length; i++) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(labels[i]);
        }
        return sb.toString();
    }

    static String normalize(String domain) {
        String d = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
        return d.endsWith(".") ? d.substring(0, d.length() - 1) : d;
    }
}