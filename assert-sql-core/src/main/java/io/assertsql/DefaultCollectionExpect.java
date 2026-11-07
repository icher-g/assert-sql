package io.assertsql;

import io.sqm.core.*;
import io.sqm.core.dialect.SqlDialectId;
import io.sqm.parser.spi.ParseContext;
import io.sqm.parser.spi.Specs;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class DefaultCollectionExpect implements CollectionExpect {
    private final Supplier<Specs> specs;
    private final String description;
    private final List<Rule> rules;
    private final Redaction redaction;

    private DefaultCollectionExpect(Supplier<Specs> specs, String description, List<Rule> rules, Redaction redaction) {
        this.specs = specs;
        this.description = description;
        this.rules = List.copyOf(rules);
        this.redaction = redaction;
    }

    static CollectionExpect.Builder builder(SqlDialectId dialect) {
        return new Builder(dialect, SqlDialects.of(dialect).parser());
    }

    @Override
    public Match evaluate(List<String> statements) {
        var snapshot = List.copyOf(Objects.requireNonNull(statements, "statements"));
        var context = ParseContext.of(specs.get());
        var policy = redaction == null ? Redaction.BINDINGS : redaction;
        var diagnostics = new DiagnosticContext(policy, context.identifierQuoting(), snapshot, List.of(), ParameterizedSql.of(""));
        var entries = new ArrayList<Entry>();
        var mismatches = new ArrayList<Mismatch>();
        for (int index = 0; index < snapshot.size(); index++) {
            var sql = snapshot.get(index);
            var parsed = context.parse(Statement.class, sql);
            if (parsed.ok()) {
                entries.add(new Entry(sql, parsed.value()));
            }
            else {
                for (var problem : parsed.problems()) {
                    mismatches.add(new Mismatch("PARSE_ERROR", "statements[" + index + "].sql[" + problem.pos() + "]", problem.message()));
                }
            }
        }
        if (!mismatches.isEmpty()) {
            return result(mismatches, diagnostics);
        }
        // Prepare every filter before evaluating any custom check.
        var prepared = rules.stream()
            .map(rule -> new PreparedRule(rule, rule.filter() == null ? null : rule.filter().prepare(context, redaction)))
            .toList();
        for (var rule : prepared) {
            mismatches.addAll(evaluateRule(rule, entries, redaction));
        }
        return result(mismatches, diagnostics);
    }

    private static List<Mismatch> evaluateRule(PreparedRule prepared, List<Entry> entries, Redaction redaction) {
        var rule = prepared.rule();
        if (rule.mode() == Mode.TOTAL) {
            if (entries.size() == rule.count()) {
                return List.of();
            }
            var message = "Expected " + rule.count() + " statements but found " + entries.size() + ".";
            return List.of(new Mismatch("STATEMENT_COUNT_MISMATCH", "collection.count", message));
        }
        var matched = new ArrayList<Integer>();
        var failures = new ArrayList<Mismatch>();
        var evaluationErrors = new ArrayList<Mismatch>();
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.get(index);
            if (rule.kind() != null && !rule.kind().matches(entry.statement())) {
                continue;
            }
            var match = prepared.filter() != null
                ? prepared.filter().apply(entry.statement(), ParameterizedSql.of(entry.sql()))
                : rule.expectation() != null
                    ? DiagnosticContext.evaluate(rule.expectation(), ParameterizedSql.of(entry.sql()), redaction)
                    : Match.of(List.of());
            if (match.matches()) {
                matched.add(index);
            }
            else {
                for (var mismatch : match.mismatches()) {
                    var indexed = atIndex(index, mismatch);
                    failures.add(indexed);
                    if (evaluationError(mismatch)) {
                        evaluationErrors.add(indexed);
                    }
                }
            }
        }
        if (!evaluationErrors.isEmpty()) {
            return List.copyOf(evaluationErrors);
        }
        boolean success = switch (rule.mode()) {
            case EXACT -> matched.size() == rule.count();
            case ANY -> !matched.isEmpty();
            case ALL -> matched.size() == entries.size();
            case NONE -> matched.isEmpty();
            case TOTAL -> throw new IllegalStateException("Total count was already evaluated.");
        };
        if (success) {
            return List.of();
        }
        var mismatches = new ArrayList<Mismatch>();
        var expected = switch (rule.mode()) {
            case EXACT -> Integer.toString(rule.count());
            case ANY -> "at least one";
            case ALL -> "all " + entries.size();
            case NONE -> "no";
            case TOTAL -> throw new IllegalStateException("Total count was already evaluated.");
        };
        var kind = rule.kind() == null ? "" : rule.kind().name() + " ";
        var message = "Expected " + expected + " matching " + kind + "statements but found " + matched.size() + " at indices " + matched + ".";
        var code = rule.kind() == null ? "MATCH_COUNT_MISMATCH" : rule.kind().name() + "_COUNT_MISMATCH";
        if (rule.mode() != Mode.EXACT) {
            code = rule.mode().name() + "_MATCH_MISMATCH";
        }
        mismatches.add(new Mismatch(code, "collection." + rule.label(), message));
        if (rule.mode() == Mode.ALL || rule.mode() == Mode.ANY || (rule.mode() == Mode.EXACT && matched.size() < rule.count())) {
            mismatches.addAll(failures);
        }
        return List.copyOf(mismatches);
    }

    private static boolean evaluationError(Mismatch mismatch) {
        return switch (mismatch.code()) {
            case "PARSE_ERROR", "BINDINGS_UNAVAILABLE", "SQM_SCOPE_UNSUPPORTED", "VALIDATION_UNSUPPORTED" -> true;
            default -> false;
        };
    }

    private static Mismatch atIndex(int index, Mismatch mismatch) {
        var suffix = mismatch.path().isEmpty() ? "" : "." + mismatch.path();
        return new Mismatch(mismatch.code(), "statements[" + index + "]" + suffix, mismatch.message(), mismatch.descriptions());
    }

    private Match result(List<Mismatch> mismatches, DiagnosticContext diagnostics) {
        var safe = mismatches.stream()
            .map(diagnostics::sanitize)
            .toList();
        var match = Match.of(safe);
        return description == null ? match : new DescribedMatch(diagnostics.description(description), match);
    }

    private enum Mode {
        TOTAL, EXACT, ANY, ALL, NONE
    }

    private record Entry(String sql, Statement statement) {
    }

    private record Rule(String label, Mode mode, int count, StatementKind kind, DefaultExpect filter, Expect expectation) {
    }

    private record PreparedRule(Rule rule, BiFunction<Statement, ParameterizedSql, Match> filter) {
    }

    private static final class Builder implements CollectionExpect.Builder {
        private final SqlDialectId dialect;
        private final Supplier<Specs> specs;
        private final List<Rule> rules = new ArrayList<>();
        private String description;
        private Redaction redaction;

        private Builder(SqlDialectId dialect, Supplier<Specs> specs) {
            this.dialect = dialect;
            this.specs = specs;
        }

        @Override
        public CollectionExpect.Builder as(String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        @Override
        public CollectionExpect.Builder redaction(Redaction redaction) {
            this.redaction = Objects.requireNonNull(redaction, "redaction");
            return this;
        }

        @Override
        public CollectionExpect.Builder count(int count) {
            return add("count", Mode.TOTAL, count, null, null, null);
        }

        @Override
        public CollectionExpect.Builder selectCount(int count) {
            return kindCount(count, StatementKind.SELECT);
        }

        @Override
        public CollectionExpect.Builder selectCount(int count, Consumer<Expect.Builder> declaration) {
            return kindCount(count, StatementKind.SELECT, declaration);
        }

        @Override
        public CollectionExpect.Builder insertCount(int count) {
            return kindCount(count, StatementKind.INSERT);
        }

        @Override
        public CollectionExpect.Builder insertCount(int count, Consumer<Expect.Builder> declaration) {
            return kindCount(count, StatementKind.INSERT, declaration);
        }

        @Override
        public CollectionExpect.Builder updateCount(int count) {
            return kindCount(count, StatementKind.UPDATE);
        }

        @Override
        public CollectionExpect.Builder updateCount(int count, Consumer<Expect.Builder> declaration) {
            return kindCount(count, StatementKind.UPDATE, declaration);
        }

        @Override
        public CollectionExpect.Builder deleteCount(int count) {
            return kindCount(count, StatementKind.DELETE);
        }

        @Override
        public CollectionExpect.Builder deleteCount(int count, Consumer<Expect.Builder> declaration) {
            return kindCount(count, StatementKind.DELETE, declaration);
        }

        @Override
        public CollectionExpect.Builder mergeCount(int count) {
            return kindCount(count, StatementKind.MERGE);
        }

        @Override
        public CollectionExpect.Builder mergeCount(int count, Consumer<Expect.Builder> declaration) {
            return kindCount(count, StatementKind.MERGE, declaration);
        }

        @Override
        public CollectionExpect.Builder matchingCount(Expect expectation, int count) {
            return add("matchingCount", Mode.EXACT, count, null, null, Objects.requireNonNull(expectation, "expectation"));
        }

        @Override
        public CollectionExpect.Builder any(Expect expectation) {
            return add("any", Mode.ANY, 0, null, null, Objects.requireNonNull(expectation, "expectation"));
        }

        @Override
        public CollectionExpect.Builder all(Expect expectation) {
            return add("all", Mode.ALL, 0, null, null, Objects.requireNonNull(expectation, "expectation"));
        }

        @Override
        public CollectionExpect.Builder none(Expect expectation) {
            return add("none", Mode.NONE, 0, null, null, Objects.requireNonNull(expectation, "expectation"));
        }

        @Override
        public CollectionExpect build() {
            if (rules.isEmpty()) {
                throw new IllegalStateException("Declare at least one collection check before building.");
            }
            return new DefaultCollectionExpect(specs, description, rules, redaction);
        }

        private static String label(StatementKind kind) {
            return kind.name().toLowerCase(java.util.Locale.ROOT) + "Count";
        }

        private static void nonNegative(int count) {
            if (count < 0) {
                throw new IllegalArgumentException("Statement counts must be non-negative.");
            }
        }

        private CollectionExpect.Builder kindCount(int count, StatementKind kind) {
            return add(label(kind), Mode.EXACT, count, kind, null, null);
        }

        private CollectionExpect.Builder kindCount(int count, StatementKind kind, Consumer<Expect.Builder> declaration) {
            nonNegative(count);
            Objects.requireNonNull(declaration, "declaration");
            var builder = Expect.sql(dialect);
            declaration.accept(builder);
            return add(label(kind), Mode.EXACT, count, kind, (DefaultExpect) builder.build(), null);
        }

        private CollectionExpect.Builder add(String label, Mode mode, int count, StatementKind kind, DefaultExpect filter, Expect expectation) {
            nonNegative(count);
            rules.add(new Rule(label, mode, count, kind, filter, expectation));
            return this;
        }
    }
}
