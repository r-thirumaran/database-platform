package org.dbplatform.proxy.identity;

import java.util.regex.Pattern;

/** Case-insensitive glob ({@code *} and {@code ?}) or exact match. */
public final class GlobMatcher {
    private GlobMatcher() {
    }

    public static boolean matches(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        if (pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0) {
            return pattern.equalsIgnoreCase(value);
        }
        return toRegex(pattern).matcher(value).matches();
    }

    static Pattern toRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }
}
