package com.pbl4.mailserver.dns;

import java.net.InetAddress;
import java.util.List;

/**
 * Giao diện tra cứu DNS dùng chung cho SPF/DKIM/DMARC và gửi thư trực tiếp.
 * Mặc định là {@link DnsJavaLookup}; test thay bằng FakeDnsLookup qua {@link #set(DnsLookup)}.
 */
public interface DnsLookup {

    /** MX của domain, sắp theo preference tăng dần; [] nếu không có; null-MX = List.of(new MxRecord(0, ".")). */
    List<MxRecord> mx(String domain) throws DnsException;

    /** TXT của tên; các chuỗi con của mỗi bản ghi được nối lại thành một chuỗi. [] nếu không có. */
    List<String> txt(String name) throws DnsException;

    /** A + AAAA của host (hoặc chính địa chỉ nếu host là IP literal). [] nếu không có. */
    List<InetAddress> addresses(String host) throws DnsException;

    /** PTR đầu tiên của IP (chữ thường, không có dấu chấm cuối); null nếu không có. */
    String ptr(InetAddress ip) throws DnsException;

    static DnsLookup get() {
        return DnsLookupHolder.get();
    }

    /** Dành cho test. set(null) trả về cài đặt mặc định (dnsjava). */
    static void set(DnsLookup l) {
        DnsLookupHolder.set(l);
    }
}
