package io.assertsql;

import io.sqm.core.SelectQuery;
import io.sqm.core.Statement;
import io.sqm.core.dialect.SqlDialectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static io.sqm.core.dialect.SqlDialectId.*;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationTest {
    @ParameterizedTest
    @ValueSource(strings = {"ansi", "postgresql", "mysql", "sqlserver", "oracle"})
    void parsesCommonSqlInEveryDialect(String dialect) {
        Expect.sql(SqlDialectId.of(dialect)).select().table("users")
            .noSelectStar().build().assertMatches("SELECT id FROM users");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "postgresql|SELECT id::text FROM users",
        "mysql|SELECT `id` FROM `users` LIMIT 1",
        "sqlserver|SELECT TOP 1 [id] FROM [users]",
        "oracle|SELECT id FROM users FETCH FIRST 1 ROWS ONLY"
    })
    void usesDialectSpecificGrammar(String dialect, String sql) {
        Expect.sql(SqlDialectId.of(dialect)).select().build().assertMatches(sql);
    }

    @Test
    void rejectsUnknownOrNullDialectImmediately() {
        assertThrows(NullPointerException.class, () -> Expect.sql(null));
        assertThrows(IllegalArgumentException.class, () -> Expect.sql(SqlDialectId.of("unknown")));
    }

    @Test
    void defersChecksAndExpectedParsingUntilATerminalCall() {
        var calls = new AtomicInteger();
        var expectation = Expect.sql(POSTGRESQL).satisfies(statement -> {
            calls.incrementAndGet();
            return List.of();
        });
        var valid = expectation.build();
        var invalid = expectation.structurallyEquals("SELECT FROM").build();
        var invalidTable = Expect.sql(POSTGRESQL).table("users WHERE id = 1").build();
        assertEquals(0, calls.get());
        assertThrows(IllegalArgumentException.class, () -> invalid.evaluate("SELECT 1"));
        assertEquals("PARSE_ERROR", invalid.evaluate("bad actual SQL").mismatches().getFirst().code());
        assertEquals("PARSE_ERROR", invalidTable.evaluate("bad actual SQL").mismatches().getFirst().code());
        assertThrows(IllegalArgumentException.class, () -> invalidTable.evaluate("SELECT 1"));
        assertEquals(0, calls.get());
        assertTrue(valid.matches("SELECT 1"));
        assertEquals(1, calls.get());
    }

    @Test
    void mutableBuilderProducesIndependentSnapshots() {
        var builder = Expect.sql(POSTGRESQL);
        assertSame(builder, builder.select());
        var select = builder.build();
        assertSame(builder, builder.table("users u"));
        var users = builder.build();
        assertSame(builder, builder.table("orders o"));
        var both = builder.build();
        assertTrue(select.matches("SELECT 1"));
        assertTrue(users.matches("SELECT id FROM users u"));
        assertFalse(both.matches("SELECT id FROM users u"));
        assertFalse(users.matches("SELECT id FROM orders o"));
        both.assertMatches("SELECT u.id FROM users u JOIN orders o ON u.id = o.id");
    }

    @Test
    void buildRejectsEmptyAndDescriptionOnlyDeclarations() {
        var builder = Expect.sql(POSTGRESQL);
        assertThrows(IllegalStateException.class, builder::build);
        builder.as("description only");
        assertThrows(IllegalStateException.class, builder::build);
        builder.select();
        assertDoesNotThrow(builder::build);
    }

    @Test
    void customChecksReceiveTheSameParsedSqmInstance() {
        var first = new AtomicReference<Statement>();
        var result = Expect.sql(POSTGRESQL)
            .satisfies(statement -> {
                assertInstanceOf(SelectQuery.class, statement);
                first.set(statement);
                return List.of(new Mismatch("CUSTOM_ONE", "", "First custom failure."));
            })
            .satisfies(statement -> {
                assertSame(first.get(), statement);
                return List.of(new Mismatch("CUSTOM_TWO", "", "Second custom failure."));
            })
            .build().evaluate("SELECT 1");
        assertEquals(List.of("CUSTOM_ONE", "CUSTOM_TWO"),
            result.mismatches().stream().map(Mismatch::code).toList());
    }

    @Test
    void collectsFailuresInDeclarationOrderAndAppliesDescription() {
        var base = Expect.sql(POSTGRESQL).select().table("missing")
            .noSelectStar();
        var undescribed = base.build();
        var result = base.as("user lookup").build().evaluate("UPDATE users SET id = (SELECT * FROM orders)");
        assertEquals(List.of("NOT_SELECT", "TABLE_NOT_REFERENCED", "SELECT_STAR"),
            result.mismatches().stream().map(Mismatch::code).toList());
        var error = assertThrows(AssertionError.class, result::assertMatches);
        assertTrue(error.getMessage().startsWith("[user lookup]"));
        assertFalse(undescribed.evaluate("SELECT 1").describe().contains("user lookup"));
        assertTrue(Expect.sql(POSTGRESQL).as("success").select().build().evaluate("SELECT 1")
            .describe().startsWith("[success]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not sql", "SELECT FROM", "SELECT 1; SELECT 2", "SELECT 1 trailing garbage"})
    void invalidActualSqlProducesDiagnosticsAndSkipsChecks(String sql) {
        var expectation = Expect.sql(POSTGRESQL).satisfies(statement -> {
            fail("A check must not run against invalid SQL");
            return List.of();
        });
        var result = expectation.build().evaluate(sql);
        assertFalse(result.matches());
        assertEquals("PARSE_ERROR", result.mismatches().getFirst().code());
        assertFalse(result.mismatches().getFirst().message().isBlank());
        assertFalse(expectation.build().matches(sql));
        assertThrows(AssertionError.class, () -> expectation.build().assertMatches(sql));
    }

    @Test
    void rejectsWrongDialectSyntax() {
        assertFalse(Expect.sql(ANSI).select().build().matches("SELECT `id` FROM `users`"));
        assertFalse(Expect.sql(ANSI).select().build().matches("SELECT TOP 1 id FROM users"));
    }

    @Test
    void nullArgumentsAreRejected() {
        var expectation = Expect.sql(POSTGRESQL);
        assertThrows(NullPointerException.class, () -> expectation.as(null));
        assertThrows(NullPointerException.class, () -> expectation.table((String[]) null));
        assertThrows(NullPointerException.class, () -> expectation.structurallyEquals(null));
        assertThrows(NullPointerException.class, () -> expectation.satisfies(null));
        assertThrows(NullPointerException.class, () -> expectation.select().build().evaluate(null));
    }

    @Test
    void customCheckErrorsPropagateAndBrokenResultsAreRejected() {
        var failure = new IllegalStateException("custom failure");
        var expectation = Expect.sql(POSTGRESQL).satisfies(statement -> { throw failure; });
        assertSame(failure, assertThrows(IllegalStateException.class, () -> expectation.build().matches("SELECT 1")));
        assertThrows(NullPointerException.class,
            () -> Expect.sql(POSTGRESQL).satisfies(statement -> null).build().matches("SELECT 1"));
        assertThrows(NullPointerException.class,
            () -> Expect.sql(POSTGRESQL).satisfies(statement -> Collections.singletonList(null))
                .build().matches("SELECT 1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT id FROM users", "WITH u AS (SELECT id FROM users) SELECT id FROM u",
        "SELECT id FROM users UNION SELECT id FROM orders"})
    void selectIncludesCtesAndSetOperations(String sql) {
        Expect.sql(POSTGRESQL).select().build().assertMatches(sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE users SET id = 1", "DELETE FROM users", "INSERT INTO users (id) VALUES (1)"})
    void dmlIsNotSelectButStillSupportsTableChecks(String sql) {
        assertFalse(Expect.sql(POSTGRESQL).select().build().matches(sql));
        Expect.sql(POSTGRESQL).table("users").build().assertMatches(sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT * FROM users", "SELECT u.* FROM users u",
        "SELECT id FROM (SELECT * FROM users) u", "WITH u AS (SELECT * FROM users) SELECT id FROM u"})
    void findsWildcardsInAllQueryScopes(String sql) {
        var result = Expect.sql(POSTGRESQL).noSelectStar().build().evaluate(sql);
        assertFalse(result.matches());
        assertEquals("SELECT_STAR", result.mismatches().getFirst().code());
    }

    @Test
    void countStarIsNotAWildcardProjectionAndAllWildcardsAreReported() {
        Expect.sql(POSTGRESQL).noSelectStar().build().assertMatches("SELECT COUNT(*) FROM users");
        assertEquals(2, Expect.sql(POSTGRESQL).noSelectStar()
            .build().evaluate("SELECT *, u.* FROM users u").mismatches().size());
    }

    @Test
    void expectationSupportsConcurrentReuse() throws Exception {
        var expectation = Expect.sql(POSTGRESQL).select().table("users")
            .noSelectStar().structurallyEquals("SELECT id FROM users").build();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = executor.invokeAll(IntStream.range(0, 24)
                .<java.util.concurrent.Callable<Boolean>>mapToObj(i ->
                    () -> expectation.matches(i % 2 == 0 ? "SELECT id FROM users" : "SELECT * FROM orders"))
                .toList());
            for (int i = 0; i < futures.size(); i++) {
                assertEquals(i % 2 == 0, futures.get(i).get());
            }
        }
    }
}
