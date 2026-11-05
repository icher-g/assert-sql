package io.assertsql;

import io.sqm.core.*;
import io.sqm.core.dialect.SqlDialectId;
import io.sqm.parser.ansi.AnsiSpecs;
import io.sqm.parser.mysql.spi.MySqlSpecs;
import io.sqm.parser.oracle.spi.OracleSpecs;
import io.sqm.parser.postgresql.spi.PostgresSpecs;
import io.sqm.parser.spi.ParseContext;
import io.sqm.parser.spi.Specs;
import io.sqm.parser.sqlserver.spi.SqlServerSpecs;
import io.sqm.render.ansi.spi.AnsiDialect;
import io.sqm.render.mysql.spi.MySqlDialect;
import io.sqm.render.oracle.spi.OracleDialect;
import io.sqm.render.postgresql.spi.PostgresDialect;
import io.sqm.render.spi.SqlDialect;
import io.sqm.render.sqlserver.spi.SqlServerDialect;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

final class DefaultExpect implements Expect {
    private final Supplier<Specs> specs;
    private final String description;
    private final List<Function<ParseContext, Check>> requirements;

    private DefaultExpect(Supplier<Specs> specs, String description, List<Function<ParseContext, Check>> requirements) {
        this.specs = specs;
        this.description = description;
        this.requirements = List.copyOf(requirements);
    }

    static Expect.Builder builder(SqlDialectId dialect) {
        return switch (dialect.value()) {
            case "ansi" -> new Builder(AnsiSpecs::new, AnsiDialect::new);
            case "postgresql" -> new Builder(PostgresSpecs::new, PostgresDialect::new);
            case "mysql" -> new Builder(MySqlSpecs::new, MySqlDialect::new);
            case "sqlserver" -> new Builder(SqlServerSpecs::new, SqlServerDialect::new);
            case "oracle" -> new Builder(OracleSpecs::new, OracleDialect::new);
            default -> throw new IllegalArgumentException("Unsupported SQL dialect: " + dialect.value());
        };
    }

    @Override
    public Match evaluate(String actualSql) {
        Objects.requireNonNull(actualSql, "actualSql");
        var context = ParseContext.of(specs.get());
        var actual = context.parse(Statement.class, actualSql);
        var mismatches = new ArrayList<Mismatch>();
        if (!actual.ok()) {
            for (var problem : actual.problems()) {
                mismatches.add(new Mismatch("PARSE_ERROR", "sql[" + problem.pos() + "]", problem.message()));
            }
        }
        else {
            // Prepare the full declaration before invoking any custom checks.
            var checks = requirements.stream().map(requirement -> requirement.apply(context)).toList();
            for (var check : checks) {
                mismatches.addAll(List.copyOf(check.evaluate(actual.value())));
            }
        }
        var match = Match.of(mismatches);
        return description == null ? match : new DescribedMatch(description, match);
    }

    private static final class Builder implements Expect.Builder {
        private final Supplier<Specs> specs;
        private final Supplier<SqlDialect> renderer;
        private final List<Function<ParseContext, Check>> requirements = new ArrayList<>();
        private String description;

        private Builder(Supplier<Specs> specs, Supplier<SqlDialect> renderer) {
            this.specs = specs;
            this.renderer = renderer;
        }

