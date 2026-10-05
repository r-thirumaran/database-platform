package org.dbplatform.controlplane.collector;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

/** Case-insensitive glob ({@code *}, {@code ?}) and CIDR matching used by identity rules. */
public final class Globs {
    private Globs() {}

    public static boolean matchesAny(List<String> patterns, String... values) {
        if (patterns == null || patterns.isEmpty()) return false;
        for (String p : patterns) {
            Pattern re = toRegex(p);
            for (String v : values) if (v != null && re.matcher(v).matches()) return true;
        }
        return false;
    }

    public static Pattern toRegex(String glob) {
        StringBuilder sb = new StringBuilder("(?i)^");
        for (char ch : glob.toCharArray()) {
            switch (ch) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> sb.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return Pattern.compile(sb.append('$').toString());
    }

    public static boolean inAnyCidr(List<String> cidrs, String addr) {
        if (cidrs == null || cidrs.isEmpty() || addr == null) return false;
        String ip = addr.contains("/") ? addr.substring(0, addr.indexOf('/')) : addr;
        for (String c : cidrs) if (inCidr(c, ip)) return true;
        return false;
    }

    public static boolean inCidr(String cidr, String ip) {
        try {
            String[] parts = cidr.split("/");
            byte[] net = InetAddress.getByName(parts[0]).getAddress();
            byte[] a = InetAddress.getByName(ip).getAddress();
            if (net.length != a.length) return false;
            int bits = parts.length > 1 ? Integer.parseInt(parts[1]) : net.length * 8;
            for (int i = 0; i < net.length; i++) {
                int remaining = bits - i * 8;
                if (remaining <= 0) return true;
                int mask = remaining >= 8 ? 0xFF : (0xFF << (8 - remaining)) & 0xFF;
                if ((net[i] & mask) != (a[i] & mask)) return false;
            }
            return true;
        } catch (UnknownHostException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return false;
        }
    }
}
