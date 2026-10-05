package org.dbplatform.protocol;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/**
 * Column metadata as carried in RESULT_SET_HEADER and GENERATED_KEYS (section 4.5 of the specification).
 *
 * <pre>
 * ColumnMeta := string label, string name, i32 jdbcType, string typeName, i32 precision, i32 scale,
 *               u8 nullable, string className, string tableName, string schemaName, string catalogName,
 *               bool signed, bool autoIncrement, bool caseSensitive, bool currency, bool readOnly,
 *               bool searchable, i32 displaySize
 * </pre>
 *
 * @param label         {@code getColumnLabel}
 * @param name          {@code getColumnName}
 * @param jdbcType      {@code getColumnType} ({@link java.sql.Types})
 * @param typeName      {@code getColumnTypeName}
 * @param precision     {@code getPrecision}
 * @param scale         {@code getScale}
 * @param nullable      {@code isNullable}: 0 = columnNoNulls, 1 = columnNullable, 2 = columnNullableUnknown
 * @param className     {@code getColumnClassName}
 * @param tableName     {@code getTableName}
 * @param schemaName    {@code getSchemaName}
 * @param catalogName   {@code getCatalogName}
 * @param signed        {@code isSigned}
 * @param autoIncrement {@code isAutoIncrement}
 * @param caseSensitive {@code isCaseSensitive}
 * @param currency      {@code isCurrency}
 * @param readOnly      {@code isReadOnly}
 * @param searchable    {@code isSearchable}
 * @param displaySize   {@code getColumnDisplaySize}
 */
