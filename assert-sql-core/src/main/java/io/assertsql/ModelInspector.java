package io.assertsql;

import io.sqm.core.*;
import io.sqm.core.collect.TableRefsCollector;
import io.sqm.core.collect.ParametersCollector;
import io.sqm.core.walk.RecursiveNodeVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Inspects SQM models using dedicated collectors where available and SQM's
 * recursive visitor for other node types.
 */
final class ModelInspector {
    private ModelInspector() {
    }

    static boolean containsTable(Statement statement, Table expected, boolean local) {
        var level = queryLevel(statement, local);
        var collector = new TableRefsCollector() {
            @Override
            protected Void accept(Node node) {
                return withinLevel(node, level) ? super.accept(node) : null;
            }
        };
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

    static boolean containsJoinTable(Statement statement, Table expected, JoinKind kind, boolean local) {
        return joins(statement, local).stream().anyMatch(join ->
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

    static GroupBy groupBy(Statement statement) {
        return outerBody(statement) instanceof SelectQuery select ? select.groupBy() : null;
    }

    static List<GroupItem> groupItems(Statement statement) {
        var groupBy = groupBy(statement);
        return groupBy == null ? List.of() : groupBy.items();
    }

    static Predicate having(Statement statement) {
        return outerBody(statement) instanceof SelectQuery select ? select.having() : null;
    }

    static DistinctSpec distinct(Statement statement) {
        return outerBody(statement) instanceof SelectQuery select ? select.distinct() : null;
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

    static ParametersCollector parameters(Statement statement, boolean local) {
        var level = queryLevel(statement, local);
        var collector = new ParametersCollector() {
            @Override
            protected Void accept(Node node) {
                return withinLevel(node, level) ? super.accept(node) : null;
            }
        };
        statement.accept(collector);
        return collector;
    }

    static boolean containsColumn(Statement statement, ColumnExpr expected, boolean local) {
        return collect(statement, ColumnExpr.class, local)
            .stream()
            .anyMatch(actual ->
                Identifiers.matches(actual.name(), expected.name()) && Identifiers.matches(actual.tableAlias(), expected.tableAlias())
            );
    }

    static List<Join> joins(Statement statement, boolean local) {
        return collect(statement, Join.class, local);
    }

    static List<QueryTable> derivedTables(Statement statement) {
        return collect(statement, QueryTable.class, true);
    }

    static List<ExistsPredicate> existsPredicates(Statement statement) {
        return collect(statement, ExistsPredicate.class, true);
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

    static Set<Statement> queryLevel(Statement statement, boolean local) {
        if (!local) {
            return null;
        }
        Set<Statement> level = Collections.newSetFromMap(new IdentityHashMap<>());
        addLevel(statement, level);
        return level;
    }

    static boolean withinLevel(Node node, Set<Statement> level) {
        return level == null || !(node instanceof CteDef) && (!(node instanceof Statement statement) || level.contains(statement));
    }

    private static LimitOffset pagination(Statement statement) {
        return switch (outerBody(statement)) {
            case SelectQuery select -> select.limitOffset();
            case CompositeQuery composite -> composite.limitOffset();
            default -> null;
        };
    }

    private static void addLevel(Statement statement, Set<Statement> level) {
        if (statement == null || !level.add(statement)) {
            return;
        }
        if (statement instanceof WithQuery with) {
            addLevel(with.body(), level);
        } else if (statement instanceof CompositeQuery composite) {
            composite.terms().forEach(term -> addLevel(term, level));
        }
    }

    private static <T extends Node> List<T> collect(Statement root, Class<T> type, boolean local) {
        var nodes = new ArrayList<T>();
        var level = queryLevel(root, local);
        var visitor = new RecursiveNodeVisitor<Void>() {
            @Override
            protected Void defaultResult() {
                return null;
            }

            @Override
            protected Void accept(Node node) {
                if (!withinLevel(node, level)) {
                    return null;
                }
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
