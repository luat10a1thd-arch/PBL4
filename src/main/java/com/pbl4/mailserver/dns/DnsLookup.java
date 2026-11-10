package com.pbl4.mailserver.dns;

import java.net.InetAddress;
import java.util.List;

public interface DnsLookup {

    List<MxRecord> mx(String domain) throws DnsException;

    List<String> txt(String name) throws DnsException;

    List<InetAddress> addresses(String host) throws DnsException;

    String ptr(InetAddress ip) throws DnsException;

    static DnsLookup get() {
        return DnsLookupHolder.get();
    }

    static void set(DnsLookup l) {
        DnsLookupHolder.set(l);
    }
}
