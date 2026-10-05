package org.dbplatform.jdbc;

import org.dbplatform.protocol.messages.HelloOk;
import org.dbplatform.protocol.messages.Message;
import org.dbplatform.protocol.messages.Metadata;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link DatabaseMetaData}: scalar values come from {@code HELLO_OK.serverProperties} (with driver defaults
 * for absent keys), result-set methods are forwarded to the physical {@code DatabaseMetaData} through the
 * METADATA message (specification section 4.4).
 */
public final class DbpDatabaseMetaData extends DbpWrapper implements DatabaseMetaData {

    private static final Pattern VERSION = Pattern.compile("(\\d+)(?:\\.(\\d+))?");

    private final DbpConnection connection;
    private final HelloOk hello;

    DbpDatabaseMetaData(DbpConnection connection) {
        this.connection = connection;
        this.hello = connection.hello();
    }

    // ---------------------------------------------------------------- helpers

    private String str(String key, String dflt) {
        String v = hello.property(key);
        return v == null ? dflt : v;
    }

    private boolean bool(String key, boolean dflt) {
        return hello.booleanProperty(key, dflt);
    }

    private int integer(String key, int dflt) {
        return hello.intProperty(key, dflt);
    }

    private ResultSet query(String operation, Object... args) throws SQLException {
        List<Message> replies = connection.exchange(Metadata.of(operation, args), -1);
        ExecutionResult result = ExecutionResult.parse(replies);
        for (ExecutionResult.Item item : result.items) {
            if (item instanceof ExecutionResult.ResultSetItem rs) {
                return new DbpResultSet(connection, null, rs.cursorId(), rs.header().columns(), rs.rows().rows(),
                        rs.rows().last(), connection.defaultFetchSize(), 0);
            }
        }
        return DbpResultSet.inMemory(connection, null, List.of(), List.of());
    }

