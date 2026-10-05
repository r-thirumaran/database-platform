package org.dbplatform.common.util;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses human-friendly durations: a bare number is milliseconds ({@code 2000}); suffixes {@code ms},
 * {@code s}, {@code m}, {@code h}, {@code d} ({@code 500ms}, {@code 2s}, {@code 5m}, {@code 1h}); ISO-8601
 * ({@code PT2S}); and compounds ({@code 1h30m}).
 */
public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ms|millis|milliseconds|s|sec|secs|seconds|m|min|mins|minutes|h|hr|hrs|hours|d|days?)?");

    private Durations() {}

    public static Duration parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("duration is empty");
        }
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("pt") || t.startsWith("p") && t.length() > 1 && Character.isDigit(t.charAt(1))) {
            return Duration.parse(t.toUpperCase(Locale.ROOT));
        }
        if (t.startsWith("-")) {
            return parse(t.substring(1)).negated();
        }
        Matcher m = PART.matcher(t);
        long nanos = 0;
        int pos = 0;
        boolean any = false;
        while (pos < t.length()) {
            if (!m.find(pos) || m.start() != pos) {
                throw new IllegalArgumentException("Invalid duration: '" + text + "'");
            }
            double value = Double.parseDouble(m.group(1));
            String unit = m.group(2);
            nanos += (long) (value * nanosPerUnit(unit));
            pos = m.end();
            any = true;
            while (pos < t.length() && Character.isWhitespace(t.charAt(pos))) {
                pos++;
            }
        }
        if (!any) {
            throw new IllegalArgumentException("Invalid duration: '" + text + "'");
        }
        return Duration.ofNanos(nanos);
    }

    /** Lenient variant returning {@code defaultValue} on invalid input. */
    public static Duration parseOrDefault(String text, Duration defaultValue) {
        try {
            return text == null ? defaultValue : parse(text);
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    /** Compact rendering: {@code 1h30m}, {@code 2s}, {@code 250ms}. */
    public static String format(Duration d) {
        if (d == null) {
            return "null";
        }
        if (d.isNegative()) {
            return "-" + format(d.negated());
        }
        long ms = d.toMillis();
        if (ms == 0) {
            return "0ms";
        }
        StringBuilder sb = new StringBuilder();
        long h = ms / 3_600_000; ms %= 3_600_000;
        long m = ms / 60_000; ms %= 60_000;
        long s = ms / 1_000; ms %= 1_000;
        if (h > 0) sb.append(h).append('h');
        if (m > 0) sb.append(m).append('m');
        if (s > 0) sb.append(s).append('s');
        if (ms > 0) sb.append(ms).append("ms");
        return sb.toString();
    }

    private static double nanosPerUnit(String unit) {
        if (unit == null) {
            return 1_000_000d;
        }
        return switch (unit) {
            case "ms", "millis", "milliseconds" -> 1_000_000d;
            case "s", "sec", "secs", "seconds" -> 1_000_000_000d;
            case "m", "min", "mins", "minutes" -> 60_000_000_000d;
            case "h", "hr", "hrs", "hours" -> 3_600_000_000_000d;
            case "d", "day", "days" -> 86_400_000_000_000d;
            default -> throw new IllegalArgumentException("Unknown duration unit: " + unit);
        };
    }
}
