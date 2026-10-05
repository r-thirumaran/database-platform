package org.dbplatform.common.sql;

import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.execute.Execute;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@link TablesNamesFinder} extension that additionally records the {@link Table} objects (with aliases),
 * column references, function calls, CTE names and executed routines while walking the statement.
 */
final class JsqlVisitor extends TablesNamesFinder<Void> {

    final List<Table> tables = new ArrayList<>();
    final List<Column> columns = new ArrayList<>();
    final List<Function> functions = new ArrayList<>();
    final Set<String> cteNames = new LinkedHashSet<>();
    final List<String> executed = new ArrayList<>();

    @Override
    protected void init(boolean allowColumnProcessing) {
        super.init(allowColumnProcessing);
        tables.clear();
        columns.clear();
        functions.clear();
        cteNames.clear();
        executed.clear();
    }

    @Override
    public <S> Void visit(Table table, S context) {
        tables.add(table);
        return super.visit(table, context);
    }

    @Override
    public <S> Void visit(Column column, S context) {
        columns.add(column);
        return super.visit(column, context);
    }

    @Override
    public <S> Void visit(Function function, S context) {
        functions.add(function);
        return super.visit(function, context);
    }

    @Override
    public <S> Void visit(TableFunction tableFunction, S context) {
        Function f = tableFunction.getFunction();
        if (f != null) {
            functions.add(f);
        }
        return super.visit(tableFunction, context);
    }

    @Override
    public <S> Void visit(WithItem<?> withItem, S context) {
        if (withItem.getAlias() != null && withItem.getAlias().getName() != null) {
            cteNames.add(Identifiers.unquote(withItem.getAlias().getName()).toLowerCase(Locale.ROOT));
        }
        return super.visit(withItem, context);
    }

    @Override
    public <S> Void visit(Execute execute, S context) {
        if (execute.getName() != null) {
            executed.add(execute.getName());
        }
        return null; // TablesNamesFinder throws UnsupportedOperationException here
    }

    @Override
    public <S> Void visit(CreateIndex createIndex, S context) {
        if (createIndex.getTable() != null) {
            visit(createIndex.getTable(), context);
        }
        return null;
    }

    /** Name key the superclass used when registering the table (so we can tell CTE references apart). */
    String keyOf(Table table) {
        return extractTableName(table);
    }
}
