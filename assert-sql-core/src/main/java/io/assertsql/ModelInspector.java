package io.assertsql;

import io.sqm.core.*;
import io.sqm.core.collect.TableRefsCollector;
import io.sqm.core.collect.ParametersCollector;
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
        return collector.getTableRefs()
            .stream()
            .filter(Table.class::isInstance)
            .map(Table.class::cast)
            .anyMatch(table -> matchesTable(table, expected));
    }

    static boolean matchesTable(Table actual, Table expected) {
        return Identifiers.matches(actual.name(), expected.name())
            && Identifiers.matches(actual.schema(), expected.schema())
            && Identifiers.matches(actual.alias(), expected.alias());
    }

    static boolean containsJoinTable(Statement statement, Table expected, JoinKind kind) {
        return joins(statement).stream().anyMatch(join ->
            join.right() instanceof Table table
                && matchesTable(table, expected)
                && (kind == null || switch (join) {
                    case OnJoin on -> on.kind() == kind;
                    case UsingJoin using -> using.kind() == kind;
                    default -> false;
                })
        );
    }

    static Statement outerBody(Statement statement) {
        return statement instanceof WithQuery with ? outerBody(with.body()) : statement;
    }

    static List<SelectItem> projection(Statement statement) {
        return switch (outerBody(statement)) {
            case SelectQuery select -> select.items();
            case CompositeQuery composite -> projection(composite.terms().getFirst());
            default -> List.of();
        };
    }

    static OrderBy orderBy(Statement statement) {
        return switch (outerBody(statement)) {
            case SelectQuery select -> select.orderBy();
            case CompositeQuery composite -> composite.orderBy();
            default -> null;
        };
    }

    static boolean ambiguousSetTail(Statement statement) {
        if (!(outerBody(statement) instanceof CompositeQuery composite)) {
            return false;
        }
        var last = composite.terms().getLast();
        return orderBy(last) != null || hasLimit(last) || offset(last) != null || ambiguousSetTail(last);
    }

    static TopSpec top(Statement statement) {
        return outerBody(statement) instanceof SelectQuery select ? select.topSpec() : null;
    }

    static boolean hasLimit(Statement statement) {
        var pagination = pagination(statement);
        return top(statement) != null || (pagination != null && (pagination.limit() != null || pagination.limitAll()));
    }

    static Expression limit(Statement statement) {
        var pagination = pagination(statement);
        if (pagination != null && pagination.limit() != null) {
            return pagination.limit();
        }
        var top = top(statement);
        return top == null || top.percent() || top.withTies() ? null : top.count();
    }

    static Expression offset(Statement statement) {
        var pagination = pagination(statement);
        return pagination == null ? null : pagination.offset();
    }

    static ParametersCollector parameters(Statement statement) {
        var collector = new ParametersCollector();
        statement.accept(collector);
        return collector;
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

    private static LimitOffset pagination(Statement statement) {
        return switch (outerBody(statement)) {
            case SelectQuery select -> select.limitOffset();
            case CompositeQuery composite -> composite.limitOffset();
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
