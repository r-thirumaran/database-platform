package org.dbplatform.jdbc;

import org.dbplatform.protocol.ColumnMeta;
import org.dbplatform.protocol.ValueTag;
import org.dbplatform.protocol.Values;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

/**
 * {@link ResultSetMetaData} over the {@link ColumnMeta} list of a RESULT_SET_HEADER or GENERATED_KEYS frame.
 */
public final class DbpResultSetMetaData extends DbpWrapper implements ResultSetMetaData {

    private final List<ColumnMeta> columns;

    DbpResultSetMetaData(List<ColumnMeta> columns) {
        this.columns = columns == null ? List.of() : columns;
    }

    List<ColumnMeta> columns() {
        return columns;
    }

    private ColumnMeta col(int column) throws SQLException {
        if (column < 1 || column > columns.size()) {
            throw DbpSqlExceptions.invalidIndex("column index " + column + " out of range 1.." + columns.size());
        }
        return columns.get(column - 1);
    }

    /** Java class returned by {@code getObject} for a value of the given JDBC type (driver-side mapping). */
    static String classNameFor(int jdbcType) {
        ValueTag tag = Values.tagForJdbcType(jdbcType);
        return switch (tag) {
            case NULL, TYPED_NULL -> "java.lang.Object";
            case BOOLEAN -> "java.lang.Boolean";
            case BYTE -> "java.lang.Byte";
            case SHORT -> "java.lang.Short";
            case INT -> "java.lang.Integer";
            case LONG -> "java.lang.Long";
            case FLOAT -> "java.lang.Float";
            case DOUBLE -> "java.lang.Double";
            case DECIMAL -> "java.math.BigDecimal";
            case STRING -> "java.lang.String";
            case BYTES -> "[B";
            case DATE -> "java.sql.Date";
            case TIME -> "java.sql.Time";
            case TIMESTAMP -> "java.sql.Timestamp";
            case TIMESTAMP_TZ -> "java.time.OffsetDateTime";
            case TIME_TZ -> "java.time.OffsetTime";
        };
    }

    @Override
    public int getColumnCount() {
        return columns.size();
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        return col(column).autoIncrement();
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        return col(column).caseSensitive();
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        return col(column).searchable();
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        return col(column).currency();
    }

    @Override
    public int isNullable(int column) throws SQLException {
        return col(column).nullable();
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        return col(column).signed();
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        return col(column).displaySize();
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        ColumnMeta c = col(column);
        return c.label() != null ? c.label() : c.name();
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        ColumnMeta c = col(column);
        return c.name() != null ? c.name() : c.label();
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
        String s = col(column).schemaName();
        return s == null ? "" : s;
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        return col(column).precision();
    }

    @Override
    public int getScale(int column) throws SQLException {
        return col(column).scale();
    }

    @Override
    public String getTableName(int column) throws SQLException {
        String s = col(column).tableName();
        return s == null ? "" : s;
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        String s = col(column).catalogName();
        return s == null ? "" : s;
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        return col(column).jdbcType();
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        String s = col(column).typeName();
        return s == null ? "" : s;
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        col(column);
        return true;
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        col(column);
        return false;
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        col(column);
        return false;
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        return classNameFor(col(column).jdbcType());
    }

    @Override
    public String toString() {
        return "DbpResultSetMetaData[" + columns.size() + " columns]";
    }
}
