package org.dbplatform.gateway.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads a {@link StaticConfig} from YAML. {@code ${NAME}} and {@code ${NAME:-default}} placeholders are
 * substituted from the environment (system properties as fallback) before parsing.
 */
public final class StaticConfigLoader {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.]*)(?::-([^}]*))?}");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);

    private StaticConfigLoader() {
    }

    public static StaticConfig load(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read gateway config " + file, e);
        }
    }

    public static StaticConfig parse(String yaml) {
        return parse(yaml, name -> {
            String v = System.getenv(name);
            return v != null ? v : System.getProperty(name);
        });
    }

    public static StaticConfig parse(String yaml, Function<String, String> env) {
        String substituted = substitute(yaml, env);
        try {
            StaticConfig cfg = YAML.readValue(substituted, StaticConfig.class);
            if (cfg == null) {
                throw new IllegalArgumentException("gateway config is empty");
            }
            return cfg;
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid gateway config: " + e.getMessage(), e);
        }
    }

    static String substitute(String text, Function<String, String> env) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = env.apply(m.group(1));
            if (value == null) {
                value = m.group(2);
            }
            if (value == null) {
                throw new IllegalArgumentException("unresolved placeholder ${" + m.group(1) + "} in gateway config");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Resolves the password of a datasource from {@code password}, {@code passwordEnv} or {@code passwordFile}.
     */
    public static String resolvePassword(StaticConfig.DatasourceConfig ds) {
        if (ds.password() != null) {
            return ds.password();
        }
        if (ds.passwordEnv() != null) {
            String v = System.getenv(ds.passwordEnv());
            if (v == null) {
                v = System.getProperty(ds.passwordEnv());
            }
            if (v == null) {
                throw new IllegalStateException("datasource '" + ds.name() + "': environment variable "
                        + ds.passwordEnv() + " is not set");
            }
            return v;
        }
        if (ds.passwordFile() != null) {
            try {
                return Files.readString(Path.of(ds.passwordFile()), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new UncheckedIOException("datasource '" + ds.name() + "': cannot read passwordFile "
                        + ds.passwordFile(), e);
            }
        }
        return "";
    }
}
