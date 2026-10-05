package org.dbplatform.jdbc;

import org.dbplatform.protocol.messages.Metadata;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseMetaDataTest extends GatewayTest {

    @Test
    void scalarsComeFromServerProperties() throws Exception {
        try (Connection c = connect()) {
            DatabaseMetaData md = c.getMetaData();
            assertThat(md.getDatabaseProductName()).isEqualTo("H2");
            assertThat(md.getDatabaseProductVersion()).isEqualTo("2.3.232 (2024-08-11)");
            assertThat(md.getDatabaseMajorVersion()).isEqualTo(2);
            assertThat(md.getDatabaseMinorVersion()).isEqualTo(3);
            assertThat(md.getIdentifierQuoteString()).isEqualTo("\"");
            assertThat(md.storesUpperCaseIdentifiers()).isTrue();
            assertThat(md.storesLowerCaseIdentifiers()).isFalse();
            assertThat(md.supportsMixedCaseIdentifiers()).isFalse();
            assertThat(md.supportsSavepoints()).isTrue();
            assertThat(md.supportsBatchUpdates()).isTrue();
            assertThat(md.supportsGetGeneratedKeys()).isTrue();
            assertThat(md.supportsStoredProcedures()).isTrue();
            assertThat(md.supportsNamedParameters()).isFalse();
            assertThat(md.supportsMultipleResultSets()).isFalse();
            assertThat(md.getSQLKeywords()).contains("LIMIT");
            assertThat(md.getDefaultTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            assertThat(md.supportsTransactionIsolationLevel(Connection.TRANSACTION_SERIALIZABLE)).isTrue();
            assertThat(md.supportsTransactionIsolationLevel(Connection.TRANSACTION_NONE)).isFalse();
            assertThat(md.getURL()).isEqualTo("jdbc:dbp://gateway.example:7420/sales");
            assertThat(md.getUserName()).isEqualTo("SA");
            assertThat(md.getDriverName()).isEqualTo("DBP JDBC Driver");
            assertThat(md.getDriverVersion()).isEqualTo("0.1.0-SNAPSHOT");
            assertThat(md.getDriverMajorVersion()).isZero();
            assertThat(md.getDriverMinorVersion()).isEqualTo(1);
            assertThat(md.getJDBCMajorVersion()).isEqualTo(4);
            assertThat(md.getJDBCMinorVersion()).isEqualTo(3);
            assertThat(md.getMaxConnections()).isZero();
            assertThat(md.getMaxStatementLength()).isZero();
            assertThat(md.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY)).isTrue();
            assertThat(md.supportsResultSetType(ResultSet.TYPE_SCROLL_INSENSITIVE)).isFalse();
            assertThat(md.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)).isFalse();
            assertThat(md.supportsResultSetHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT)).isTrue();
            assertThat(md.getResultSetHoldability()).isEqualTo(ResultSet.CLOSE_CURSORS_AT_COMMIT);
            assertThat(md.getSearchStringEscape()).isEqualTo("\\");
            assertThat(md.getCatalogSeparator()).isEqualTo(".");
            assertThat(md.getCatalogTerm()).isEqualTo("catalog");
            assertThat(md.getSchemaTerm()).isEqualTo("schema");
            assertThat(md.getProcedureTerm()).isEqualTo("procedure");
            assertThat(md.getExtraNameCharacters()).isEmpty();
            assertThat(md.nullsAreSortedLow()).isTrue();
            assertThat(md.nullsAreSortedHigh()).isFalse();
            assertThat(md.isReadOnly()).isFalse();
            assertThat(md.supportsSchemasInTableDefinitions()).isTrue();
            assertThat(md.supportsCatalogsInDataManipulation()).isTrue();
            assertThat(md.supportsUnion()).isTrue();
            assertThat(md.supportsUnionAll()).isTrue();
            assertThat(md.supportsOuterJoins()).isTrue();
            assertThat(md.getConnection()).isSameAs(c);
            assertThat(md.getRowIdLifetime()).isEqualTo(RowIdLifetime.ROWID_UNSUPPORTED);
            assertThat(md.generatedKeyAlwaysReturned()).isFalse();
            assertThat(md.supportsRefCursors()).isTrue();
            assertThat(md.getSQLStateType()).isEqualTo(DatabaseMetaData.sqlStateSQL);
            assertThat(md.getMaxTableNameLength()).isZero();
            assertThat(md.allProceduresAreCallable()).isFalse();
            assertThat(md.isWrapperFor(DatabaseMetaData.class)).isTrue();
            assertThat(md.toString()).contains("H2");
        }
    }

    @Test
    void versionFallbackParsesTheProductVersion() throws Exception {
        Map<String, String> props = new java.util.LinkedHashMap<>(FakeGateway.EchoHandler.defaultServerProperties());
        props.remove("databaseMajorVersion");
        props.remove("databaseMinorVersion");
        props.remove("url");
        props.remove("userName");
        props.remove("identifierQuoteString");
        props.put("databaseProductVersion", "PostgreSQL 15.4 on x86_64");
        gateway.echo().serverProperties = props;
        gateway.echo().engine = "POSTGRES";
        try (Connection c = connect("apiKey=k&user=bob")) {
            DatabaseMetaData md = c.getMetaData();
            assertThat(md.getDatabaseMajorVersion()).isEqualTo(15);
            assertThat(md.getDatabaseMinorVersion()).isEqualTo(4);
            assertThat(md.getURL()).startsWith("jdbc:dbp://").endsWith("/sales?apiKey=k&user=bob");
            assertThat(md.getUserName()).isEqualTo("bob");
            assertThat(md.getIdentifierQuoteString()).isEqualTo("\"");
        }
        gateway.echo().serverProperties = Map.of();
        gateway.echo().engine = "ORACLE";
        try (Connection c = connect()) {
            DatabaseMetaData md = c.getMetaData();
            assertThat(md.getDatabaseProductName()).isEqualTo("ORACLE");
            assertThat(md.getDatabaseMajorVersion()).isZero();
            assertThat(md.getDefaultTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            assertThat(md.supportsSavepoints()).isTrue();
        }
    }

    @Test
    void getTablesEncodesTheTypeArray() throws Exception {
        try (Connection c = connect()) {
            ResultSet rs = c.getMetaData().getTables(null, "PUBLIC", "%", new String[] {"TABLE", "VIEW"});
            Metadata m = gateway.last(Metadata.class);
            assertThat(m.operation()).isEqualTo("getTables");
            assertThat(m.args()).containsExactly(null, "PUBLIC", "%", "TABLE\u0000VIEW");
            assertThat(rs.getStatement()).isNull();
            assertThat(rs.getMetaData().getColumnLabel(3)).isEqualTo("TABLE_NAME");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("TABLE_NAME")).isEqualTo("CUSTOMERS");
            assertThat(rs.getString("REMARKS")).isNull();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(3)).isEqualTo("ORDERS");
            assertThat(rs.next()).isFalse();
            rs.close();

            c.getMetaData().getTables("CAT", null, "T", null);
            assertThat(gateway.last(Metadata.class).args()).containsExactly("CAT", null, "T", null);
            c.getMetaData().getTables(null, null, null, new String[0]);
            assertThat(gateway.last(Metadata.class).args()).containsExactly(null, null, null, "");
        }
    }

    @Test
    void everyResultSetMethodIsForwardedWithItsArguments() throws Exception {
        try (Connection c = connect()) {
            DatabaseMetaData md = c.getMetaData();
            md.getSchemas();
            assertThat(gateway.last(Metadata.class).args()).isEmpty();
            md.getSchemas("c", "s%");
            assertThat(gateway.last(Metadata.class).args()).containsExactly("c", "s%");
            md.getCatalogs();
            assertThat(gateway.last(Metadata.class).operation()).isEqualTo("getCatalogs");
            md.getTableTypes();
            assertThat(gateway.last(Metadata.class).operation()).isEqualTo("getTableTypes");
            md.getColumns("c", "s", "t", "col%");
            assertThat(gateway.last(Metadata.class).args()).containsExactly("c", "s", "t", "col%");
            md.getPrimaryKeys(null, "s", "t");
            assertThat(gateway.last(Metadata.class).args()).containsExactly(null, "s", "t");
            md.getImportedKeys(null, "s", "t");
            assertThat(gateway.last(Metadata.class).operation()).isEqualTo("getImportedKeys");
            md.getExportedKeys(null, "s", "t");
            assertThat(gateway.last(Metadata.class).operation()).isEqualTo("getExportedKeys");
            md.getCrossReference("pc", "ps", "pt", "fc", "fs", "ft");
            assertThat(gateway.last(Metadata.class).args()).containsExactly("pc", "ps", "pt", "fc", "fs", "ft");
            md.getIndexInfo(null, "s", "t", true, false);
            assertThat(gateway.last(Metadata.class).args()).containsExactly(null, "s", "t", Boolean.TRUE, Boolean.FALSE);
            md.getTypeInfo();
            assertThat(gateway.last(Metadata.class).operation()).isEqualTo("getTypeInfo");
            md.getProcedures(null, "s", "p%");
            assertThat(gateway.last(Metadata.class).operation()).isEqualTo("getProcedures");
            md.getProcedureColumns(null, "s", "p", "%");
            assertThat(gateway.last(Metadata.class).args()).hasSize(4);
            md.getFunctions(null, "s", "f%");
            assertThat(gateway.last(Metadata.class).operation()).isEqualTo("getFunctions");
            md.getFunctionColumns(null, "s", "f", "%");
            assertThat(gateway.last(Metadata.class).operation()).isEqualTo("getFunctionColumns");
            md.getBestRowIdentifier(null, "s", "t", DatabaseMetaData.bestRowSession, true);
            assertThat(gateway.last(Metadata.class).args()).containsExactly(null, "s", "t", DatabaseMetaData.bestRowSession, Boolean.TRUE);
            md.getVersionColumns(null, "s", "t");
            assertThat(gateway.last(Metadata.class).operation()).isEqualTo("getVersionColumns");
            md.getTablePrivileges(null, "s", "t");
            assertThat(gateway.last(Metadata.class).operation()).isEqualTo("getTablePrivileges");
            md.getColumnPrivileges(null, "s", "t", "%");
            assertThat(gateway.last(Metadata.class).operation()).isEqualTo("getColumnPrivileges");
            md.getUDTs(null, "s", "%", new int[] {2000, 2002});
            assertThat(gateway.last(Metadata.class).args()).containsExactly(null, "s", "%", "2000\u00002002");
            md.getUDTs(null, "s", "%", null);
            assertThat(gateway.last(Metadata.class).args()).containsExactly(null, "s", "%", null);
            md.getSuperTables(null, "s", "t");
            assertThat(gateway.last(Metadata.class).operation()).isEqualTo("getSuperTables");
            md.getSuperTypes(null, "s", "t");
            assertThat(gateway.last(Metadata.class).operation()).isEqualTo("getSuperTypes");
            md.getAttributes(null, "s", "t", "%");
            assertThat(gateway.last(Metadata.class).operation()).isEqualTo("getAttributes");
            md.getPseudoColumns(null, "s", "t", "%");
            assertThat(gateway.last(Metadata.class).operation()).isEqualTo("getPseudoColumns");
            ResultSet ci = md.getClientInfoProperties();
            assertThat(gateway.last(Metadata.class).operation()).isEqualTo("getClientInfoProperties");
            assertThat(ci.next()).isTrue();
            assertThat(ci.getString("OPERATION")).isEqualTo("getClientInfoProperties");

            List<String> ops = gateway.received(Metadata.class).stream().map(Metadata::operation).toList();
            assertThat(ops).containsAll(Arrays.asList("getTables".equals("x") ? "" : "getSchemas", "getCatalogs", "getTableTypes",
                    "getColumns", "getPrimaryKeys", "getImportedKeys", "getExportedKeys", "getCrossReference", "getIndexInfo",
                    "getTypeInfo", "getProcedures", "getProcedureColumns", "getFunctions", "getFunctionColumns",
                    "getBestRowIdentifier", "getVersionColumns", "getTablePrivileges", "getColumnPrivileges", "getUDTs",
                    "getSuperTables", "getSuperTypes", "getAttributes", "getPseudoColumns", "getClientInfoProperties"));
        }
    }
}