    private static String joinInts(int[] values) {
        if (values == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(Metadata.ARRAY_SEPARATOR);
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private int versionPart(String key, int group) {
        String explicit = hello.property(key);
        if (explicit != null) {
            try {
                return Integer.parseInt(explicit.trim());
            } catch (NumberFormatException ignored) {
                // fall through to parsing the version string
            }
        }
        String text = str("databaseProductVersion", hello.serverVersion());
        if (text != null) {
            Matcher m = VERSION.matcher(text);
            if (m.find()) {
                String g = m.group(group);
                if (g != null) {
                    try {
                        return Integer.parseInt(g);
                    } catch (NumberFormatException ignored) {
                        // fall through
                    }
                }
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------- general

    @Override
    public boolean allProceduresAreCallable() {
        return false;
    }

    @Override
    public boolean allTablesAreSelectable() {
        return false;
    }

    @Override
    public String getURL() {
        return str(HelloOk.PROP_URL, connection.url().toString());
    }

    @Override
    public String getUserName() {
        return str("userName", connection.user() == null ? "" : connection.user());
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return bool("isReadOnly", connection.isReadOnly());
    }

    @Override
    public boolean nullsAreSortedHigh() {
        return bool("nullsAreSortedHigh", false);
    }

    @Override
    public boolean nullsAreSortedLow() {
        return bool("nullsAreSortedLow", false);
    }

    @Override
    public boolean nullsAreSortedAtStart() {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtEnd() {
        return false;
    }

    @Override
    public String getDatabaseProductName() {
        return str("databaseProductName", hello.engine() == null ? "DBP" : hello.engine());
    }

    @Override
    public String getDatabaseProductVersion() {
        return str("databaseProductVersion", hello.serverVersion() == null ? "" : hello.serverVersion());
    }

    @Override
    public String getDriverName() {
        return DriverVersion.DRIVER_NAME;
    }

    @Override
    public String getDriverVersion() {
        return DriverVersion.VERSION;
    }

    @Override
    public int getDriverMajorVersion() {
        return DriverVersion.MAJOR;
    }

    @Override
    public int getDriverMinorVersion() {
        return DriverVersion.MINOR;
    }

    @Override
    public boolean usesLocalFiles() {
        return false;
    }

    @Override
    public boolean usesLocalFilePerTable() {
        return false;
    }

    // ---------------------------------------------------------------- identifiers and SQL grammar

    @Override
    public boolean supportsMixedCaseIdentifiers() {
        return bool("supportsMixedCaseIdentifiers", false);
    }

    @Override
    public boolean storesUpperCaseIdentifiers() {
        return bool("storesUpperCaseIdentifiers", false);
    }

    @Override
    public boolean storesLowerCaseIdentifiers() {
        return bool("storesLowerCaseIdentifiers", false);
    }

    @Override
    public boolean storesMixedCaseIdentifiers() {
        return bool("storesMixedCaseIdentifiers", false);
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() {
        return true;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() {
        return true;
    }

    @Override
    public String getIdentifierQuoteString() {
        return str("identifierQuoteString", "\"");
    }

    @Override
    public String getSQLKeywords() {
        return str("sqlKeywords", "");
    }

    @Override
    public String getNumericFunctions() {
        return "";
    }

    @Override
    public String getStringFunctions() {
        return "";
    }

    @Override
    public String getSystemFunctions() {
        return "";
    }

    @Override
    public String getTimeDateFunctions() {
        return "";
    }

    @Override
    public String getSearchStringEscape() {
        return str("searchStringEscape", "\\");
    }

    @Override
    public String getExtraNameCharacters() {
        return str("extraNameCharacters", "");
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() {
        return true;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() {
        return true;
    }

    @Override
    public boolean supportsColumnAliasing() {
        return true;
    }

    @Override
    public boolean nullPlusNonNullIsNull() {
        return bool("nullPlusNonNullIsNull", true);
    }

    @Override
    public boolean supportsConvert() {
        return false;
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) {
        return false;
    }

    @Override
    public boolean supportsTableCorrelationNames() {
        return true;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() {
        return false;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() {
        return true;
    }

    @Override
    public boolean supportsOrderByUnrelated() {
        return true;
    }

    @Override
    public boolean supportsGroupBy() {
        return true;
    }

    @Override
    public boolean supportsGroupByUnrelated() {
        return true;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() {
        return true;
    }

    @Override
    public boolean supportsLikeEscapeClause() {
        return true;
    }

    @Override
    public boolean supportsMultipleResultSets() {
        return bool("supportsMultipleResultSets", true);
    }

    @Override
    public boolean supportsMultipleTransactions() {
        return true;
    }

    @Override
    public boolean supportsNonNullableColumns() {
        return true;
    }

    @Override
    public boolean supportsMinimumSQLGrammar() {
        return true;
    }

    @Override
    public boolean supportsCoreSQLGrammar() {
        return true;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() {
        return true;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() {
        return false;
    }

    @Override
    public boolean supportsANSI92FullSQL() {
        return false;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() {
        return false;
    }

    @Override
    public boolean supportsOuterJoins() {
        return bool("supportsOuterJoins", true);
    }

    @Override
    public boolean supportsFullOuterJoins() {
        return bool("supportsOuterJoins", true);
    }

    @Override
    public boolean supportsLimitedOuterJoins() {
        return bool("supportsOuterJoins", true);
    }

    @Override
    public String getSchemaTerm() {
        return str("schemaTerm", "schema");
    }

    @Override
    public String getProcedureTerm() {
        return str("procedureTerm", "procedure");
    }

    @Override
    public String getCatalogTerm() {
        return str("catalogTerm", "catalog");
    }

    @Override
    public boolean isCatalogAtStart() {
        return true;
    }

    @Override
    public String getCatalogSeparator() {
        return str("catalogSeparator", ".");
    }

    @Override
    public boolean supportsSchemasInDataManipulation() {
        return bool("supportsSchemasInDataManipulation", true);
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() {
        return bool("supportsSchemasInDataManipulation", true);
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() {
        return bool("supportsSchemasInTableDefinitions", true);
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() {
        return bool("supportsSchemasInTableDefinitions", true);
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() {
        return bool("supportsSchemasInTableDefinitions", true);
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() {
        return bool("supportsCatalogsInDataManipulation", false);
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() {
        return bool("supportsCatalogsInDataManipulation", false);
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() {
        return bool("supportsCatalogsInTableDefinitions", false);
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() {
        return bool("supportsCatalogsInTableDefinitions", false);
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() {
        return bool("supportsCatalogsInTableDefinitions", false);
    }

    @Override
    public boolean supportsPositionedDelete() {
        return false;
    }

    @Override
    public boolean supportsPositionedUpdate() {
        return false;
    }

    @Override
    public boolean supportsSelectForUpdate() {
        return true;
    }

    @Override
    public boolean supportsStoredProcedures() {
        return bool("supportsStoredProcedures", true);
    }

    @Override
    public boolean supportsSubqueriesInComparisons() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInExists() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInIns() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() {
        return true;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() {
        return true;
    }

    @Override
    public boolean supportsUnion() {
        return bool("supportsUnion", true);
    }

    @Override
    public boolean supportsUnionAll() {
        return bool("supportsUnionAll", true);
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() {
        return true;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() {
        return true;
    }

    // ---------------------------------------------------------------- limits

    @Override
    public int getMaxBinaryLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxCharLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxColumnNameLength() {
        return 0;
    }

    @Override
    public int getMaxColumnsInGroupBy() {
        return 0;
    }

    @Override
    public int getMaxColumnsInIndex() {
        return 0;
    }

    @Override
    public int getMaxColumnsInOrderBy() {
        return 0;
    }

    @Override
    public int getMaxColumnsInSelect() {
        return 0;
    }

    @Override
    public int getMaxColumnsInTable() {
        return 0;
    }

    @Override
    public int getMaxConnections() {
        return integer("maxConnections", 0);
    }

    @Override
    public int getMaxCursorNameLength() {
        return 0;
    }

    @Override
    public int getMaxIndexLength() {
        return 0;
    }

    @Override
    public int getMaxSchemaNameLength() {
        return 0;
    }

    @Override
    public int getMaxProcedureNameLength() {
        return 0;
    }

    @Override
    public int getMaxCatalogNameLength() {
        return 0;
    }

    @Override
    public int getMaxRowSize() {
        return 0;
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() {
        return false;
    }

    @Override
    public int getMaxStatementLength() {
        return integer("maxStatementLength", 0);
    }

    @Override
    public int getMaxStatements() {
        return 0;
    }

    @Override
    public int getMaxTableNameLength() {
        return 0;
    }

    @Override
    public int getMaxTablesInSelect() {
        return 0;
    }

    @Override
    public int getMaxUserNameLength() {
        return 0;
    }

    @Override
    public long getMaxLogicalLobSize() {
        return 0;
    }

    // ---------------------------------------------------------------- transactions

    @Override
    public int getDefaultTransactionIsolation() {
        return integer("defaultTransactionIsolation", Connection.TRANSACTION_READ_COMMITTED);
    }

    @Override
    public boolean supportsTransactions() {
        return bool("supportsTransactions", true);
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) {
        return switch (level) {
            case Connection.TRANSACTION_READ_UNCOMMITTED, Connection.TRANSACTION_READ_COMMITTED,
                 Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE -> true;
            default -> false;
        };
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() {
        return true;
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() {
        return false;
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() {
        return false;
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() {
        return false;
    }

    @Override
    public boolean supportsSavepoints() {
        return bool("supportsSavepoints", true);
    }

    @Override
    public boolean supportsBatchUpdates() {
        return bool("supportsBatchUpdates", true);
    }

    @Override
    public boolean supportsGetGeneratedKeys() {
        return bool("supportsGetGeneratedKeys", true);
    }

    @Override
    public boolean supportsNamedParameters() {
        return false;
    }

    @Override
    public boolean supportsMultipleOpenResults() {
        return true;
    }

    @Override
    public boolean supportsStatementPooling() {
        return false;
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() {
        return true;
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() {
        return false;
    }

    @Override
    public boolean generatedKeyAlwaysReturned() {
        return false;
    }

    @Override
    public boolean locatorsUpdateCopy() {
        return true;
    }

    @Override
    public RowIdLifetime getRowIdLifetime() {
        return RowIdLifetime.ROWID_UNSUPPORTED;
    }

    @Override
    public boolean supportsRefCursors() {
        return true;
    }

    @Override
    public boolean supportsSharding() {
        return false;
    }

    // ---------------------------------------------------------------- result sets

    @Override
    public boolean supportsResultSetType(int type) {
        return type == ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) {
        return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean updatesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean deletesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean insertsAreDetected(int type) {
        return false;
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) {
        return holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public int getResultSetHoldability() {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    // ---------------------------------------------------------------- versions

    @Override
    public int getDatabaseMajorVersion() {
        return versionPart("databaseMajorVersion", 1);
    }

    @Override
    public int getDatabaseMinorVersion() {
        return versionPart("databaseMinorVersion", 2);
    }

    @Override
    public int getJDBCMajorVersion() {
        return 4;
    }

    @Override
    public int getJDBCMinorVersion() {
        return 3;
    }

    @Override
    public int getSQLStateType() {
        return sqlStateSQL;
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    // ---------------------------------------------------------------- result-set methods (METADATA)

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) throws SQLException {
        return query("getProcedures", catalog, schemaPattern, procedureNamePattern);
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern,
                                         String columnNamePattern) throws SQLException {
        return query("getProcedureColumns", catalog, schemaPattern, procedureNamePattern, columnNamePattern);
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types)
            throws SQLException {
        return query("getTables", catalog, schemaPattern, tableNamePattern, Metadata.joinStringArray(types));
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        return query("getSchemas");
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        return query("getSchemas", catalog, schemaPattern);
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        return query("getCatalogs");
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        return query("getTableTypes");
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern)
            throws SQLException {
        return query("getColumns", catalog, schemaPattern, tableNamePattern, columnNamePattern);
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern)
            throws SQLException {
        return query("getColumnPrivileges", catalog, schema, table, columnNamePattern);
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        return query("getTablePrivileges", catalog, schemaPattern, tableNamePattern);
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable)
            throws SQLException {
        return query("getBestRowIdentifier", catalog, schema, table, scope, nullable);
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
        return query("getVersionColumns", catalog, schema, table);
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        return query("getPrimaryKeys", catalog, schema, table);
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        return query("getImportedKeys", catalog, schema, table);
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        return query("getExportedKeys", catalog, schema, table);
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable,
                                       String foreignCatalog, String foreignSchema, String foreignTable) throws SQLException {
        return query("getCrossReference", parentCatalog, parentSchema, parentTable, foreignCatalog, foreignSchema,
                foreignTable);
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        return query("getTypeInfo");
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate)
            throws SQLException {
        return query("getIndexInfo", catalog, schema, table, unique, approximate);
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types) throws SQLException {
        return query("getUDTs", catalog, schemaPattern, typeNamePattern, joinInts(types));
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) throws SQLException {
        return query("getSuperTypes", catalog, schemaPattern, typeNamePattern);
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        return query("getSuperTables", catalog, schemaPattern, tableNamePattern);
    }

    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern)
            throws SQLException {
        return query("getAttributes", catalog, schemaPattern, typeNamePattern, attributeNamePattern);
    }

    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        return query("getClientInfoProperties");
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) throws SQLException {
        return query("getFunctions", catalog, schemaPattern, functionNamePattern);
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern,
                                        String columnNamePattern) throws SQLException {
        return query("getFunctionColumns", catalog, schemaPattern, functionNamePattern, columnNamePattern);
    }

    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern)
            throws SQLException {
        return query("getPseudoColumns", catalog, schemaPattern, tableNamePattern, columnNamePattern);
    }

    @Override
    public String toString() {
        return "DbpDatabaseMetaData[" + getDatabaseProductName() + " " + getDatabaseProductVersion() + "]";
    }
}
