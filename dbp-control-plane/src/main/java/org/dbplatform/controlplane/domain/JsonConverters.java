package org.dbplatform.controlplane.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JPA converters storing small JSON documents in TEXT columns (portable across H2 and PostgreSQL). */
public final class JsonConverters {
    private JsonConverters() {}

    @Converter
    public static class StringList implements AttributeConverter<List<String>, String> {
        @Override public String convertToDatabaseColumn(List<String> v) { return v == null ? null : Json.write(v); }
        @Override public List<String> convertToEntityAttribute(String s) {
            List<String> l = Json.read(s, new TypeReference<List<String>>() {});
            return l == null ? new ArrayList<>() : l;
        }
    }

    @Converter
    public static class StringMap implements AttributeConverter<Map<String, String>, String> {
        @Override public String convertToDatabaseColumn(Map<String, String> v) { return v == null ? null : Json.write(v); }
        @Override public Map<String, String> convertToEntityAttribute(String s) {
            Map<String, String> m = Json.read(s, new TypeReference<LinkedHashMap<String, String>>() {});
            return m == null ? new LinkedHashMap<>() : m;
        }
    }

    @Converter
    public static class IdentityRulesConv implements AttributeConverter<IdentityRules, String> {
        @Override public String convertToDatabaseColumn(IdentityRules v) { return v == null ? null : Json.write(v); }
        @Override public IdentityRules convertToEntityAttribute(String s) {
            IdentityRules r = Json.read(s, IdentityRules.class);
            return r == null ? new IdentityRules() : r;
        }
    }

    @Converter
    public static class CollectorConfigConv implements AttributeConverter<CollectorConfig, String> {
        @Override public String convertToDatabaseColumn(CollectorConfig v) { return v == null ? null : Json.write(v); }
        @Override public CollectorConfig convertToEntityAttribute(String s) {
            CollectorConfig c = Json.read(s, CollectorConfig.class);
            return c == null ? new CollectorConfig() : c;
        }
    }

    @Converter
    public static class PoolPolicyConv implements AttributeConverter<PoolPolicy, String> {
        @Override public String convertToDatabaseColumn(PoolPolicy v) { return v == null ? null : Json.write(v); }
        @Override public PoolPolicy convertToEntityAttribute(String s) {
            PoolPolicy p = Json.read(s, PoolPolicy.class);
            return p == null ? new PoolPolicy() : p;
        }
    }

    @Converter
    public static class TableMigrationConv implements AttributeConverter<TableMigration, String> {
        @Override public String convertToDatabaseColumn(TableMigration v) { return v == null ? null : Json.write(v); }
        @Override public TableMigration convertToEntityAttribute(String s) {
            TableMigration m = Json.read(s, TableMigration.class);
            return m == null ? new TableMigration() : m;
        }
    }
}