        @Override
        public Expect.Builder as(String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        @Override
        public Expect.Builder select() {
            return satisfies(statement ->
                statement instanceof Query
                    ? List.of()
                    : List.of(new Mismatch("NOT_SELECT", "statement", "Expected a SELECT query."))
            );
        }

        @Override
        public Expect.Builder table(String... names) {
            return presence(names, "table", Table.class, false, ModelInspector::containsTable);
        }

        @Override
        public Expect.Builder noTable(String... names) {
            return presence(names, "table", Table.class, true, ModelInspector::containsTable);
        }

        @Override
        public Expect.Builder column(String... names) {
            return presence(names, "column", ColumnExpr.class, false, ModelInspector::containsColumn);
        }

        @Override
        public Expect.Builder noColumn(String... names) {
            return presence(names, "column", ColumnExpr.class, true, ModelInspector::containsColumn);
        }

        @Override
        public Expect.Builder join(String... joins) {
            return presence(joins, "join", Join.class, false, (statement, expected) -> ModelInspector.joins(statement).contains(expected));
        }

        @Override
        public Expect.Builder noJoin(String... joins) {
            return presence(joins, "join", Join.class, true, (statement, expected) -> ModelInspector.joins(statement).contains(expected));
        }

        @Override
        public Expect.Builder noJoin() {
            return satisfies(statement ->
                ModelInspector.joins(statement).isEmpty()
                    ? List.of()
                    : List.of(new Mismatch("JOIN_PRESENT", "statement", "Expected no joins, including in nested queries."))
            );
        }

        @Override
        public Expect.Builder joinTable(String... names) {
            return joinedTables(names, null, false);
        }

        @Override
        public Expect.Builder joinTable(JoinKind kind, String... names) {
            return joinedTables(names, Objects.requireNonNull(kind, "kind"), false);
        }

        @Override
        public Expect.Builder noJoinTable(String... names) {
            return joinedTables(names, null, true);
        }

        @Override
        public Expect.Builder noJoinTable(JoinKind kind, String... names) {
            return joinedTables(names, Objects.requireNonNull(kind, "kind"), true);
        }

        @Override
        public Expect.Builder projection(String... items) {
            return presence(
                items,
                "projection",
                SelectItem.class,
                false,
                (statement, expected) -> ModelInspector.projection(statement).contains(expected)
            );
        }

        @Override
        public Expect.Builder noProjection(String... items) {
            return presence(
                items,
                "projection",
                SelectItem.class,
                true,
                (statement, expected) -> ModelInspector.projection(statement).contains(expected)
            );
        }

        @Override
        public Expect.Builder exactProjection(String... items) {
            return orderedItems(items, "projection", SelectItem.class, ModelInspector::projection);
        }

        @Override
        public Expect.Builder orderBy(String... items) {
            return orderedItems(items, "order_by", OrderItem.class, statement -> {
                var order = ModelInspector.orderBy(statement);
                return order == null ? List.of() : order.items();
            });
        }

        @Override
        public Expect.Builder noOrderBy() {
            return satisfies(
                outerClause(statement ->
                    result(ModelInspector.orderBy(statement) == null, "ORDER_BY_PRESENT", "statement.orderBy", "Expected no outer ORDER BY.")
                )
            );
        }

        @Override
        public Expect.Builder limit(long count) {
            nonNegative(count);
            return satisfies(
                outerClause(statement ->
                    result(
                        literalCount(ModelInspector.limit(statement), count),
                        "LIMIT_MISMATCH",
                        "statement.limit",
                        "Expected a literal outer row limit of " + count + "."
                    )
                )
            );
        }

        @Override
        public Expect.Builder noLimit() {
            return satisfies(
                outerClause(statement ->
                    result(!ModelInspector.hasLimit(statement), "LIMIT_PRESENT", "statement.limit", "Expected no outer limit specification.")
                )
            );
        }

        @Override
        public Expect.Builder offset(long count) {
            nonNegative(count);
            return satisfies(
                outerClause(statement ->
                    result(
                        literalCount(ModelInspector.offset(statement), count),
                        "OFFSET_MISMATCH",
                        "statement.offset",
                        "Expected a literal outer offset of " + count + "."
                    )
                )
            );
        }

        @Override
        public Expect.Builder noOffset() {
            return satisfies(
                outerClause(statement ->
                    result(ModelInspector.offset(statement) == null, "OFFSET_PRESENT", "statement.offset", "Expected no outer offset specification.")
                )
            );
        }

        @Override
        public Expect.Builder parameter(String... names) {
            return namedParameters(names, false);
        }

        @Override
        public Expect.Builder noParameter(String... names) {
            return namedParameters(names, true);
        }

        @Override
        public Expect.Builder parameterCount(int count) {
            return parametersCount(count, false);
        }

        @Override
        public Expect.Builder positionalParameterCount(int count) {
            return parametersCount(count, true);
        }

        @Override
        public Expect.Builder where(String... predicates) {
            return presence(
                predicates,
                "where",
                Predicate.class,
                false,
                (statement, expected) -> expected.equals(ModelInspector.outerWhere(statement))
            );
        }

        @Override
        public Expect.Builder noWhere(String... predicates) {
            return presence(
                predicates,
                "where",
                Predicate.class,
                true,
                (statement, expected) -> expected.equals(ModelInspector.outerWhere(statement))
            );
        }

        @Override
        public Expect.Builder noWhere() {
            return satisfies(statement ->
                ModelInspector.outerWhere(statement) == null
                    ? List.of()
                    : List.of(new Mismatch("WHERE_PRESENT", "statement.where", "Expected no outer WHERE predicate."))
            );
        }

        @Override
        public Expect.Builder noSelectStar() {
            return satisfies(SelectStars::find);
        }

        @Override
        public Expect.Builder structurallyEquals(String expectedSql) {
            Objects.requireNonNull(expectedSql, "expectedSql");
            var dialect = renderer;
            return append(context -> {
                var parsed = context.parse(Statement.class, expectedSql);
                if (!parsed.ok()) {
                    throw new IllegalArgumentException("Invalid expected SQL: " + parsed.errorMessage());
                }
                var expected = parsed.value();
                return actual -> expected.equals(actual) ? List.of() : List.of(StructureDifference.between(expected, actual, dialect.get()));
            });
        }

        @Override
        public Expect.Builder satisfies(Check check) {
            Objects.requireNonNull(check, "check");
            return append(context -> check);
        }

        @Override
        public Expect build() {
            if (requirements.isEmpty()) {
                throw new IllegalStateException("Declare at least one check before building an expectation.");
            }
            return new DefaultExpect(specs, description, requirements);
        }

        private static <T extends Node> T parseExpected(ParseContext context, Class<T> type, String sql, String kind) {
            var parsed = context.parse(type, sql);
            if (!parsed.ok()) {
                throw new IllegalArgumentException("Invalid expected " + kind + ": " + parsed.errorMessage());
            }
            var expected = parsed.value();
            if (
                expected instanceof Table table
                    && (
                        table.inheritance() != Table.Inheritance.DEFAULT
                            || !table.hints().isEmpty()
                            || table.version() != null
                            || table.partitionSpec() != null
                    )
            ) {
                throw new IllegalArgumentException("Expected a table reference without hints or modifiers: " + sql);
            }
            return expected;
        }

        private static Check outerClause(Check check) {
            return statement ->
                ModelInspector.ambiguousSetTail(statement)
                    ? List.of(
                        new Mismatch(
                            "SQM_SCOPE_UNSUPPORTED",
                            "statement",
                            "SQM 0.5.0 cannot distinguish outer from final-term ordering/pagination in this set-query model. "
                                + "Use parenthesized terms with clauses on the composite query."
                        )
                    )
                    : check.evaluate(statement);
        }

        private static boolean literalCount(Expression expression, long expected) {
            return expression instanceof LiteralExpr literal
                && literal.value() instanceof Number number
                && new BigDecimal(number.toString()).compareTo(BigDecimal.valueOf(expected)) == 0;
        }

        private static void nonNegative(long count) {
            if (count < 0) {
                throw new IllegalArgumentException("Count must be non-negative.");
            }
        }

        private static List<Mismatch> result(boolean matched, String code, String path, String message) {
            return matched ? List.of() : List.of(new Mismatch(code, path, message));
        }

        private static List<String> fragments(String[] fragments, String kind) {
            var snapshot = List.of(Objects.requireNonNull(fragments, kind));
            if (snapshot.isEmpty()) {
                throw new IllegalArgumentException("Provide at least one " + kind + " fragment.");
            }
            return snapshot;
        }

        private Expect.Builder joinedTables(String[] names, JoinKind kind, boolean forbidden) {
            return presence(
                names,
                "join_table",
                Table.class,
                forbidden,
                (statement, expected) -> ModelInspector.containsJoinTable(statement, expected, kind)
            );
        }

        private <T extends Node> Expect.Builder orderedItems(String[] items, String kind, Class<T> type, Function<Statement, List<T>> inspect) {
            var snapshot = fragments(items, kind);
            var dialect = renderer;
            return append(context -> {
                var expected = snapshot.stream().map(item -> parseExpected(context, type, item, kind)).toList();
                Check check = statement -> {
                    var actual = inspect.apply(statement);
                    if (expected.equals(actual)) {
                        return List.of();
                    }
                    var difference = StructureDifference.between(expected, actual, dialect.get());
                    return List.of(new Mismatch(kind.toUpperCase(Locale.ROOT) + "_MISMATCH", difference.path(), difference.message()));
                };
                return kind.equals("order_by") ? outerClause(check) : check;
            });
        }

        private Expect.Builder namedParameters(String[] names, boolean forbidden) {
            var snapshot = fragments(names, "parameter");
            for (var name : snapshot) {
                if (name.isBlank() || name.startsWith(":") || name.startsWith("@") || name.startsWith("#{")) {
                    throw new IllegalArgumentException("Use a non-blank canonical parameter name without prefix: " + name);
                }
            }
            for (var name : snapshot) {
                satisfies(statement -> {
                    boolean present = ModelInspector.parameters(statement)
                        .named()
                        .stream()
                        .map(NamedParamExpr.class::cast)
                        .anyMatch(parameter -> parameter.name().equals(name));
                    return result(
                        present != forbidden,
                        forbidden ? "PARAMETER_PRESENT" : "PARAMETER_NOT_REFERENCED",
                        "statement.parameters",
                        (forbidden ? "Unexpected" : "Expected") + " named parameter <" + name + ">."
                    );
                });
            }
            return this;
        }

        private Expect.Builder parametersCount(int count, boolean positionalOnly) {
            nonNegative(count);
            return satisfies(statement -> {
                var parameters = ModelInspector.parameters(statement);
                int actual = parameters.positional().size() + (positionalOnly ? 0 : parameters.named().size());
                return result(
                    actual == count,
                    "PARAMETER_COUNT_MISMATCH",
                    "statement.parameters",
                    "Expected " + count + (positionalOnly ? " positional" : " total") + " placeholder occurrences but found " + actual + "."
                );
            });
        }

        private Expect.Builder append(Function<ParseContext, Check> requirement) {
            requirements.add(requirement);
            return this;
        }

        private <T extends Node> Expect.Builder presence(
            String[] fragments,
            String kind,
            Class<T> type,
            boolean forbidden,
            BiPredicate<Statement, T> contains
        ) {
            var snapshot = fragments(fragments, kind);
            for (var fragment : snapshot) {
                append(context -> {
                    var expected = parseExpected(context, type, fragment, kind);
                    return statement -> {
                        boolean present = contains.test(statement, expected);
                        if (present != forbidden) {
                            return List.of();
                        }
                        var code = kind.toUpperCase(Locale.ROOT) + (forbidden ? "_PRESENT" : "_NOT_REFERENCED");
                        var message = (forbidden ? "Unexpected " : "Expected ") + kind + " <" + fragment + ">.";
                        return List.of(new Mismatch(code, "statement", message));
                    };
                });
            }
            return this;
        }
    }
}

record DescribedMatch(String description, Match delegate) implements Match {
    @Override
    public List<Mismatch> mismatches() {
        return delegate.mismatches();
    }

    @Override
    public String describe() {
        return "[" + description + "] " + delegate.describe();
    }
}
