package io.assertsql;

import io.sqm.core.Statement;
import io.sqm.core.dialect.SqlDialectId;
import io.sqm.parser.postgresql.spi.PostgresSpecs;
import io.sqm.parser.spi.ParseContext;
import io.sqm.render.ansi.spi.AnsiDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticRedactionTest {
    @Test
    void defaultRetainsSqlLiteralsAndHidesBindingValues() {
        var sql = Expect.sql(POSTGRESQL)
            .structurallyEquals("SELECT 'expected-secret'")
            .build()
            .evaluate("SELECT 'actual-secret'");
        assertTrue(sql.describe().contains("expected-secret"));
        assertTrue(sql.describe().contains("actual-secret"));
        var bindings = Expect.sql(POSTGRESQL)
            .binding("name", "expected-secret")
            .build()
            .evaluate(ParameterizedSql.named("SELECT :name", Map.of("name", "actual-secret")));
        hidden(bindings, "expected-secret", "actual-secret");
        assertEquals("BINDING_MISMATCH", bindings.mismatches().getFirst().code());
        assertEquals("bindings.named[name]", bindings.mismatches().getFirst().path());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "'literal-secret'", "'O''Brien  literal-secret'", "E'escape\\nliteral-secret'", "$$dollar-literal-secret$$",
        "918273", "true", "false", "NULL", "DATE '2026-11-07'", "TIMESTAMP '2026-11-07 12:34:56'", "B'101010'", "X'ABCD'"
    })
    void fullRedactionUsesSqmLiteralMetadataForQuotedEscapedTypedAndNullValues(String literal) {
        var result = Expect.sql(POSTGRESQL)
            .redaction(Redaction.ALL)
            .structurallyEquals("SELECT " + literal)
            .build()
            .evaluate("SELECT 'actual-secret'");
        assertFalse(result.matches());
        assertTrue(result.describe().contains("Expected SQL"), result::describe);
        assertTrue(result.describe().contains("?"), result::describe);
        hidden(result, "literal-secret", "actual-secret", "918273", "2026-11-07", "101010", "ABCD");
        assertFalse(result.describe().contains("Impl["));
        assertEquals("", result.mismatches().getFirst().path());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ansi", "postgresql", "mysql", "sqlserver", "oracle"})
    void fullRedactionWorksWithEveryConfiguredDialect(String dialect) {
        var result = Expect.sql(SqlDialectId.of(dialect))
            .redaction(Redaction.ALL)
            .structurallyEquals("SELECT 'expected-secret'")
            .build()
            .evaluate("SELECT 'actual-secret'");
        hidden(result, "expected-secret", "actual-secret");
        assertTrue(result.describe().contains("Expected SQL"), result::describe);
    }

    @Test
    void rendererFallbackCannotExposeScalarValuesUnderFullRedaction() {
        var parser = ParseContext.of(new PostgresSpecs());
        var expected = parser.parse(Statement.class, "SELECT id FROM users WHERE name ILIKE 'expected-secret'").value();
        var actual = parser.parse(Statement.class, "SELECT id FROM users WHERE name ILIKE 'actual-secret'").value();
        var difference = StructureDifference.between(expected, actual, new AnsiDialect(), Redaction.ALL);
        var result = Match.of(List.of(difference));
        hidden(result, "expected-secret", "actual-secret");
        assertTrue(difference.path().contains("where"));
        assertTrue(difference.message().contains("could not be rendered safely"));
    }

    @Test
    void redactedLiteralFailuresKeepReadableSqlClauseDetail() {
        var result = Expect.sql(POSTGRESQL)
            .as("user lookup")
            .redaction(Redaction.ALL)
            .structurallyEquals("SELECT id FROM users WHERE email = 'expected@example.test'")
            .build()
            .evaluate("SELECT id FROM users WHERE email = 'actual@example.test'");
        assertEquals("""
            [user lookup] SQL did not match expectations:
            - [STRUCTURE_MISMATCH] Expected SQL <email = ?> but was <email = ?>. Literal values differ (redacted).""", result.describe());
    }

    @Test
    void presenceAndClauseFailuresShowRelevantActualSqlWithoutLeakingLiterals() {
        var result = Expect.sql(POSTGRESQL)
            .redaction(Redaction.ALL)
            .table("expected_users")
            .where("name = 'expected-secret'")
            .exactProjection("'projection-secret'")
            .build()
            .evaluate("SELECT id FROM actual_users WHERE name = 'actual-secret'");
        hidden(result, "expected-secret", "actual-secret", "projection-secret");
        assertTrue(result.describe().contains("actual_users"), result::describe);
        assertTrue(result.describe().contains("Actual SQL"), result::describe);
        assertEquals(3, result.mismatches().size());
    }

    @Test
    void literalRedactionPreservesIdentifiersEvenWhenTheirSpellingMatchesALiteralValue() {
        var result = Expect.sql(POSTGRESQL)
            .redaction(Redaction.ALL)
            .table("wanted")
            .build()
            .evaluate("SELECT 'users' FROM users");
        assertFalse(result.describe().contains("'users'"));
        assertTrue(result.describe().contains("FROM users"), result::describe);
    }

    @Test
    void orPreservesBranchDescriptionsPathsAndNestedScopeLabels() {
        var left = Expect.sql(POSTGRESQL)
            .as("users branch")
            .table("users")
            .build();
        var right = Expect.sql(POSTGRESQL)
            .as("archive branch")
            .cte("page", query -> query
                .as("page projection")
                .exactProjection("id")
            )
            .build();
        var result = Expect.or(left, Expect.or(right))
            .evaluate("WITH page AS (SELECT name FROM audit) SELECT name FROM page");
        assertEquals("or[0].statement", result.mismatches().get(1).path());
        assertEquals(List.of("users branch"), result.mismatches().get(1).descriptions());
        var nested = result.mismatches().getLast();
        assertEquals("or[1].or[0].CTE \"page\" (WITH item 1)", nested.path());
        assertEquals(List.of("archive branch", "page projection"), nested.descriptions());
        assertTrue(result.describe().contains("[users branch]"));
        assertTrue(result.describe().contains("[archive branch] [page projection]"));
    }

    @Test
    void explicitOrPolicyOverridesBranchesAndStillShortCircuits() {
        var unsafe = Expect.sql(POSTGRESQL)
            .redaction(Redaction.NONE)
            .as("branch expected-secret")
            .binding("name", "expected-secret")
            .build();
        var input = ParameterizedSql.named("SELECT :name", Map.of("name", "actual-secret"));
        var redacted = Expect.or(Redaction.ALL, Expect.or(unsafe))
            .evaluate(input);
        hidden(redacted, "expected-secret", "actual-secret");
        var calls = new AtomicInteger();
        var first = Expect.sql(POSTGRESQL)
            .select()
            .build();
        var never = Expect.sql(POSTGRESQL)
            .satisfies(statement -> {
                calls.incrementAndGet();
                throw new AssertionError("not evaluated");
            })
            .build();
        Expect.or(Redaction.ALL, first, never)
            .assertMatches("SELECT 'actual-secret'");
        assertEquals(0, calls.get());
    }

    @Test
    void scopesKeepMachinePathsIndependentOfDescriptionsAndPolicy() {
        var plain = scoped(Redaction.BINDINGS);
        var safe = scoped(Redaction.ALL);
        var sql = "WITH page AS (SELECT 'actual-secret' AS id) SELECT id FROM page";
        var rawResult = plain.evaluate(sql);
        var safeResult = safe.evaluate(sql);
        assertEquals(rawResult.mismatches().getFirst().code(), safeResult.mismatches().getFirst().code());
        assertEquals(rawResult.mismatches().getFirst().path(), safeResult.mismatches().getFirst().path());
        assertEquals(List.of("report contract", "page contract"), safeResult.mismatches().getFirst().descriptions());
        hidden(safeResult, "expected-secret", "actual-secret");
        assertTrue(safeResult.describe().contains("CTE \"page\" (WITH item 1)"));
    }

    @Test
    void parseAndConfigurationErrorsOmitUnsafeTextAndRawCauses() {
        var malformed = Expect.sql(POSTGRESQL)
            .redaction(Redaction.ALL)
            .as("label parser-secret")
            .noTable("users")
            .build()
            .evaluate("SELECT 'parser-secret");
        hidden(malformed, "parser-secret");
        assertEquals("PARSE_ERROR", malformed.mismatches().getFirst().code());
        assertTrue(malformed.mismatches().getFirst().path().startsWith("sql["));
        var invalid = Expect.sql(POSTGRESQL)
            .redaction(Redaction.ALL)
            .where("name = 'configuration-secret' AND")
            .build();
        var failure = assertThrows(IllegalArgumentException.class, () -> invalid.evaluate("SELECT 1"));
        assertFalse(failure.getMessage().contains("configuration-secret"));
        assertNull(failure.getCause());
        assertEquals("PARSE_ERROR", invalid.evaluate("SELECT FROM").mismatches().getFirst().code());
    }

    @Test
    void customFreeFormDetailsAreOmittedWhileCodesAndPathsRemainUsable() {
        var input = ParameterizedSql.named("SELECT :name", Map.of("name", "actual-secret"));
        var expectation = Expect.sql(POSTGRESQL)
            .as("binding prefixactual-secretsuffix")
            .satisfies(statement -> List.of(new Mismatch("CUSTOM_FAILURE", "custom.location", "raw actual-secret and decoded literal-secret")))
            .build();
        var result = expectation.evaluate(input);
        hidden(result, "actual-secret", "literal-secret");
        assertEquals("CUSTOM_FAILURE", result.mismatches().getFirst().code());
        assertEquals("custom.location", result.mismatches().getFirst().path());
        var full = Expect.sql(POSTGRESQL)
            .redaction(Redaction.ALL)
            .satisfies(statement -> List.of(new Mismatch("CUSTOM_FAILURE", "custom", "literal-secret")))
            .build()
            .evaluate("SELECT 'literal-secret'");
        hidden(full, "literal-secret");
    }

    @Test
    void namedPositionalOrdinalArraysAndNullValuesStayHiddenByDefaultAndCanBeExplicitlyShown() {
        var input = ParameterizedSql.of(
            "SELECT :name, ?, $2",
            Map.of("name", "actual-secret"),
            List.of((Object) new String[]{"array-secret"}),
            Map.of(2, 918273L)
        );
        var builder = Expect.sql(POSTGRESQL)
            .binding("name", "expected-secret")
            .positionalBinding(1, new String[]{"expected-array"})
            .ordinalBinding(2, null);
        var hidden = builder.build();
        hidden(hidden.evaluate(input), "expected-secret", "actual-secret", "expected-array", "array-secret", "918273");
        var visible = builder.redaction(Redaction.NONE)
            .build()
            .evaluate(input);
        assertTrue(visible.describe().contains("expected-secret"));
        assertTrue(visible.describe().contains("actual-secret"));
        assertTrue(visible.describe().contains("array-secret"));
        assertTrue(visible.describe().contains("<null>"));
        assertTrue(visible.describe().contains("918273"));
        var nullInput = ParameterizedSql.named("SELECT :name", Collections.singletonMap("name", null));
        hidden(hidden.evaluate(nullInput), "expected-secret", "actual-secret");
    }

    @Test
    void longFragmentsAreRedactedBeforeTruncationAndDetailedMessagesAreBounded() {
        var secret = "long-secret".repeat(600);
        var full = Expect.sql(POSTGRESQL)
            .redaction(Redaction.ALL)
            .structurallyEquals("SELECT '" + secret + "'")
            .build()
            .evaluate("SELECT 'actual-secret'");
        hidden(full, "long-secret", "actual-secret");
        assertTrue(full.describe().length() < 1000);
        var detailed = Expect.sql(POSTGRESQL)
            .redaction(Redaction.NONE)
            .satisfies(statement -> List.of(new Mismatch("CUSTOM", "custom", secret)))
            .build()
            .evaluate("SELECT 1");
        assertTrue(detailed.mismatches().getFirst().message().length() <= 2020);
        assertTrue(detailed.describe().contains("truncated"));
        var fragments = Expect.sql(POSTGRESQL)
            .structurallyEquals("SELECT '" + secret + "'")
            .build()
            .evaluate("SELECT 'actual-secret'");
        assertTrue(fragments.describe().contains("actual-secret"));
        assertTrue(fragments.describe().contains("truncated"));
    }

    @Test
    void collectionsPropagatePrivacyAndDescriptionsThroughFiltersAndOr() {
        var existing = Expect.sql(POSTGRESQL)
            .as("existing branch")
            .redaction(Redaction.NONE)
            .structurallyEquals("SELECT 'expected-secret'")
            .build();
        var result = Expect.statements(POSTGRESQL)
            .as("capture contract")
            .redaction(Redaction.ALL)
            .selectCount(1, query -> query
                .redaction(Redaction.NONE)
                .as("projection filter")
                .exactProjection("'projection-secret'")
            )
            .all(Expect.or(existing))
            .build()
            .evaluate(List.of("SELECT 'actual-secret'"));
        hidden(result, "expected-secret", "projection-secret", "actual-secret");
        assertTrue(result.describe().contains("statements[0]"));
        assertTrue(result.describe().contains("projection filter"));
        assertTrue(result.describe().contains("existing branch"));
        var malformed = Expect.statements(POSTGRESQL)
            .redaction(Redaction.ALL)
            .updateCount(0)
            .build()
            .evaluate(List.of("SELECT 'parser-secret"));
        hidden(malformed, "parser-secret");
    }

    @Test
    void mismatchContextsAreImmutableAndExternalExceptionsStillPropagateUnchanged() {
        var labels = new ArrayList<>(List.of("contract"));
        var mismatch = new Mismatch("FAILURE", "location", "detail", labels);
        labels.clear();
        assertEquals(List.of("contract"), mismatch.descriptions());
        assertThrows(UnsupportedOperationException.class, () -> mismatch.descriptions().clear());
        var failure = new IllegalStateException("application-owned exception");
        var expectation = Expect.sql(POSTGRESQL)
            .redaction(Redaction.ALL)
            .satisfies(statement -> {
                throw failure;
            })
            .build();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> expectation.evaluate("SELECT 1")));
    }

    @Test
    void policiesAreSnapshottedAndRejectMissingOrScopedConfiguration() {
        var builder = Expect.sql(POSTGRESQL)
            .structurallyEquals("SELECT 'expected-secret'");
        var visible = builder.build();
        var safe = builder.redaction(Redaction.ALL)
            .build();
        assertTrue(visible.evaluate("SELECT 'actual-secret'").describe().contains("actual-secret"));
        hidden(safe.evaluate("SELECT 'actual-secret'"), "expected-secret", "actual-secret");
        assertThrows(NullPointerException.class, () -> builder.redaction(null));
        assertThrows(
            NullPointerException.class,
            () -> Expect.statements(POSTGRESQL)
                .redaction(null)
        );
        assertThrows(NullPointerException.class, () -> Expect.or((Redaction) null, visible));
        assertThrows(IllegalStateException.class, () -> builder.outer(query -> query.redaction(Redaction.ALL)));
    }

    @Test
    void opaqueBindingObjectsAreNeverStringifiedByRedactedDiagnostics() {
        var calls = new AtomicInteger();
        var opaque = new Object() {
            @Override
            public String toString() {
                calls.incrementAndGet();
                return "opaque-secret";
            }
        };
        var result = Expect.sql(POSTGRESQL)
            .redaction(Redaction.ALL)
            .as("opaque-secret")
            .binding("value", "expected-secret")
            .build()
            .evaluate(ParameterizedSql.named("SELECT :value", Map.of("value", opaque)));
        hidden(result, "opaque-secret", "expected-secret");
        assertEquals(0, calls.get());
    }

    @Test
    void concurrentEvaluationsDoNotShareSecretsOrDisclosureState() throws Exception {
        var expectation = Expect.sql(POSTGRESQL)
            .redaction(Redaction.ALL)
            .binding("name", "expected-secret")
            .build();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = IntStream.range(0, 20)
                .mapToObj(index -> executor.submit(() -> {
                    var actual = "private-value-" + index;
                    var result = expectation.evaluate(ParameterizedSql.named("SELECT :name", Map.of("name", actual)));
                    hidden(result, "expected-secret", actual);
                    return true;
                }))
                .toList();
            for (var future : futures) {
                assertTrue(future.get(10, TimeUnit.SECONDS));
            }
        }
    }

    private static Expect scoped(Redaction redaction) {
        return Expect.sql(POSTGRESQL)
            .redaction(redaction)
            .as("report contract")
            .cte("page", query -> query
                .as("page contract")
                .structurallyEquals("SELECT 'expected-secret' AS id")
            )
            .build();
    }

    private static void hidden(Match result, String... secrets) {
        assertFalse(result.matches());
        var error = assertThrows(AssertionError.class, result::assertMatches);
        for (var secret : secrets) {
            assertFalse(result.describe().contains(secret), result::describe);
            assertFalse(error.getMessage().contains(secret), error::getMessage);
            for (var mismatch : result.mismatches()) {
                assertFalse(mismatch.message().contains(secret), mismatch::message);
                assertFalse(mismatch.descriptions().toString().contains(secret));
            }
        }
    }
}
