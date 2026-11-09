package com.pbl4.mailserver.dns;

final class DnsLookupHolder {
    private static volatile DnsLookup instance;

    private DnsLookupHolder() {}

    static DnsLookup get() {
        DnsLookup l = instance;
        if (l == null) {
            synchronized (DnsLookupHolder.class) {
                l = instance;
                if (l == null) {
                    l = new DnsJavaLookup();
                    instance = l;
                }
            }
        }
        return l;
    }

    static void set(DnsLookup l) {
        synchronized (DnsLookupHolder.class) {
            instance = l;
        }
    }
}
