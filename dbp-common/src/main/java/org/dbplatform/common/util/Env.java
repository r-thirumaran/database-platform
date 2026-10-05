package org.dbplatform.common.util;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reads {@code DBP_*} configuration. Lookup order for a key such as {@code DBP_TELEMETRY_FLUSH_MS}:
 * environment variable {@code DBP_TELEMETRY_FLUSH_MS}, system property {@code DBP_TELEMETRY_FLUSH_MS},
 * system property {@code dbp.telemetry.flush.ms}. Environment wins, as required by CONTRIBUTING.md.
 */
public final class Env {

    private static volatile Function<String, String> envReader = System::getenv;

    private Env() {}

    /** Test hook: replace the environment reader (pass {@code null} to restore {@link System#getenv}). */
    public static void setEnvReader(Function<String, String> reader) {
        envReader = reader == null ? System::getenv : reader;
    }

    /** Raw lookup; empty when not set or blank. */
    public static Optional<String> lookup(String name) {
        String key = normalize(name);
        String v = envReader.apply(key);
        if (isBlank(v)) {
            v = System.getProperty(key);
        }
        if (isBlank(v)) {
            v = System.getProperty(key.toLowerCase(Locale.ROOT).replace('_', '.'));
        }
        return isBlank(v) ? Optional.empty() : Optional.of(v.trim());
    }

    public static String get(String name, String defaultValue) {
        return lookup(name).orElse(defaultValue);
    }

    /** Value of a required setting; throws {@link IllegalStateException} naming the variable when missing. */
    public static String require(String name) {
        return lookup(name).orElseThrow(() ->
                new IllegalStateException("Missing required configuration: " + normalize(name)));
    }

    public static int getInt(String name, int defaultValue) {
        return lookup(name).map(v -> parse(name, v, Integer::parseInt)).orElse(defaultValue);
    }

    public static long getLong(String name, long defaultValue) {
        return lookup(name).map(v -> parse(name, v, Long::parseLong)).orElse(defaultValue);
    }

    public static boolean getBoolean(String name, boolean defaultValue) {
        return lookup(name).map(v -> switch (v.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> throw new IllegalArgumentException("Invalid boolean for " + normalize(name) + ": " + v);
        }).orElse(defaultValue);
    }

    /** Duration setting parsed with {@link Durations#parse(String)} ({@code 2000}, {@code 2s}, {@code PT2S}, ...). */
    public static Duration getDuration(String name, Duration defaultValue) {
        return lookup(name).map(v -> parse(name, v, Durations::parse)).orElse(defaultValue);
    }

    public static <T extends Enum<T>> T getEnum(String name, Class<T> type, T defaultValue) {
        return lookup(name).map(v -> parse(name, v, s -> Enum.valueOf(type, s.trim().toUpperCase(Locale.ROOT))))
                .orElse(defaultValue);
    }

    private static <T> T parse(String name, String value, Function<String, T> parser) {
        try {
            return parser.apply(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid value for " + normalize(name) + ": '" + value + "'", e);
        }
    }

    private static String normalize(String name) {
        String n = name.trim();
        if (n.contains(".")) {
            n = n.replace('.', '_');
        }
        n = n.toUpperCase(Locale.ROOT);
        return n.startsWith("DBP_") ? n : "DBP_" + n;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
