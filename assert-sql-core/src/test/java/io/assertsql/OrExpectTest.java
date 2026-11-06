package io.assertsql;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.sqm.core.dialect.SqlDialectId.*;
import static org.junit.jupiter.api.Assertions.*;

class OrExpectTest {
    @Test
    void succeedsWhenAnyAlternativeMatchesAndKeepsAndWithinEachBranch() {
        var expectation = Expect.or(
            Expect.sql(POSTGRESQL).table("users").column("id").build(),
            Expect.sql(POSTGRESQL).table("orders").column("total").build()
        );
        expectation.assertMatches("SELECT id FROM users");
        expectation.assertMatches("SELECT total FROM orders");
        assertFalse(expectation.matches("SELECT id FROM orders"));
        assertFalse(expectation.matches("SELECT total FROM users"));
    }

    @Test
    void reportsAllFailedBranchesWithPaths() {
        var result = Expect.or(Expect.sql(POSTGRESQL).table("users").build(), Expect.sql(POSTGRESQL).column("id").build())
            .evaluate("SELECT name FROM orders");
        assertFalse(result.matches());
        assertEquals("OR_NOT_MATCHED", result.mismatches().getFirst().code());
        assertEquals("or[0].statement", result.mismatches().get(1).path());
        assertEquals("or[1].statement", result.mismatches().get(2).path());
        var error = assertThrows(AssertionError.class, result::assertMatches);
        assertTrue(error.getMessage().contains("users"));
        assertTrue(error.getMessage().contains("id"));
    }

    @Test
    void shortCircuitsAfterTheFirstSuccess() {
        var calls = new AtomicInteger();
        var expectation = Expect.or(Expect.sql(POSTGRESQL).select().build(),
            Expect.sql(POSTGRESQL).satisfies(statement -> {
                calls.incrementAndGet();
                return List.of();
            }).build());
        expectation.assertMatches("SELECT 1");
        assertEquals(0, calls.get());
    }

    @Test
    void exceptionsFromEvaluatedBranchesPropagate() {
        var failure = new IllegalStateException("check failed");
        var expectation = Expect.or(Expect.sql(POSTGRESQL).satisfies(statement -> { throw failure; }).build(),
            Expect.sql(POSTGRESQL).select().build());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> expectation.matches("SELECT 1")));
        assertThrows(
            IllegalArgumentException.class,
            () -> Expect.or(Expect.sql(POSTGRESQL).column("SELECT FROM").build(), Expect.sql(POSTGRESQL).select().build()).matches("SELECT 1")
        );
    }

    @Test
    void supportsNestedOrAndDefensivelyCopiesAlternatives() {
        var alternatives = new Expect[]{Expect.sql(POSTGRESQL).table("users").build()};
        var snapshot = Expect.or(alternatives);
        alternatives[0] = Expect.sql(POSTGRESQL).table("missing").build();
        snapshot.assertMatches("SELECT id FROM users");
        Expect.or(snapshot, Expect.or(Expect.sql(POSTGRESQL).table("orders").build())).assertMatches("SELECT id FROM orders");
    }

    @Test
    void alternativesCanUseDifferentDialects() {
        Expect.or(Expect.sql(ANSI).column("id").build(), Expect.sql(MYSQL).column("id").build()).assertMatches("SELECT `id` FROM `users`");
    }

    @Test
    void rejectsEmptyAndNullAlternativesAndNullSql() {
        assertThrows(IllegalArgumentException.class, Expect::or);
        assertThrows(NullPointerException.class, () -> Expect.or((Expect[]) null));
        assertThrows(NullPointerException.class, () -> Expect.or((Expect) null));
        assertThrows(
            NullPointerException.class,
            () -> Expect.or(
                Expect.sql(POSTGRESQL)
                    .select()
                    .build()
            )
                .evaluate((String) null)
        );
    }

    @Test
    void parseFailuresDoNotSatisfyOrEvenWithNegativeChecks() {
        var expectation = Expect.or(Expect.sql(POSTGRESQL).noColumn("password").build(), Expect.sql(ANSI).noTable("users").build());
        assertFalse(expectation.matches("not sql"));
        assertThrows(AssertionError.class, () -> expectation.assertMatches("not sql"));
        assertEquals(2, expectation.evaluate("not sql").mismatches().stream().filter(mismatch -> mismatch.code().equals("PARSE_ERROR")).count());
    }
}
