package org.dbplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.dbplatform.controlplane.domain.CollectorConfig;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.IdentityRules;
import org.dbplatform.controlplane.domain.Json;
import org.dbplatform.controlplane.domain.JsonConverters;
import org.dbplatform.controlplane.domain.PoolPolicy;
import org.dbplatform.controlplane.domain.TableMigration;
import org.dbplatform.controlplane.service.Chunks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Hibernate decides whether a converted attribute is dirty by comparing the snapshot taken at load time with the current value via
 * {@code equals()}. Every value type behind a JPA converter therefore needs value-based equality, and the converters must write the same
 * JSON for equal values. Plain unit test, no Spring context.
 */
class ValueTypesTest {

    @Test
    void everyConverterBackedValueTypeHasValueBasedEquals() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<String> checked = new TreeSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("org.dbplatform.controlplane.domain")) {
            Class<?> entity = Class.forName(bd.getBeanClassName());
            for (Field f : entity.getDeclaredFields()) {
                Convert convert = f.getAnnotation(Convert.class);
                if (convert == null) continue;
                Class<?> domainType = domainTypeOf(convert.converter());
                checked.add(domainType.getSimpleName());
                if (List.class.isAssignableFrom(domainType) || Map.class.isAssignableFrom(domainType)) continue; // JDK collections: value equality
                assertThat(domainType.getMethod("equals", Object.class).getDeclaringClass())
                        .as("%s.%s is converted with %s: %s must override equals()", entity.getSimpleName(), f.getName(), convert.converter().getSimpleName(), domainType.getName())
                        .isEqualTo(domainType);
                assertThat(domainType.getMethod("hashCode").getDeclaringClass())
                        .as("%s must override hashCode()", domainType.getName()).isEqualTo(domainType);
            }
        }
        assertThat(checked).contains("IdentityRules", "CollectorConfig", "PoolPolicy", "TableMigration", "List", "Map");
    }

    private static Class<?> domainTypeOf(Class<? extends AttributeConverter> converter) {
        for (java.lang.reflect.Type t : converter.getGenericInterfaces()) {
            if (t instanceof ParameterizedType p && p.getRawType() == AttributeConverter.class) {
                java.lang.reflect.Type arg = p.getActualTypeArguments()[0];
                return arg instanceof ParameterizedType pt ? (Class<?>) pt.getRawType() : (Class<?>) arg;
            }
        }
        throw new AssertionError("not an AttributeConverter<X, String>: " + converter);
    }

    @Test
    void identityRulesAreEqualByValueAndSurviveTheConverterRoundTrip() {
        IdentityRules a = rules();
        IdentityRules b = rules();
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        b.getCidrs().add("192.168.0.0/16");
        assertThat(a).isNotEqualTo(b);
        JsonConverters.IdentityRulesConv conv = new JsonConverters.IdentityRulesConv();
        assertThat(conv.convertToEntityAttribute(conv.convertToDatabaseColumn(a))).isEqualTo(a).hasSameHashCodeAs(a);
        // a NULL / blank column reads as the default, which is what a new Application holds, so loading it is not a change either
        assertThat(conv.convertToEntityAttribute(null)).isEqualTo(new IdentityRules());
        assertThat(conv.convertToEntityAttribute("{}")).isEqualTo(new IdentityRules());
        assertThat(conv.convertToEntityAttribute("{\"cidrs\":null}")).isEqualTo(new IdentityRules());
    }

    @Test
    void collectorConfigPoolPolicyAndTableMigrationAreEqualByValue() {
        CollectorConfig c = new CollectorConfig();
        c.setEnabled(true); c.setSchemas(new ArrayList<>(List.of("SALES", "HR"))); c.setAuditTrail(true); c.setCredentialId("cred-1");
        c.setRuntimeIntervalSeconds(7); c.setDictionaryIntervalSeconds(99);
        JsonConverters.CollectorConfigConv cc = new JsonConverters.CollectorConfigConv();
        assertThat(cc.convertToEntityAttribute(cc.convertToDatabaseColumn(c))).isEqualTo(c).hasSameHashCodeAs(c);
        CollectorConfig c2 = cc.convertToEntityAttribute(cc.convertToDatabaseColumn(c));
        c2.setRuntimeIntervalSeconds(8);
        assertThat(c2).isNotEqualTo(c);
        assertThat(new CollectorConfig()).isEqualTo(new CollectorConfig());

        PoolPolicy p = new PoolPolicy();
        p.setMode(Enums.PoolMode.SESSION); p.setMaxConnections(33); p.setValidationQuery("select 1"); p.setStatementTimeoutSeconds(5);
        JsonConverters.PoolPolicyConv pc = new JsonConverters.PoolPolicyConv();
        assertThat(pc.convertToEntityAttribute(pc.convertToDatabaseColumn(p))).isEqualTo(p).hasSameHashCodeAs(p);
        PoolPolicy p2 = pc.convertToEntityAttribute(pc.convertToDatabaseColumn(p));
        p2.setIdleTimeoutMs(1);
        assertThat(p2).isNotEqualTo(p);
        assertThat(new PoolPolicy()).isEqualTo(new PoolPolicy());

        TableMigration m = new TableMigration();
        m.setTargetDatabaseId("db-1"); m.setTargetSchema("S"); m.setTargetName("T"); m.setState(Enums.MigrationState.NOT_PLANNED);
        JsonConverters.TableMigrationConv mc = new JsonConverters.TableMigrationConv();
        assertThat(mc.convertToEntityAttribute(mc.convertToDatabaseColumn(m))).isEqualTo(m).hasSameHashCodeAs(m);
        assertThat(new TableMigration()).isEqualTo(new TableMigration());
    }

    @Test
    void convertersWriteTheSameJsonForEqualValues() {
        // bean properties in alphabetical order, whatever the declaration order of the class
        assertThat(new JsonConverters.IdentityRulesConv().convertToDatabaseColumn(rules()))
                .isEqualTo("{\"cidrs\":[\"10.0.0.0/8\"],\"machinePatterns\":[\"rpt-*\"],\"pgApplicationNames\":[\"orders\"],\"programNames\":[\"JDBC Thin Client/orders*\"],\"serviceAliases\":[\"orders\"]}");
        // map entries sorted by key, whatever the insertion order
        Map<String, String> ab = new LinkedHashMap<>(); ab.put("a", "1"); ab.put("b", "2"); ab.put("c", "3");
        Map<String, String> cba = new LinkedHashMap<>(); cba.put("c", "3"); cba.put("b", "2"); cba.put("a", "1");
        JsonConverters.StringMap conv = new JsonConverters.StringMap();
        assertThat(conv.convertToDatabaseColumn(cba)).isEqualTo(conv.convertToDatabaseColumn(ab)).isEqualTo("{\"a\":\"1\",\"b\":\"2\",\"c\":\"3\"}");
        // Map.of has a per-JVM iteration order
        assertThat(Json.writeCanonical(Map.of("tableId", "t1", "access", "READ", "via", "r1"))).isEqualTo("{\"access\":\"READ\",\"tableId\":\"t1\",\"via\":\"r1\"}");
        // reading is unaffected: an old column written in declaration order is read as before
        assertThat(new JsonConverters.StringMap().convertToEntityAttribute("{\"z\":\"1\",\"a\":\"2\"}")).containsEntry("z", "1").containsEntry("a", "2");
    }

    @Test
    void chunksSplitInOrderWithoutLosingElements() {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < 450; i++) all.add(i);
        List<List<Integer>> chunks = Chunks.of(all, 200);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.stream().map(List::size)).containsExactly(200, 200, 50);
        assertThat(chunks.stream().flatMap(List::stream)).containsExactlyElementsOf(all);
        assertThat(Chunks.of(List.of(), 200)).isEmpty();
        assertThat(Chunks.of(null, 200)).isEmpty();
    }

    private static IdentityRules rules() {
        IdentityRules r = new IdentityRules();
        r.setCidrs(new ArrayList<>(List.of("10.0.0.0/8")));
        r.setProgramNames(new ArrayList<>(List.of("JDBC Thin Client/orders*")));
        r.setMachinePatterns(new ArrayList<>(List.of("rpt-*")));
        r.setServiceAliases(new ArrayList<>(List.of("orders")));
        r.setPgApplicationNames(new ArrayList<>(List.of("orders")));
        return r;
    }
}
