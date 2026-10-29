package com.pbl4.mailserver.dns;

import com.pbl4.mailserver.config.ServerConfig;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.ExtendedResolver;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.PTRRecord;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.ReverseMap;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Cài đặt {@link DnsLookup} bằng dnsjava.
 * - DNS_SERVERS (PBL4_DNS_SERVERS) tùy chọn: "ip", "ip:port", "[ipv6]:port", phân tách bằng dấu phẩy; rỗng = dùng cấu hình hệ thống.
 * - Có timeout + thử lại; cache nhỏ (LRU) theo TTL, TTL tối đa 5 phút, cache âm 30 giây, KHÔNG cache lỗi tạm.
 * - NXDOMAIN / NODATA => danh sách rỗng. SERVFAIL, REFUSED, timeout, lỗi mạng => DnsException.temporary() = true.
 *   Tên không hợp lệ => temporary() = false.
 */
public final class DnsJavaLookup implements DnsLookup {

    private static final int TIMEOUT_SECONDS = 3;
    private static final int RETRIES = 2;
    private static final int CACHE_MAX_ENTRIES = 2000;
    private static final long MAX_TTL_SEC = 300;
    private static final long NEGATIVE_TTL_SEC = 30;

    private final Resolver resolver;
    private final LongSupplier nanoClock;
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(256, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > CACHE_MAX_ENTRIES;
        }
    };
    /** Số truy vấn thật sự gửi đi (không tính cache) - dùng cho test. */
    final AtomicInteger networkQueries = new AtomicInteger();

    private record CacheEntry(List<?> value, long expiresAtNanos) {}

    private record Answer(List<Record> records, long ttlSec) {}

    private record Loaded<T>(List<T> value, long ttlSec) {}

    @FunctionalInterface
    private interface Loader<T> {
        Loaded<T> load() throws DnsException;
    }

    public DnsJavaLookup() {
        this(buildResolver(ServerConfig.DNS_SERVERS), System::nanoTime);
    }

    /** Dùng cho test: tiêm resolver (ví dụ trỏ tới DNS server giả trên localhost). */
    public DnsJavaLookup(Resolver resolver) {
        this(resolver, System::nanoTime);
    }

    DnsJavaLookup(Resolver resolver, LongSupplier nanoClock) {
        this.resolver = resolver;
        this.nanoClock = nanoClock;
    }

    // ------------------------------------------------------------------ API

    @Override
    public List<MxRecord> mx(String domain) throws DnsException {
        Name name = toName(domain);
        return cached("MX|" + keyOf(name), () -> {
            Answer a = query(name, Type.MX);
            List<MxRecord> out = new ArrayList<>();
            for (Record r : a.records()) {
                if (r instanceof MXRecord m) {
                    out.add(new MxRecord(m.getPriority(), hostOf(m.getTarget())));
                }
            }
            out.sort(Comparator.comparingInt(MxRecord::preference));
            if (out.size() == 1 && ".".equals(out.get(0).host())) {
                return new Loaded<>(List.of(new MxRecord(0, ".")), a.ttlSec());
            }
            return new Loaded<>(List.copyOf(out), a.ttlSec());
        });
    }

    @Override
    public List<String> txt(String nameStr) throws DnsException {
        Name name = toName(nameStr);
        return cached("TXT|" + keyOf(name), () -> {
            Answer a = query(name, Type.TXT);
            List<String> out = new ArrayList<>();
            for (Record r : a.records()) {
                if (r instanceof TXTRecord t) {
                    StringBuilder sb = new StringBuilder();
                    for (byte[] part : t.getStringsAsByteArrays()) {
                        sb.append(new String(part, StandardCharsets.UTF_8));
                    }
                    out.add(sb.toString());
                }
            }
            return new Loaded<>(List.copyOf(out), a.ttlSec());
        });
    }

    @Override
    public List<InetAddress> addresses(String host) throws DnsException {
        String h = host == null ? "" : host.trim();
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
            h = h.substring(1, h.length() - 1);
        }
        if (isIpLiteral(h)) {
            try {
                return List.of(InetAddress.getByName(h));
            } catch (UnknownHostException e) {
                throw new DnsException("Địa chỉ IP không hợp lệ: " + h, false, e);
            }
        }
        Name name = toName(h);
        return cached("ADDR|" + keyOf(name), () -> {
            List<InetAddress> out = new ArrayList<>();
            DnsException firstError = null;
            long positiveTtl = MAX_TTL_SEC;
            for (int type : new int[] {Type.A, Type.AAAA}) {
                try {
                    Answer a = query(name, type);
                    if (!a.records().isEmpty()) {
                        positiveTtl = Math.min(positiveTtl, a.ttlSec());
                    }
                    for (Record r : a.records()) {
                        if (r instanceof ARecord ar) {
                            out.add(ar.getAddress());
                        } else if (r instanceof AAAARecord aaaa) {
                            out.add(aaaa.getAddress());
                        }
                    }
                } catch (DnsException e) {
                    if (firstError == null) {
                        firstError = e;
                    }
                }
            }
            if (out.isEmpty() && firstError != null) {
                throw firstError;
            }
            // Một trong hai loại bị lỗi tạm: vẫn trả kết quả có được nhưng không cache (TTL 0)
            long ttl = firstError != null ? 0 : (out.isEmpty() ? NEGATIVE_TTL_SEC : positiveTtl);
            return new Loaded<>(List.copyOf(out), ttl);
        });
    }

    @Override
    public String ptr(InetAddress ip) throws DnsException {
        if (ip == null) {
            throw new DnsException("Thiếu địa chỉ IP để tra PTR", false);
        }
        Name name = ReverseMap.fromAddress(ip);
        List<String> r = cached("PTR|" + keyOf(name), () -> {
            Answer a = query(name, Type.PTR);
            List<String> out = new ArrayList<>();
            for (Record rec : a.records()) {
                if (rec instanceof PTRRecord p) {
                    out.add(hostOf(p.getTarget()));
                }
            }
            return new Loaded<>(List.copyOf(out), a.ttlSec());
        });
        return r.isEmpty() ? null : r.get(0);
    }

    /** Xóa cache (dùng cho test / quản trị). */
    public void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    // ------------------------------------------------------------ nội bộ

    private Answer query(Name name, int type) throws DnsException {
        Message q = Message.newQuery(Record.newRecord(name, type, DClass.IN));
        Message resp;
        networkQueries.incrementAndGet();
        try {
            resp = resolver.send(q);
        } catch (IOException | RuntimeException e) {
            throw new DnsException("Truy vấn DNS " + Type.string(type) + " " + name + " thất bại: " + e, true, e);
        }
        int rcode = resp.getRcode();
        if (rcode == Rcode.NXDOMAIN) {
            return new Answer(List.of(), NEGATIVE_TTL_SEC);
        }
        if (rcode != Rcode.NOERROR) {
            throw new DnsException("DNS trả " + Rcode.string(rcode) + " cho " + Type.string(type) + " " + name, true);
        }
        List<Record> matched = new ArrayList<>();
        long ttl = MAX_TTL_SEC;
        for (Record r : resp.getSection(Section.ANSWER)) {
            if (r.getType() == type) {
                matched.add(r);
                ttl = Math.min(ttl, r.getTTL());
            }
        }
        if (matched.isEmpty()) {
            ttl = NEGATIVE_TTL_SEC;
        }
        return new Answer(matched, ttl);
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> cached(String key, Loader<T> loader) throws DnsException {
        long now = nanoClock.getAsLong();
        synchronized (cache) {
            CacheEntry e = cache.get(key);
            if (e != null) {
                if (e.expiresAtNanos() - now > 0) {
                    return (List<T>) e.value();
                }
                cache.remove(key);
            }
        }
        Loaded<T> loaded = loader.load();
        long ttl = Math.min(loaded.ttlSec(), MAX_TTL_SEC);
        if (ttl > 0) {
            long expires = nanoClock.getAsLong() + ttl * 1_000_000_000L;
            synchronized (cache) {
                cache.put(key, new CacheEntry(loaded.value(), expires));
            }
        }
        return loaded.value();
    }

    private static String keyOf(Name n) {
        return n.toString().toLowerCase(Locale.ROOT);
    }

    private static String hostOf(Name n) {
        if (n.equals(Name.root)) {
            return ".";
        }
        return n.toString(true).toLowerCase(Locale.ROOT);
    }

    private static Name toName(String s) throws DnsException {
        String n = s == null ? "" : s.trim();
        if (n.endsWith(".")) {
            n = n.substring(0, n.length() - 1);
        }
        if (n.isEmpty()) {
            throw new DnsException("Tên DNS rỗng", false);
        }
        try {
            n = IDN.toASCII(n, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            throw new DnsException("Tên DNS không hợp lệ: " + s, false, e);
        }
        try {
            return Name.fromString(n, Name.root);
        } catch (TextParseException e) {
            throw new DnsException("Tên DNS không hợp lệ: " + s, false, e);
        }
    }

    private static boolean isIpLiteral(String s) {
        if (s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return true;
        }
        return s.indexOf(':') >= 0 && s.matches("[0-9A-Fa-f:.]+");
    }

    // ----------------------------------------------------- dựng resolver

    private static Resolver buildResolver(String serversCsv) {
        try {
            List<InetSocketAddress> servers = parseServers(serversCsv);
            Resolver r;
            if (servers.isEmpty()) {
                r = new ExtendedResolver();
            } else {
                List<Resolver> list = new ArrayList<>();
                for (InetSocketAddress a : servers) {
                    list.add(new SimpleResolver(a));
                }
                r = new ExtendedResolver(list);
            }
            r.setTimeout(Duration.ofSeconds(TIMEOUT_SECONDS));
            if (r instanceof ExtendedResolver er) {
                er.setRetries(RETRIES);
            }
            return r;
        } catch (Exception e) {
            throw new IllegalStateException("Không khởi tạo được DNS resolver: " + e, e);
        }
    }

    /** Phân tích DNS_SERVERS. Mục không hợp lệ bị bỏ qua (có cảnh báo). */
    static List<InetSocketAddress> parseServers(String csv) {
        List<InetSocketAddress> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        for (String part : csv.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            String host = p;
            int port = 53;
            if (p.startsWith("[")) {
                int close = p.indexOf(']');
                if (close < 0) {
                    warn(p);
                    continue;
                }
                host = p.substring(1, close);
                String rest = p.substring(close + 1);
                if (rest.startsWith(":")) {
                    port = parsePort(rest.substring(1));
                } else if (!rest.isEmpty()) {
                    port = -1;
                }
            } else if (p.indexOf(':') >= 0 && p.indexOf(':') == p.lastIndexOf(':')) {
                int c = p.indexOf(':');
                host = p.substring(0, c);
                port = parsePort(p.substring(c + 1));
            }
            if (port <= 0 || host.isEmpty()) {
                warn(p);
                continue;
            }
            try {
                out.add(new InetSocketAddress(InetAddress.getByName(host), port));
            } catch (UnknownHostException e) {
                warn(p);
            }
        }
        return out;
    }

    private static int parsePort(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            return (v >= 1 && v <= 65535) ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void warn(String entry) {
        System.err.println("[WARNING] PBL4_DNS_SERVERS: bỏ qua mục không hợp lệ \"" + entry + "\"");
    }
}