public record ColumnMeta(
        String label,
        String name,
        int jdbcType,
        String typeName,
        int precision,
        int scale,
        int nullable,
        String className,
        String tableName,
        String schemaName,
        String catalogName,
        boolean signed,
        boolean autoIncrement,
        boolean caseSensitive,
        boolean currency,
        boolean readOnly,
        boolean searchable,
        int displaySize) {

    /** {@link ResultSetMetaData#columnNoNulls}. */
    public static final int NO_NULLS = ResultSetMetaData.columnNoNulls;
    /** {@link ResultSetMetaData#columnNullable}. */
    public static final int NULLABLE = ResultSetMetaData.columnNullable;
    /** {@link ResultSetMetaData#columnNullableUnknown}. */
    public static final int NULLABLE_UNKNOWN = ResultSetMetaData.columnNullableUnknown;

    /**
     * Validates the nullable flag.
     *
     * @param label         see record
     * @param name          see record
     * @param jdbcType      see record
     * @param typeName      see record
     * @param precision     see record
     * @param scale         see record
     * @param nullable      must be 0, 1 or 2
     * @param className     see record
     * @param tableName     see record
     * @param schemaName    see record
     * @param catalogName   see record
     * @param signed        see record
     * @param autoIncrement see record
     * @param caseSensitive see record
     * @param currency      see record
     * @param readOnly      see record
     * @param searchable    see record
     * @param displaySize   see record
     */
    public ColumnMeta {
        if (nullable < 0 || nullable > 2) {
            throw new IllegalArgumentException("nullable must be 0, 1 or 2 but was " + nullable);
        }
    }

    /**
     * Minimal metadata for synthetic result sets (driver-side defaults, tests): nullable unknown, not
     * auto-increment, read-only, searchable, signed when numeric.
     *
     * @param label    column label (also used as name)
     * @param jdbcType {@link java.sql.Types} constant
     * @param typeName vendor type name
     * @return the metadata
     */
    public static ColumnMeta simple(String label, int jdbcType, String typeName) {
        boolean numeric = Values.tagForJdbcType(jdbcType) != ValueTag.STRING
                && Values.tagForJdbcType(jdbcType) != ValueTag.BYTES
                && Values.tagForJdbcType(jdbcType) != ValueTag.BOOLEAN;
        return new ColumnMeta(label, label, jdbcType, typeName, 0, 0, NULLABLE_UNKNOWN, null, null, null, null,
                numeric, false, !numeric, false, true, true, 0);
    }

    /**
     * Server-side convenience: captures the metadata of column {@code column} from a physical
     * {@link ResultSetMetaData}. Each attribute is read individually; attributes the physical driver
     * does not support ({@link SQLException}) fall back to a neutral default instead of failing the
     * whole header.
     *
     * @param md     physical metadata
     * @param column 1-based column index
     * @return the metadata
     * @throws SQLException if the mandatory attributes (label, name, type) cannot be read
     */
    public static ColumnMeta from(ResultSetMetaData md, int column) throws SQLException {
        return new ColumnMeta(
                md.getColumnLabel(column),
                md.getColumnName(column),
                md.getColumnType(column),
                orNull(() -> md.getColumnTypeName(column)),
                orDefault(() -> md.getPrecision(column), 0),
                orDefault(() -> md.getScale(column), 0),
                clampNullable(orDefault(() -> md.isNullable(column), NULLABLE_UNKNOWN)),
                orNull(() -> md.getColumnClassName(column)),
                orNull(() -> md.getTableName(column)),
                orNull(() -> md.getSchemaName(column)),
                orNull(() -> md.getCatalogName(column)),
                orDefault(() -> md.isSigned(column), false),
                orDefault(() -> md.isAutoIncrement(column), false),
                orDefault(() -> md.isCaseSensitive(column), false),
                orDefault(() -> md.isCurrency(column), false),
                orDefault(() -> md.isReadOnly(column), false),
                orDefault(() -> md.isSearchable(column), true),
                orDefault(() -> md.getColumnDisplaySize(column), 0));
    }

    /**
     * Writes this structure.
     *
     * @param out destination
     */
    public void encode(ProtocolOutput out) {
        out.writeString(label)
                .writeString(name)
                .writeI32(jdbcType)
                .writeString(typeName)
                .writeI32(precision)
                .writeI32(scale)
                .writeU8(nullable)
                .writeString(className)
                .writeString(tableName)
                .writeString(schemaName)
                .writeString(catalogName)
                .writeBool(signed)
                .writeBool(autoIncrement)
                .writeBool(caseSensitive)
                .writeBool(currency)
                .writeBool(readOnly)
                .writeBool(searchable)
                .writeI32(displaySize);
    }

    /**
     * Reads this structure.
     *
     * @param in source
     * @return the metadata
     * @throws ProtocolException if malformed
     */
    public static ColumnMeta decode(ProtocolInput in) throws ProtocolException {
        String label = in.readString();
        String name = in.readString();
        int jdbcType = in.readI32();
        String typeName = in.readString();
        int precision = in.readI32();
        int scale = in.readI32();
        int nullable = in.readU8();
        if (nullable > 2) {
            throw new ProtocolException("invalid ColumnMeta.nullable " + nullable);
        }
        String className = in.readString();
        String tableName = in.readString();
        String schemaName = in.readString();
        String catalogName = in.readString();
        boolean signed = in.readBool();
        boolean autoIncrement = in.readBool();
        boolean caseSensitive = in.readBool();
        boolean currency = in.readBool();
        boolean readOnly = in.readBool();
        boolean searchable = in.readBool();
        int displaySize = in.readI32();
        return new ColumnMeta(label, name, jdbcType, typeName, precision, scale, nullable, className, tableName,
                schemaName, catalogName, signed, autoIncrement, caseSensitive, currency, readOnly, searchable,
                displaySize);
    }

    /**
     * Returns the value tag the server uses for cells of this column.
     *
     * @return tag derived from {@link #jdbcType()}
     */
    public ValueTag valueTag() {
        return Values.tagForJdbcType(jdbcType);
    }

    private static int clampNullable(int n) {
        return (n < 0 || n > 2) ? NULLABLE_UNKNOWN : n;
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    private static <T> T orNull(SqlSupplier<T> s) {
        try {
            return s.get();
        } catch (SQLException | RuntimeException e) {
            return null;
        }
    }

    private static <T> T orDefault(SqlSupplier<T> s, T dflt) {
        try {
            T v = s.get();
            return v == null ? dflt : v;
        } catch (SQLException | RuntimeException e) {
            return dflt;
        }
    }
}
