package com.pbl4.mailserver.auth;

import com.pbl4.mailserver.dns.DnsException;
import com.pbl4.mailserver.dns.DnsLookup;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;

/** Đánh giá DMARC (RFC 7489): tìm bản ghi, căn chỉnh SPF/DKIM, áp pct. */
public final class DmarcEvaluator {

    private DmarcEvaluator() {}

    public static DmarcResult evaluate(String fromDomain, SpfResult spf, String spfDomain, List<DkimResult> dkim) {
        return evaluate(fromDomain, spf, spfDomain, dkim, DnsLookup.get(),
                () -> ThreadLocalRandom.current().nextInt(100));
    }

    /** roll trả về số 0..99; thư bị áp chính sách khi roll &lt; pct. */
    static DmarcResult evaluate(String fromDomain, SpfResult spf, String spfDomain, List<DkimResult> dkim,
                                DnsLookup dns, IntSupplier roll) {
        String from = DmarcOrgDomain.normalize(fromDomain);
        if (from.isEmpty()) {
            return DmarcResult.of("none", null, "không có domain trong From");
        }
        DmarcRecord rec;
        try {
            rec = find(dns, "_dmarc." + from, from);
            if (rec == null) {
                String org = DmarcOrgDomain.of(from);
                if (!org.equals(from)) {
                    rec = find(dns, "_dmarc." + org, org);
                }
            }
        } catch (DnsException e) {
            return DmarcResult.of("temperror", from, "lỗi DNS tạm khi tra _dmarc: " + e.getMessage());
        }
        if (rec == null) {
            return DmarcResult.of("none", from, "không có bản ghi DMARC");
        }

        String policy = from.equals(rec.domain()) ? rec.p() : rec.sp();

        boolean spfAligned = spf == SpfResult.PASS && spfDomain != null
                && aligned(spfDomain, from, rec.aspfStrict());
        boolean dkimAligned = false;
        boolean maybeTemp = spf == SpfResult.TEMPERROR && spfDomain != null && aligned(spfDomain, from, rec.aspfStrict());
        for (DkimResult r : dkim == null ? new ArrayList<DkimResult>() : dkim) {
            if (r == null || r.domain() == null || !aligned(r.domain(), from, rec.adkimStrict())) {
                continue;
            }
            if ("pass".equals(r.result())) {
                dkimAligned = true;
            } else if ("temperror".equals(r.result())) {
                maybeTemp = true;
            }
        }
        if (spfAligned || dkimAligned) {
            return new DmarcResult("pass", "none", from, rec.domain(), policy, spfAligned, dkimAligned,
                    "căn chỉnh " + (spfAligned ? "SPF" : "DKIM") + " đạt");
        }
        if (maybeTemp) {
            return new DmarcResult("temperror", policy, from, rec.domain(), policy, false, false,
                    "SPF/DKIM lỗi tạm, chưa kết luận được");
        }
        String disp = policy;
        if (!policy.equals("none") && rec.pct() < 100 && roll.getAsInt() >= rec.pct()) {
            disp = policy.equals("reject") ? "quarantine" : "none";   // pct: hạ một bậc
        }
        return new DmarcResult("fail", disp, from, rec.domain(), policy, false, false, "không căn chỉnh được SPF/DKIM");
    }

    static boolean aligned(String a, String b, boolean strict) {
        String x = DmarcOrgDomain.normalize(a);
        String y = DmarcOrgDomain.normalize(b);
        if (x.isEmpty() || y.isEmpty()) {
            return false;
        }
        return strict ? x.equals(y) : DmarcOrgDomain.of(x).equals(DmarcOrgDomain.of(y));
    }

    /** null nếu không có bản ghi hợp lệ hoặc có nhiều hơn một bản ghi DMARC (RFC: bỏ hết). */
    private static DmarcRecord find(DnsLookup dns, String name, String recordDomain) throws DnsException {
        List<String> txts;
        try {
            txts = dns.txt(name);
        } catch (DnsException e) {
            if (e.temporary()) {
                throw e;
            }
            return null;
        }
        DmarcRecord found = null;
        int n = 0;
        for (String t : txts == null ? List.<String>of() : txts) {
            if (DmarcRecord.looksLikeDmarc(t)) {
                n++;
                found = DmarcRecord.parse(t, recordDomain);
            }
        }
        return n == 1 ? found : null;
    }
}