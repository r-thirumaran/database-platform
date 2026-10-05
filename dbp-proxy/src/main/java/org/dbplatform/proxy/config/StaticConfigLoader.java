package org.dbplatform.proxy.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads {@code proxy.yaml} (or JSON — YAML is a superset) into a {@link ProxyConfigDocument}. */
public final class StaticConfigLoader {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private StaticConfigLoader() {
    }

    public static ProxyConfigDocument load(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return load(in);
        }
    }

    public static ProxyConfigDocument load(InputStream in) throws IOException {
        ProxyConfigDocument doc = YAML.readValue(in, ProxyConfigDocument.class);
        return doc == null ? ProxyConfigDocument.EMPTY : doc;
    }

    public static ProxyConfigDocument parse(String yaml) throws IOException {
        ProxyConfigDocument doc = YAML.readValue(yaml, ProxyConfigDocument.class);
        return doc == null ? ProxyConfigDocument.EMPTY : doc;
    }
}
