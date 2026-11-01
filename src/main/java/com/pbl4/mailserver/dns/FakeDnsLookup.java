package com.pbl4.mailserver.dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DNS giả cho test của MỌI bot (SPF, DMARC, MxDeliverer, DKIM...). Dán dùng lại:
 *
 *   new FakeDnsLookup()
 *       .addMx("example.com", 10, "mx1.example.com")
 *       .addAddress("mx1.example.com", "192.0.2.10")
 *       .addTxt("example.com", "v=spf1 ip4:192.0.2.0/24 -all")
 *       .setPtr("192.0.2.10", "mx1.example.com")
 *       .install();                    // = DnsLookup.set(this)
 *   ... chạy test ...
 *   FakeDnsLookup.uninstall();         // trong @AfterEach
 *
 * Tên không phân biệt hoa thường, bỏ dấu chấm cuối. Tên chưa khai báo => trả danh sách rỗng (như NXDOMAIN).
 * failTemporary(name)/failPermanent(name) làm MỌI loại truy vấn tới tên đó ném DnsException.
 */
public class FakeDnsLookup implements DnsLookup {

    private final Map<String, List<MxRecord>> mx = new ConcurrentHashMap<>();
    private final Map<String, List<String>> txt = new ConcurrentHashMap<>();
    private final Map<String, List<InetAddress>> addr = new ConcurrentHashMap<>();
    private final Map<String, String> ptr = new ConcurrentHashMap<>();
    private final Map<String, DnsException> errors = new ConcurrentHashMap<>();
    private final AtomicInteger queries = new AtomicInteger();

    // ---------------------------------------------------------- khai báo

    public FakeDnsLookup addMx(String domain, int preference, String host) {
        mx.computeIfAbsent(norm(domain), k -> new ArrayList<>()).add(new MxRecord(preference, norm(host)));
        return this;
    }

    /** null-MX (RFC 7505): domain không nhận thư. */
    public FakeDnsLookup setNullMx(String domain) {
        mx.put(norm(domain), new ArrayList<>(List.of(new MxRecord(0, "."))));
        return this;
    }

    /** value là chuỗi đã nối (không cần chia 255 byte). */
    public FakeDnsLookup addTxt(String name, String value) {
        txt.computeIfAbsent(norm(name), k -> new ArrayList<>()).add(value);
        return this;
    }

    /** ip phải là IP literal (v4 hoặc v6). */
    public FakeDnsLookup addAddress(String host, String ip) {
        addr.computeIfAbsent(norm(host), k -> new ArrayList<>()).add(literal(ip));
        return this;
    }

    public FakeDnsLookup setPtr(String ip, String host) {
        ptr.put(literal(ip).getHostAddress(), norm(host));
        return this;
    }

    public FakeDnsLookup failTemporary(String name) {
        errors.put(norm(name), new DnsException("SERVFAIL giả lập cho " + name, true));
        return this;
    }

    public FakeDnsLookup failPermanent(String name) {
        errors.put(norm(name), new DnsException("Lỗi vĩnh viễn giả lập cho " + name, false));
        return this;
    }

    public FakeDnsLookup install() {
        DnsLookup.set(this);
        return this;
    }

    public static void uninstall() {
        DnsLookup.set(null);
    }

    /** Tổng số truy vấn đã nhận (để test kiểm tra có tra DNS hay không). */
    public int queryCount() {
        return queries.get();
    }

    // ---------------------------------------------------------- DnsLookup

    @Override
    public List<MxRecord> mx(String domain) throws DnsException {
        String k = enter(domain);
        List<MxRecord> l = mx.get(k);
        if (l == null) {
            return List.of();
        }
        List<MxRecord> copy = new ArrayList<>(l);
        copy.sort(Comparator.comparingInt(MxRecord::preference));
        return copy;
    }

    @Override
    public List<String> txt(String name) throws DnsException {
        List<String> l = txt.get(enter(name));
        return l == null ? List.of() : List.copyOf(l);
    }

    @Override
    public List<InetAddress> addresses(String host) throws DnsException {
        String h = host == null ? "" : host.trim();
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.matches("\\d{1,3}(\\.\\d{1,3}){3}") || (h.indexOf(':') >= 0 && h.matches("[0-9A-Fa-f:.]+"))) {
            queries.incrementAndGet();
            return List.of(literal(h));
        }
        List<InetAddress> l = addr.get(enter(h));
        return l == null ? List.of() : List.copyOf(l);
    }

    @Override
    public String ptr(InetAddress ip) throws DnsException {
        queries.incrementAndGet();
        String key = ip.getHostAddress();
        DnsException e = errors.get(key);
        if (e != null) {
            throw e;
        }
        return ptr.get(key);
    }

    // ------------------------------------------------------------ nội bộ

    private String enter(String name) throws DnsException {
        queries.incrementAndGet();
        String k = norm(name);
        DnsException e = errors.get(k);
        if (e != null) {
            throw e;
        }
        return k;
    }

    private static String norm(String s) {
        String n = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        return n.endsWith(".") && n.length() > 1 ? n.substring(0, n.length() - 1) : n;
    }

    private static InetAddress literal(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Không phải IP literal: " + ip, e);
        }
    }
}