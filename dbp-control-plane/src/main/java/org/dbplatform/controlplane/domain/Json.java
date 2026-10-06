package org.dbplatform.controlplane.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.UncheckedIOException;

/** Shared ObjectMapper for JSON columns (independent of the web mapper on purpose). */
public final class Json {
    public static final ObjectMapper MAPPER = baseBuilder().build();

    /**
     * Mapper of the JSON columns behind the JPA converters: bean properties sorted alphabetically and map entries sorted by key, so the
     * same value always serialises to the same string (independent of declaration order, insertion order or the per-JVM iteration
     * order of {@code Map.of}). Reading is identical to {@link #MAPPER}.
     */
    private static final ObjectMapper CANONICAL = baseBuilder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private static JsonMapper.Builder baseBuilder() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .serializationInclusion(JsonInclude.Include.NON_NULL);
    }

    private Json() {}

    public static String write(Object value) {
        if (value == null) return null;
        try {
            return MAPPER.writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Deterministic serialisation for database columns (see {@link #CANONICAL}). */
    public static String writeCanonical(Object value) {
        if (value == null) return null;
        try {
            return CANONICAL.writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, type);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T read(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, type);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
