package org.dbplatform.proxy.identity;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Matches an address against {@code a.b.c.d/n}, {@code ::1/128} or a bare IP (IPv4-mapped IPv6 handled). */
public final class CidrMatcher {
    private final byte[] network;
    private final int prefix;

    private CidrMatcher(byte[] network, int prefix) {
        this.network = network;
        this.prefix = prefix;
    }

    public static CidrMatcher parse(String cidr) {
        String s = cidr.strip();
        int slash = s.indexOf('/');
        String ip = slash < 0 ? s : s.substring(0, slash);
        try {
            byte[] addr = InetAddress.getByName(ip).getAddress();
            int prefix = slash < 0 ? addr.length * 8 : Integer.parseInt(s.substring(slash + 1));
            if (prefix < 0 || prefix > addr.length * 8) {
                throw new IllegalArgumentException("Invalid prefix length in CIDR '" + cidr + "'");
            }
            return new CidrMatcher(addr, prefix);
        } catch (UnknownHostException | NumberFormatException e) {
            throw new IllegalArgumentException("Invalid CIDR '" + cidr + "'", e);
        }
    }

    public boolean matches(InetAddress address) {
        if (address == null) {
            return false;
        }
        byte[] a = address.getAddress();
        if (a.length != network.length) {
            a = convert(a, network.length);
            if (a == null) {
                return false;
            }
        }
        int fullBytes = prefix / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (a[i] != network[i]) {
                return false;
            }
        }
        int rem = prefix % 8;
        if (rem == 0) {
            return true;
        }
        int mask = 0xFF << (8 - rem);
        return (a[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    /** IPv4 ↔ IPv4-mapped IPv6 conversion; null when not convertible. */
    private static byte[] convert(byte[] a, int targetLen) {
        if (a.length == 16 && targetLen == 4) {
            for (int i = 0; i < 10; i++) {
                if (a[i] != 0) {
                    return null;
                }
            }
            if ((a[10] & 0xFF) != 0xFF || (a[11] & 0xFF) != 0xFF) {
                return null;
            }
            return new byte[] {a[12], a[13], a[14], a[15]};
        }
        if (a.length == 4 && targetLen == 16) {
            byte[] v6 = new byte[16];
            v6[10] = (byte) 0xFF;
            v6[11] = (byte) 0xFF;
            System.arraycopy(a, 0, v6, 12, 4);
            return v6;
        }
        return null;
    }

    public static boolean anyMatches(Iterable<String> cidrs, InetAddress address) {
        for (String c : cidrs) {
            try {
                if (parse(c).matches(address)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // invalid CIDR in config: skip, logged at config load
            }
        }
        return false;
    }
}
