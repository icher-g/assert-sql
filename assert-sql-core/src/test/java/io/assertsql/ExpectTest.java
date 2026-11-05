package io.assertsql;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

class ExpectTest {
    @Test
    void terminalsDelegateToEvaluationExactlyOncePerCall() {
        var calls = new AtomicInteger();
        var mismatch = new Mismatch("KIND", "", "Expected SELECT.");
        var expectation = Expect.sql(POSTGRESQL).satisfies(statement -> {
            calls.incrementAndGet();
            return List.of(mismatch);
        }).build();

        assertEquals(0, calls.get());
        assertFalse(expectation.matches("SELECT 1"));
        assertEquals(1, calls.get());
        var error = assertThrows(AssertionError.class, () -> expectation.assertMatches("SELECT 1"));
        assertEquals(2, calls.get());
        assertEquals(Match.of(List.of(mismatch)).describe(), error.getMessage());
    }

    @Test
    void successfulAssertionReturnsNormally() {
        var expectation = Expect.sql(POSTGRESQL).select().build();
        assertTrue(expectation.matches("SELECT 1"));
        assertDoesNotThrow(() -> expectation.assertMatches("SELECT 1"));
    }

    @Test
    void nullSqlIsRejectedBeforeEvaluation() {
        var calls = new AtomicInteger();
        var expectation = Expect.sql(POSTGRESQL).satisfies(statement -> {
            calls.incrementAndGet();
            return List.of();
        }).build();
        assertThrows(NullPointerException.class, () -> expectation.evaluate(null));
        assertThrows(NullPointerException.class, () -> expectation.matches(null));
        assertThrows(NullPointerException.class, () -> expectation.assertMatches(null));
        assertEquals(0, calls.get());
    }
}
