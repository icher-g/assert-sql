package io.assertsql;

import io.sqm.core.*;
import io.sqm.core.collect.TableRefsCollector;
import io.sqm.core.walk.RecursiveNodeVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Inspects SQM models using dedicated collectors where available and SQM's
 * recursive visitor for other node types.
 */
final class ModelInspector {
    private ModelInspector() {
    }

    static boolean containsTable(Statement statement, Table expected) {
        var collector = new TableRefsCollector();
        statement.accept(collector);
        return collector.getTableRefs().stream()
            .filter(Table.class::isInstance)
            .map(Table.class::cast)
            .anyMatch(table -> Identifiers.matches(table.name(), expected.name())
                && Identifiers.matches(table.schema(), expected.schema())
                && Identifiers.matches(table.alias(), expected.alias()));
    }

    static boolean containsColumn(Statement statement, ColumnExpr expected) {
        return collect(statement, ColumnExpr.class)
            .stream()
            .anyMatch(actual ->
                Identifiers.matches(actual.name(), expected.name()) && Identifiers.matches(actual.tableAlias(), expected.tableAlias())
            );
    }

    static List<Join> joins(Statement statement) {
        return collect(statement, Join.class);
    }

    static Predicate outerWhere(Statement statement) {
        return switch (statement) {
            case WithQuery query -> outerWhere(query.body());
            case SelectQuery query -> query.where();
            case UpdateStatement update -> update.where();
            case DeleteStatement delete -> delete.where();
            default -> null;
        };
    }

    private static <T extends Node> List<T> collect(Node root, Class<T> type) {
        var nodes = new ArrayList<T>();
        var visitor = new RecursiveNodeVisitor<Void>() {
            @Override
            protected Void defaultResult() {
                return null;
            }

            @Override
            protected Void accept(Node node) {
                if (type.isInstance(node)) {
                    nodes.add(type.cast(node));
                }
                return super.accept(node);
            }
        };
        root.accept(visitor);
        return List.copyOf(nodes);
    }
}
