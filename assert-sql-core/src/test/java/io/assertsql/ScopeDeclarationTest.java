package io.assertsql;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

class ScopeDeclarationTest {
    @Test
    void scopeCallbacksRunAtDeclarationAndProduceIndependentSnapshots() {
        var callbacks = new AtomicInteger();
        var checks = new AtomicInteger();
        var escaped = new AtomicReference<Expect.Builder>();
        var builder = Expect.sql(POSTGRESQL);
        assertSame(builder, builder.cte("inputs", query -> {
            callbacks.incrementAndGet();
            escaped.set(query);
            query.table("users").satisfies(statement -> {
                checks.incrementAndGet();
                return List.of();
            });
        }));
        var first = builder.build();
        escaped.get().table("missing").as("later description");
        var second = builder.table("orders").build();
        assertEquals(1, callbacks.get());
        assertEquals(0, checks.get());
        var sql = "WITH inputs AS (SELECT id FROM users) SELECT id FROM inputs";
        first.assertMatches(sql);
        first.assertMatches(sql);
        assertFalse(second.matches(sql));
        assertEquals(3, checks.get());
        assertEquals(1, callbacks.get());
    }

    @Test
    void validatesBlockContainersAndRejectsEmptyDeclarationsWithoutChangingTheParent() {
        var builder = Expect.sql(POSTGRESQL);
        assertThrows(NullPointerException.class, () -> builder.outer(null));
        assertThrows(NullPointerException.class, () -> builder.cte(null, Expect.Builder::select));
        assertThrows(NullPointerException.class, () -> builder.cte("inputs", null));
        assertThrows(IllegalArgumentException.class, () -> builder.cte(" ", Expect.Builder::select));
        assertThrows(IllegalStateException.class, () -> builder.outer(query -> {}));
        assertThrows(IllegalStateException.class, () -> builder.cte("inputs", query -> query.as("description only")));
        assertThrows(IllegalStateException.class, builder::build);
        assertThrows(IllegalArgumentException.class, () -> builder.outer(query -> {
            query.select();
            throw new IllegalArgumentException("Callback failed.");
        }));
        assertThrows(IllegalStateException.class, builder::build);
        assertSame(builder, builder.outer(Expect.Builder::select));
        builder.build().assertMatches("SELECT 1");
    }

    @Test
    void defersSelectorAndExpectedFragmentParsingAndPreparesEveryBlockBeforeCustomChecks() {
        var calls = new AtomicInteger();
        var invalidName = Expect.sql(POSTGRESQL).cte("schema.inputs", query -> query.noTable("users")).build();
        assertEquals("PARSE_ERROR", invalidName.evaluate("invalid SQL").mismatches().getFirst().code());
        assertThrows(IllegalArgumentException.class, () -> invalidName.evaluate("SELECT 1"));
        var invalidAlias = Expect.sql(POSTGRESQL).cte("inputs s", Expect.Builder::select).build();
        assertThrows(IllegalArgumentException.class, () -> invalidAlias.evaluate("SELECT 1"));
        var invalidFragment = Expect.sql(POSTGRESQL)
            .satisfies(statement -> {
                calls.incrementAndGet();
                return List.of();
            })
            .outer(query -> query.table("SELECT FROM"))
            .build();
        assertThrows(IllegalArgumentException.class, () -> invalidFragment.evaluate("SELECT 1"));
        assertEquals(0, calls.get());
    }

    @Test
    void invalidActualSqlSkipsScopeSelectionAndCustomChecks() {
        var calls = new AtomicInteger();
        var expectation = Expect.sql(POSTGRESQL)
            .cte("missing", query -> query.noTable("users"))
            .outer(query -> query.satisfies(statement -> {
                calls.incrementAndGet();
                return List.of();
            }))
            .build();
        var result = expectation.evaluate("invalid SQL");
        assertTrue(result.mismatches().stream().allMatch(mismatch -> mismatch.code().equals("PARSE_ERROR")));
        assertEquals(0, calls.get());
        assertFalse(result.matches());
    }

    @Test
    void missingScopesDoNotInvokeChecksAndUnexpectedCustomExceptionsStillPropagate() {
        var calls = new AtomicInteger();
        var missing = Expect.sql(POSTGRESQL).cte("missing", query -> query.satisfies(statement -> {
            calls.incrementAndGet();
            return List.of();
        }).as("required CTE")).build();
        var missingResult = missing.evaluate("SELECT 1");
        assertEquals("SCOPE_NOT_FOUND", missingResult.mismatches().getFirst().code());
        assertTrue(missingResult.describe().contains("[required CTE]"));
        assertEquals(0, calls.get());
        var expected = new IllegalStateException("Custom check failed.");
        var throwing = Expect.sql(POSTGRESQL).outer(query -> query.satisfies(statement -> {
            throw expected;
        })).build();
        assertSame(expected, assertThrows(IllegalStateException.class, () -> throwing.evaluate("SELECT 1")));
    }

    @Test
    void scopedExpectationsComposeWithOrAndKeepReadableBranchLocations() {
        var cte = Expect.sql(POSTGRESQL).cte("inputs", query -> query.table("users")).build();
        var outer = Expect.sql(POSTGRESQL).outer(query -> query.table("orders")).build();
        Expect.or(cte, outer).assertMatches("SELECT id FROM orders");
        var result = Expect.or(cte, outer).evaluate("SELECT id FROM audit");
        assertFalse(result.matches());
        assertEquals("SCOPE_NOT_FOUND", result.mismatches().get(1).code());
        assertTrue(result.mismatches().get(1).path().contains("or[0].CTE"));
        assertTrue(result.mismatches().get(2).path().contains("or[1].outer query"));
    }

    @Test
    void repeatedAndConcurrentEvaluationsDoNotRetainScopeSelections() throws Exception {
        var expectation = Expect.sql(POSTGRESQL)
            .cte("inputs", query -> query.table("users").noTable("audit"))
            .outer(query -> query.table("inputs").noTable("users"))
            .build();
        var matching = "WITH inputs AS (SELECT id FROM users) SELECT id FROM inputs";
        var different = "WITH inputs AS (SELECT id FROM audit) SELECT id FROM inputs";
        try (var executor = Executors.newFixedThreadPool(4)) {
            var results = IntStream.range(0, 40).mapToObj(i -> executor.submit(() -> {
                expectation.assertMatches(matching);
                assertFalse(expectation.matches(different));
                assertFalse(expectation.matches("SELECT 1"));
                return true;
            })).toList();
            for (var result : results) {
                assertTrue(result.get(10, TimeUnit.SECONDS));
            }
        }
    }
}
