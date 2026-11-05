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

    private DefaultExpect(Supplier<Specs> specs, String description,
        List<Function<ParseContext, Check>> requirements) {
        this.specs = specs;
        this.description = description;
        this.requirements = List.copyOf(requirements);
    }

    static Expect.Builder builder(SqlDialectId dialect) {
        Supplier<Specs> specs = switch (dialect.value()) {
            case "ansi" -> AnsiSpecs::new;
            case "postgresql" -> PostgresSpecs::new;
            case "mysql" -> MySqlSpecs::new;
            case "sqlserver" -> SqlServerSpecs::new;
            case "oracle" -> OracleSpecs::new;
            default -> throw new IllegalArgumentException("Unsupported SQL dialect: " + dialect.value());
        };
        return new Builder(specs);
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
        private final List<Function<ParseContext, Check>> requirements = new ArrayList<>();
        private String description;

        private Builder(Supplier<Specs> specs) {
            this.specs = specs;
        }

        private static <T extends Node> T parseExpected(ParseContext context, Class<T> type, String sql, String kind) {
            var parsed = context.parse(type, sql);
            if (!parsed.ok()) {
                throw new IllegalArgumentException("Invalid expected " + kind + ": " + parsed.errorMessage());
            }
            var expected = parsed.value();
            if (expected instanceof Table table && (table.inheritance() != Table.Inheritance.DEFAULT
                || !table.hints().isEmpty() || table.version() != null || table.partitionSpec() != null)) {
                throw new IllegalArgumentException("Expected a table reference without hints or modifiers: " + sql);
            }
            return expected;
        }

        @Override
        public Expect.Builder as(String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        @Override
        public Expect.Builder select() {
            return satisfies(statement -> statement instanceof Query
                ? List.of()
                : List.of(new Mismatch("NOT_SELECT", "statement", "Expected a SELECT query.")));
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
            return presence(joins, "join", Join.class, false,
                (statement, expected) -> ModelInspector.joins(statement).contains(expected));
        }

        @Override
        public Expect.Builder noJoin(String... joins) {
            return presence(joins, "join", Join.class, true,
                (statement, expected) -> ModelInspector.joins(statement).contains(expected));
        }

        @Override
        public Expect.Builder noJoin() {
            return satisfies(statement -> ModelInspector.joins(statement).isEmpty() ? List.of()
                : List.of(new Mismatch("JOIN_PRESENT", "statement", "Expected no joins, including in nested queries.")));
        }

        @Override
        public Expect.Builder where(String... predicates) {
            return presence(predicates, "where", Predicate.class, false,
                (statement, expected) -> expected.equals(ModelInspector.outerWhere(statement)));
        }

        @Override
        public Expect.Builder noWhere(String... predicates) {
            return presence(predicates, "where", Predicate.class, true,
                (statement, expected) -> expected.equals(ModelInspector.outerWhere(statement)));
        }

        @Override
        public Expect.Builder noWhere() {
            return satisfies(statement -> ModelInspector.outerWhere(statement) == null ? List.of()
                : List.of(new Mismatch("WHERE_PRESENT", "statement.where", "Expected no outer WHERE predicate.")));
        }

        @Override
        public Expect.Builder noSelectStar() {
            return satisfies(SelectStars::find);
        }

        @Override
        public Expect.Builder structurallyEquals(String expectedSql) {
            Objects.requireNonNull(expectedSql, "expectedSql");
            return append(context -> {
                var parsed = context.parse(Statement.class, expectedSql);
                if (!parsed.ok()) {
                    throw new IllegalArgumentException("Invalid expected SQL: " + parsed.errorMessage());
                }
                var expected = parsed.value();
                return actual -> expected.equals(actual)
                    ? List.of()
                    : List.of(StructureDifference.between(expected, actual));
            });
        }

        @Override
        public Expect.Builder satisfies(Check check) {
            Objects.requireNonNull(check, "check");
            return append(context -> check);
        }

        private Expect.Builder append(Function<ParseContext, Check> requirement) {
            requirements.add(requirement);
            return this;
        }

        @Override
        public Expect build() {
            if (requirements.isEmpty()) {
                throw new IllegalStateException("Declare at least one check before building an expectation.");
            }
            return new DefaultExpect(specs, description, requirements);
        }

        private <T extends Node> Expect.Builder presence(String[] fragments, String kind, Class<T> type, boolean forbidden, BiPredicate<Statement, T> contains) {
            var snapshot = List.of(Objects.requireNonNull(fragments, kind));
            if (snapshot.isEmpty()) {
                throw new IllegalArgumentException("Provide at least one " + kind + " fragment.");
            }
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
