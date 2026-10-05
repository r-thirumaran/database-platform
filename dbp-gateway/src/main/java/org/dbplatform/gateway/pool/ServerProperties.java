package org.dbplatform.gateway.pool;

import org.dbplatform.common.telemetry.Engine;
import org.dbplatform.protocol.messages.HelloOk;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the {@code serverProperties} map of HELLO_OK (wire protocol section 4.5) from a physical
 * {@link DatabaseMetaData}. Every attribute is read defensively: a driver that throws for one attribute does not
 * prevent the others from being delivered.
 */
public final class ServerProperties {

    private ServerProperties() {
    }

    /** Computes every key of the specification except the per-session {@code url} and {@code poolMode}. */
    public static Map<String, String> compute(DatabaseMetaData md) {
        Map<String, String> p = new LinkedHashMap<>();
        put(p, "databaseProductName", md::getDatabaseProductName);
        put(p, "databaseProductVersion", md::getDatabaseProductVersion);
        put(p, "databaseMajorVersion", md::getDatabaseMajorVersion);
        put(p, "databaseMinorVersion", md::getDatabaseMinorVersion);
        put(p, "driverName", md::getDriverName);
        put(p, "driverVersion", md::getDriverVersion);
        put(p, "identifierQuoteString", md::getIdentifierQuoteString);
        put(p, "catalogSeparator", md::getCatalogSeparator);
        put(p, "catalogTerm", md::getCatalogTerm);
        put(p, "schemaTerm", md::getSchemaTerm);
        put(p, "procedureTerm", md::getProcedureTerm);
        put(p, "searchStringEscape", md::getSearchStringEscape);
        put(p, "sqlKeywords", md::getSQLKeywords);
        put(p, "extraNameCharacters", md::getExtraNameCharacters);
        put(p, "storesUpperCaseIdentifiers", md::storesUpperCaseIdentifiers);
        put(p, "storesLowerCaseIdentifiers", md::storesLowerCaseIdentifiers);
        put(p, "storesMixedCaseIdentifiers", md::storesMixedCaseIdentifiers);
        put(p, "supportsMixedCaseIdentifiers", md::supportsMixedCaseIdentifiers);
        put(p, "supportsSchemasInTableDefinitions", md::supportsSchemasInTableDefinitions);
        put(p, "supportsSchemasInDataManipulation", md::supportsSchemasInDataManipulation);
        put(p, "supportsCatalogsInTableDefinitions", md::supportsCatalogsInTableDefinitions);
        put(p, "supportsCatalogsInDataManipulation", md::supportsCatalogsInDataManipulation);
        put(p, "supportsTransactions", md::supportsTransactions);
        put(p, "supportsSavepoints", md::supportsSavepoints);
        put(p, "supportsBatchUpdates", md::supportsBatchUpdates);
        put(p, "supportsGetGeneratedKeys", md::supportsGetGeneratedKeys);
        put(p, "supportsStoredProcedures", md::supportsStoredProcedures);
        put(p, "supportsNamedParameters", md::supportsNamedParameters);
        put(p, "supportsMultipleResultSets", md::supportsMultipleResultSets);
        put(p, "supportsOuterJoins", md::supportsOuterJoins);
        put(p, "supportsUnion", md::supportsUnion);
        put(p, "supportsUnionAll", md::supportsUnionAll);
        put(p, "defaultTransactionIsolation", md::getDefaultTransactionIsolation);
        put(p, "maxStatementLength", md::getMaxStatementLength);
        put(p, "maxConnections", md::getMaxConnections);
        put(p, "nullsAreSortedHigh", md::nullsAreSortedHigh);
        put(p, "nullsAreSortedLow", md::nullsAreSortedLow);
        put(p, "nullPlusNonNullIsNull", md::nullPlusNonNullIsNull);
        put(p, "isReadOnly", md::isReadOnly);
        put(p, "userName", md::getUserName);
        return p;
    }

    /** Maps a product name to the HELLO_OK {@code engine} string. */
    public static String engineName(String productName, Engine hint) {
        Engine e = detectEngine(productName, hint);
        return switch (e) {
            case ORACLE -> HelloOk.ENGINE_ORACLE;
            case POSTGRES -> HelloOk.ENGINE_POSTGRES;
            case MSSQL -> HelloOk.ENGINE_MSSQL;
            case H2 -> HelloOk.ENGINE_H2;
            default -> HelloOk.ENGINE_OTHER;
        };
    }

    /** Detects the engine from the product name, falling back to the configured hint. */
    public static Engine detectEngine(String productName, Engine hint) {
        if (productName != null) {
            String n = productName.toLowerCase(Locale.ROOT);
            if (n.contains("oracle")) {
                return Engine.ORACLE;
            }
            if (n.contains("postgres")) {
                return Engine.POSTGRES;
            }
            if (n.contains("sql server")) {
                return Engine.MSSQL;
            }
            if (n.equals("h2") || n.startsWith("h2 ")) {
                return Engine.H2;
            }
        }
        return hint == null || hint == Engine.TCP ? Engine.OTHER : hint;
    }

    @FunctionalInterface
    private interface MetaSupplier {
        Object get() throws SQLException;
    }

    private static void put(Map<String, String> p, String key, MetaSupplier s) {
        try {
            Object v = s.get();
            if (v != null) {
                p.put(key, String.valueOf(v));
            }
        } catch (SQLException | RuntimeException | AbstractMethodError e) {
            // unknown keys fall back to driver defaults on the client side
        }
    }
}
