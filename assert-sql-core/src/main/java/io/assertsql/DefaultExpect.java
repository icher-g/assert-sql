package io.assertsql;

import io.sqm.core.*;
import io.sqm.core.dialect.SqlDialectId;
import io.sqm.parser.spi.ParseContext;
import io.sqm.parser.spi.Specs;
import io.sqm.render.spi.SqlDialect;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class DefaultExpect implements Expect {
    private final Supplier<Specs> specs;
    private final String description;
    private final List<Function<ParseContext, ParameterizedCheck>> requirements;
    private final Redaction redaction;
    private final List<String> diagnosticSources;
    private final List<Object> expectedBindings;

    private DefaultExpect(Builder builder, List<Function<ParseContext, ParameterizedCheck>> requirements) {
        this.specs = builder.specs;
        this.description = builder.description;
        this.requirements = List.copyOf(requirements);
        this.redaction = builder.redaction;
        this.diagnosticSources = List.copyOf(builder.diagnosticSources);
        this.expectedBindings = java.util.Collections.unmodifiableList(new ArrayList<>(builder.expectedBindings));
    }

    static Expect.Builder builder(SqlDialectId dialect) {
        var support = SqlDialects.of(dialect);
        return new Builder(support.parser(), support.renderer());
    }

    @Override
    public Match evaluate(String actualSql) {
        return evaluate(ParameterizedSql.of(Objects.requireNonNull(actualSql, "actualSql")));
    }

    @Override
    public Match evaluate(ParameterizedSql input) {
        return evaluate(input, null);
    }

    Match evaluate(ParameterizedSql input, Redaction override) {
        Objects.requireNonNull(input, "input");
        var context = ParseContext.of(specs.get());
        var diagnostics = diagnostics(context, override, input);
        var actual = context.parse(Statement.class, input.sql());
        var mismatches = new ArrayList<Mismatch>();
        if (!actual.ok()) {
            for (var problem : actual.problems()) {
                mismatches.add(diagnostics.sanitize(new Mismatch("PARSE_ERROR", "sql[" + problem.pos() + "]", problem.message())));
            }
        }
        else {
            return prepare(context, override, input).apply(actual.value(), input);
        }
        var match = Match.of(mismatches);
        return description == null ? match : new DescribedMatch(diagnostics.description(description), match);
    }

    BiFunction<Statement, ParameterizedSql, Match> prepare(ParseContext context, Redaction override) {
        return prepare(context, override, ParameterizedSql.of(""));
    }

    private BiFunction<Statement, ParameterizedSql, Match> prepare(ParseContext context, Redaction override, ParameterizedSql preparingInput) {
        // Prepare the full declaration before invoking any custom checks.
        List<ParameterizedCheck> checks;
        try {
            checks = requirements.stream()
                .map(requirement -> requirement.apply(context))
                .toList();
        }
        catch (InvalidExpectationException failure) {
            throw diagnostics(context, override, preparingInput).configuration(failure);
        }
        return (statement, input) -> {
            var diagnostics = diagnostics(context, override, input);
            var mismatches = new ArrayList<Mismatch>();
            var bindings = new BindingContext(input, statement, diagnostics);
            for (var check : checks) {
                mismatches.addAll(List.copyOf(check.evaluate(statement, bindings)));
            }
            var safe = mismatches.stream()
                .map(diagnostics::sanitize)
                .toList();
            var match = Match.of(safe);
            return description == null ? match : new DescribedMatch(diagnostics.description(description), match);
        };
    }

    private DiagnosticContext diagnostics(ParseContext context, Redaction override, ParameterizedSql input) {
        return new DiagnosticContext(
            override == null ? redaction : override,
            context.identifierQuoting(),
            diagnosticSources,
            expectedBindings,
            input
        );
    }

    private static final class Builder implements Expect.Builder {
        private final Supplier<Specs> specs;
        private final Supplier<SqlDialect> renderer;
        private final boolean localReferences;
        private final List<Function<ParseContext, ParameterizedCheck>> requirements = new ArrayList<>();
        private final Set<BindingContext.Slot> parameters = new LinkedHashSet<>();
        private boolean matchAllParameters;
        private Redaction redaction = Redaction.BINDINGS;
        private final List<String> diagnosticSources = new ArrayList<>();
        private final List<Object> expectedBindings = new ArrayList<>();
        private String description;

        private Builder(Supplier<Specs> specs, Supplier<SqlDialect> renderer) {
            this(specs, renderer, false);
        }

        private Builder(Supplier<Specs> specs, Supplier<SqlDialect> renderer, boolean localReferences) {
            this.specs = specs;
            this.renderer = renderer;
            this.localReferences = localReferences;
        }

        @Override
        public Expect.Builder as(String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        @Override
        public Expect.Builder redaction(Redaction redaction) {
            if (localReferences) {
                throw new IllegalStateException("Configure redaction on the root expectation, outside query scopes.");
            }
            this.redaction = Objects.requireNonNull(redaction, "redaction");
            return this;
        }

        @Override
        public Expect.Builder outer(Consumer<Expect.Builder> declaration) {
            return scope(context -> QueryScope::outer, declaration);
        }

        @Override
        public Expect.Builder cte(String name, Consumer<Expect.Builder> declaration) {
            return namedScope(name, "CTE name", QueryScope::cte, declaration);
        }

        @Override
        public Expect.Builder derived(String alias, Consumer<Expect.Builder> declaration) {
            return namedScope(alias, "derived-table alias", QueryScope::derived, declaration);
        }

        @Override
        public Expect.Builder exists(Consumer<Expect.Builder> declaration) {
            return scope(context -> QueryScope::exists, declaration);
        }

        @Override
        public Expect.Builder exists(int index, Consumer<Expect.Builder> declaration) {
            nonNegative(index);
            return scope(context -> statement -> QueryScope.exists(statement, index), declaration);
        }

        @Override
        public Expect.Builder select() {
            return statementKind(StatementKind.SELECT);
        }

        @Override
        public Expect.Builder insert() {
            return statementKind(StatementKind.INSERT);
        }

        @Override
        public Expect.Builder update() {
            return statementKind(StatementKind.UPDATE);
        }

        @Override
        public Expect.Builder delete() {
            return statementKind(StatementKind.DELETE);
        }

        @Override
        public Expect.Builder merge() {
            return statementKind(StatementKind.MERGE);
        }

        @Override
        public Expect.Builder table(String... names) {
            var local = localReferences;
            return presence(names, "table", Table.class, false, (statement, expected) -> ModelInspector.containsTable(statement, expected, local));
        }

        @Override
        public Expect.Builder noTable(String... names) {
            var local = localReferences;
            return presence(names, "table", Table.class, true, (statement, expected) -> ModelInspector.containsTable(statement, expected, local));
        }

        @Override
        public Expect.Builder column(String... names) {
            var local = localReferences;
            return presence(
                names,
                "column",
                ColumnExpr.class,
                false,
                (statement, expected) -> ModelInspector.containsColumn(statement, expected, local)
            );
        }

        @Override
        public Expect.Builder noColumn(String... names) {
            var local = localReferences;
            return presence(
                names,
                "column",
                ColumnExpr.class,
                true,
                (statement, expected) -> ModelInspector.containsColumn(statement, expected, local)
            );
        }

        @Override
        public Expect.Builder join(String... joins) {
            var local = localReferences;
            return presence(joins, "join", Join.class, false, (statement, expected) -> ModelInspector.joins(statement, local).contains(expected));
        }

        @Override
        public Expect.Builder noJoin(String... joins) {
            var local = localReferences;
            return presence(joins, "join", Join.class, true, (statement, expected) -> ModelInspector.joins(statement, local).contains(expected));
        }

        @Override
        public Expect.Builder noJoin() {
            var local = localReferences;
            var message = local ? "Expected no joins at the selected query level." : "Expected no joins, including in nested queries.";
            return checked(statement ->
                ModelInspector.joins(statement, local).isEmpty()
                    ? List.of()
                    : List.of(new Mismatch("JOIN_PRESENT", "statement", message))
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
        public Expect.Builder groupBy() {
            return checked(statement ->
                result(ModelInspector.groupBy(statement) != null, "GROUP_BY_MISSING", "statement.groupBy", "Expected an outer GROUP BY clause.")
            );
        }

        @Override
        public Expect.Builder groupBy(String... items) {
            return orderedItems(items, "group_by", GroupItem.class, ModelInspector::groupItems);
        }

        @Override
        public Expect.Builder noGroupBy() {
            return absentClause("GROUP_BY", "statement.groupBy", ModelInspector::groupBy);
        }

        @Override
        public Expect.Builder having(String predicate) {
            return clause("HAVING", Predicate.class, Objects.requireNonNull(predicate, "predicate"), ModelInspector::having);
        }

        @Override
        public Expect.Builder noHaving() {
            return absentClause("HAVING", "statement.having", ModelInspector::having);
        }

        @Override
        public Expect.Builder distinct() {
            return clause("DISTINCT", DistinctSpec.class, "DISTINCT", ModelInspector::distinct);
        }

        @Override
        public Expect.Builder distinctOn(String... expressions) {
            var snapshot = fragments(expressions, "DISTINCT ON expression");
            diagnosticSources.addAll(snapshot);
            requirements.add(context -> {
                for (var expression : snapshot) {
                    parseExpected(context, Expression.class, expression, "DISTINCT ON expression");
                }
                var expected = parseExpected(context, DistinctSpec.class, "DISTINCT ON (" + String.join(", ", snapshot) + ")", "DISTINCT ON");
                return clauseCheck("DISTINCT_ON", expected, ModelInspector::distinct);
            });
            return this;
        }

        @Override
        public Expect.Builder noDistinct() {
            return absentClause("DISTINCT", "statement.distinct", ModelInspector::distinct);
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
            return checked(statement ->
                result(ModelInspector.orderBy(statement) == null, "ORDER_BY_PRESENT", "statement.orderBy", "Expected no outer ORDER BY.")
            );
        }

        @Override
        public Expect.Builder limit(long count) {
            nonNegative(count);
            return checked(statement ->
                result(
                    literalCount(ModelInspector.limit(statement), count),
                    "LIMIT_MISMATCH",
                    "statement.limit",
                    "Expected a literal outer row limit of " + count + "."
                )
            );
        }

        @Override
        public Expect.Builder noLimit() {
            return checked(statement ->
                result(!ModelInspector.hasLimit(statement), "LIMIT_PRESENT", "statement.limit", "Expected no outer limit specification.")
            );
        }

        @Override
        public Expect.Builder offset(long count) {
            nonNegative(count);
            return checked(statement ->
                result(
                    literalCount(ModelInspector.offset(statement), count),
                    "OFFSET_MISMATCH",
                    "statement.offset",
                    "Expected a literal outer offset of " + count + "."
                )
            );
        }

        @Override
        public Expect.Builder noOffset() {
            return checked(statement ->
                result(ModelInspector.offset(statement) == null, "OFFSET_PRESENT", "statement.offset", "Expected no outer offset specification.")
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
        public Expect.Builder binding(String name, Object value) {
            return binding(new BindingContext.Slot(BindingContext.Kind.NAMED, BindingValues.name(name)), value, false);
        }

        @Override
        public Expect.Builder positionalBinding(int position, Object value) {
            return binding(new BindingContext.Slot(BindingContext.Kind.POSITIONAL, BindingValues.position(position)), value, false);
        }

        @Override
        public Expect.Builder ordinalBinding(int index, Object value) {
            return binding(new BindingContext.Slot(BindingContext.Kind.ORDINAL, BindingValues.position(index)), value, false);
        }

        @Override
        public Expect.Builder noBinding(String name) {
            return binding(new BindingContext.Slot(BindingContext.Kind.NAMED, BindingValues.name(name)), null, true);
        }

        @Override
        public Expect.Builder noPositionalBinding(int position) {
            return binding(new BindingContext.Slot(BindingContext.Kind.POSITIONAL, BindingValues.position(position)), null, true);
        }

        @Override
        public Expect.Builder noOrdinalBinding(int index) {
            return binding(new BindingContext.Slot(BindingContext.Kind.ORDINAL, BindingValues.position(index)), null, true);
        }

        @Override
        public Expect.Builder matchAllParameters() {
            matchAllParameters = true;
            return this;
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
            return checked(statement ->
                ModelInspector.outerWhere(statement) == null
                    ? List.of()
                    : List.of(new Mismatch("WHERE_PRESENT", "statement.where", "Expected no outer WHERE predicate."))
            );
        }

        @Override
        public Expect.Builder noSelectStar() {
            var local = localReferences;
            return checked(statement -> SelectStars.find(statement, local));
        }

        @Override
        public Expect.Builder structurallyEquals(String expectedSql) {
            Objects.requireNonNull(expectedSql, "expectedSql");
            diagnosticSources.add(expectedSql);
            var dialect = renderer;
            requirements.add(context -> {
                var parsed = context.parse(Statement.class, expectedSql);
                if (!parsed.ok()) {
                    throw new InvalidExpectationException("Invalid expected SQL: " + parsed.errorMessage());
                }
                var expected = parsed.value();
                return (actual, bindings) -> expected.equals(actual) ? List.of() : List.of(
                    StructureDifference.between(expected, actual, dialect.get(), bindings.diagnostics().redaction())
                );
            });
            return this;
        }

        @Override
        public Expect.Builder satisfies(Check check) {
            Objects.requireNonNull(check, "check");
            requirements.add(context -> (statement, bindings) -> bindings.diagnostics().custom(check.evaluate(statement)));
            return this;
        }

        @Override
        public Expect build() {
            if (requirements.isEmpty() && !matchAllParameters) {
                throw new IllegalStateException("Declare at least one check before building an expectation.");
            }
            var snapshot = new ArrayList<>(requirements);
            if (matchAllParameters) {
                var expected = Set.copyOf(parameters);
                var local = localReferences;
                snapshot.add(context -> (statement, bindings) -> bindings.matchAll(statement, local, expected));
            }
            return new DefaultExpect(this, snapshot);
        }

        private Expect.Builder statementKind(StatementKind kind) {
            return checked(statement ->
                result(
                    kind.matches(statement),
                    "NOT_" + kind.name(),
                    "statement",
                    "Expected " + kind.name() + " statement but was " + StatementKind.of(statement).name() + "."
                )
            );
        }

        private <T extends Node> Expect.Builder clause(String kind, Class<T> type, String expectedSql, Function<Statement, T> inspect) {
            diagnosticSources.add(expectedSql);
            requirements.add(context -> clauseCheck(kind, parseExpected(context, type, expectedSql, kind), inspect));
            return this;
        }

        private ParameterizedCheck clauseCheck(String kind, Node expected, Function<Statement, ? extends Node> inspect) {
            var dialect = renderer;
            return (statement, bindings) -> {
                var actual = inspect.apply(statement);
                if (expected.equals(actual)) {
                    return List.of();
                }
                if (expected instanceof DistinctSpec) {
                    var policy = bindings.diagnostics().redaction();
                    var left = StructureDifference.sql(expected, dialect.get(), policy);
                    var right = actual == null ? "absent" : StructureDifference.sql(actual, dialect.get(), policy);
                    var suffix = policy == Redaction.ALL && left.equals(right) ? " Literal values differ (redacted)." : "";
                    return List.of(new Mismatch(kind + "_MISMATCH", "", "Expected SQL <" + left + "> but was <" + right + ">." + suffix));
                }
                var difference = StructureDifference.between(expected, actual, dialect.get(), bindings.diagnostics().redaction());
                return List.of(new Mismatch(kind + "_MISMATCH", difference.path(), difference.message(), difference.descriptions()));
            };
        }

        private Expect.Builder absentClause(String kind, String path, Function<Statement, ? extends Node> inspect) {
            var dialect = renderer;
            requirements.add(context -> (statement, bindings) -> {
                var actual = inspect.apply(statement);
                if (actual == null) {
                    return List.of();
                }
                var sql = StructureDifference.sql(actual, dialect.get(), bindings.diagnostics().redaction());
                return List.of(new Mismatch(kind + "_PRESENT", path, "Expected no outer " + kind.replace('_', ' ') + ". Actual SQL <" + sql + ">."));
            });
            return this;
        }

        private Expect.Builder namedScope(
            String name,
            String kind,
            BiFunction<Statement, Identifier, QueryScope> select,
            Consumer<Expect.Builder> declaration
        ) {
            Objects.requireNonNull(name, kind);
            if (name.isBlank()) {
                throw new IllegalArgumentException(kind + " must not be blank.");
            }
            diagnosticSources.add(name);
            return scope(context -> {
                var table = parseExpected(context, Table.class, name, kind);
                if (table.schema() != null || table.alias() != null) {
                    throw new InvalidExpectationException("Expected an unqualified identifier for " + kind + ": " + name);
                }
                return statement -> select.apply(statement, table.name());
            }, declaration);
        }

        private Expect.Builder scope(Function<ParseContext, Function<Statement, QueryScope>> prepare, Consumer<Expect.Builder> declaration) {
            Objects.requireNonNull(declaration, "declaration");
            var child = new Builder(specs, renderer, true);
            declaration.accept(child);
            var snapshot = (DefaultExpect) child.build();
            parameters.addAll(child.parameters);
            diagnosticSources.addAll(snapshot.diagnosticSources);
            expectedBindings.addAll(snapshot.expectedBindings);
            requirements.add(context -> {
                var select = prepare.apply(context);
                var checks = snapshot.requirements.stream()
                    .map(requirement -> requirement.apply(context))
                    .toList();
                return (statement, bindings) -> select.apply(statement).evaluate(checks, snapshot.description, bindings);
            });
            return this;
        }

        private static <T extends Node> T parseExpected(ParseContext context, Class<T> type, String sql, String kind) {
            var parsed = context.parse(type, sql);
            if (!parsed.ok()) {
                throw new InvalidExpectationException("Invalid expected " + kind + ": " + parsed.errorMessage());
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
                throw new InvalidExpectationException("Expected a table reference without hints or modifiers: " + sql);
            }
            return expected;
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
            var local = localReferences;
            return presence(
                names,
                "join_table",
                Table.class,
                forbidden,
                (statement, expected) -> ModelInspector.containsJoinTable(statement, expected, kind, local)
            );
        }

        private <T extends Node> Expect.Builder orderedItems(String[] items, String kind, Class<T> type, Function<Statement, List<T>> inspect) {
            var snapshot = fragments(items, kind);
            diagnosticSources.addAll(snapshot);
            var dialect = renderer;
            requirements.add(context -> {
                var expected = snapshot.stream()
                    .map(item -> parseExpected(context, type, item, kind))
                    .toList();
                return (statement, bindings) -> {
                    Check check = selected -> {
                        var actual = inspect.apply(selected);
                        if (expected.equals(actual)) {
                            return List.of();
                        }
                        var difference = StructureDifference.between(expected, actual, dialect.get(), bindings.diagnostics().redaction());
                        return List.of(new Mismatch(kind.toUpperCase(Locale.ROOT) + "_MISMATCH", difference.path(), difference.message()));
                    };
                    return check.evaluate(statement);
                };
            });
            return this;
        }

        private Expect.Builder namedParameters(String[] names, boolean forbidden) {
            var snapshot = fragments(names, "parameter");
            var local = localReferences;
            for (var name : snapshot) {
                if (name.isBlank() || name.startsWith(":") || name.startsWith("@") || name.startsWith("#{")) {
                    throw new IllegalArgumentException("Use a non-blank canonical parameter name without prefix: " + name);
                }
            }
            for (var name : snapshot) {
                if (!forbidden) {
                    parameters.add(new BindingContext.Slot(BindingContext.Kind.NAMED, name));
                }
                checked(statement -> {
                    boolean present = ModelInspector.parameters(statement, local)
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
            var local = localReferences;
            return checked(statement -> {
                var parameters = ModelInspector.parameters(statement, local);
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
            requirements.add(context -> {
                var check = requirement.apply(context);
                return (statement, bindings) -> check.evaluate(statement);
            });
            return this;
        }

        private Expect.Builder checked(Check check) {
            return append(context -> check);
        }

        private Expect.Builder binding(BindingContext.Slot slot, Object value, boolean absent) {
            var expected = BindingValues.copy(value);
            var local = localReferences;
            if (!absent) {
                parameters.add(slot);
                expectedBindings.add(expected);
            }
            requirements.add(context -> (statement, bindings) -> bindings.value(statement, local, slot, expected, absent));
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
            diagnosticSources.addAll(snapshot);
            var dialect = renderer;
            for (var fragment : snapshot) {
                requirements.add(context -> {
                    var expected = parseExpected(context, type, fragment, kind);
                    return (statement, bindings) -> {
                        boolean present = contains.test(statement, expected);
                        if (present != forbidden) {
                            return List.of();
                        }
                        var code = kind.toUpperCase(Locale.ROOT) + (forbidden ? "_PRESENT" : "_NOT_REFERENCED");
                        var actual = StructureDifference.sql(statement, dialect.get(), bindings.diagnostics().redaction());
                        var message = (forbidden ? "Unexpected " : "Expected ") + kind + " <" + fragment + ">. Actual SQL <" + actual + ">.";
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
        return delegate.mismatches().stream()
            .map(mismatch -> mismatch.withDescription(description))
            .toList();
    }

    @Override
    public String describe() {
        return "[" + description + "] " + delegate.describe();
    }
}
