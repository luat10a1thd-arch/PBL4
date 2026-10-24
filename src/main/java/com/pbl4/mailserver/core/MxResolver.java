package com.pbl4.mailserver.core;

import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.List;

/**
 * Tra bản ghi MX của một tên miền bằng DNS (JNDI), sắp xếp theo độ ưu tiên.
 * Ví dụ: gmail.com -> gmail-smtp-in.l.google.com, alt1.gmail-smtp-in.l.google.com, ...
 */
public final class MxResolver {

    public static final class Lookup {
        public final List<String> hosts;
        public final boolean domainMissing; // tên miền không tồn tại hoặc không nhận thư (lỗi vĩnh viễn)
        public final String error;

        Lookup(List<String> hosts, boolean domainMissing, String error) {
            this.hosts = hosts;
            this.domainMissing = domainMissing;
            this.error = error;
        }
    }

    private MxResolver() {}

    public static Lookup lookup(String domain) {
        List<String[]> found = new ArrayList<>();
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "3000");
            env.put("com.sun.jndi.dns.timeout.retries", "2");

            DirContext ctx = new InitialDirContext(env);
            try {
                Attributes attrs = ctx.getAttributes(domain, new String[]{"MX"});
                Attribute attr = attrs.get("MX");
                if (attr != null) {
                    NamingEnumeration<?> en = attr.getAll();
                    while (en.hasMore()) {
                        String[] parts = en.next().toString().trim().split("\\s+");
                        if (parts.length >= 2) {
                            String host = parts[1];
                            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
                            found.add(new String[]{parts[0], host});
                        }
                    }
                }
            } finally {
                ctx.close();
            }
        } catch (NameNotFoundException e) {
            return new Lookup(new ArrayList<>(), true, "Tên miền không tồn tại: " + domain);
        } catch (NamingException e) {
            return new Lookup(new ArrayList<>(), false, "Lỗi tra DNS cho " + domain + ": " + e.getMessage());
        }

        found.sort(Comparator.comparingInt(a -> parsePreference(a[0])));

        List<String> hosts = new ArrayList<>();
        for (String[] entry : found) {
            if (entry[1].isEmpty()) {
                // RFC 7505 "null MX": tên miền khai báo là không nhận thư
                return new Lookup(new ArrayList<>(), true, "Tên miền " + domain + " không nhận thư (null MX)");
            }
            hosts.add(entry[1]);
        }
        // RFC 5321: nếu không có MX thì dùng chính tên miền (implicit MX)
        if (hosts.isEmpty()) {
            hosts.add(domain);
        }
        return new Lookup(hosts, false, "");
    }

    private static int parsePreference(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
