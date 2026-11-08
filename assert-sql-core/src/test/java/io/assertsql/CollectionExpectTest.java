package io.assertsql;

import io.sqm.core.Statement;
import io.sqm.core.dialect.SqlDialectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static io.sqm.core.dialect.SqlDialectId.ANSI;
import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

class CollectionExpectTest {
    private static final List<String> MIXED = List.of(
        "SELECT id FROM users",
        "INSERT INTO users (id) VALUES (1)",
        "UPDATE users SET name = 'Ada' WHERE id = 1",
        "DELETE FROM users WHERE id = 1",
        "MERGE INTO users u USING incoming i ON u.id = i.id WHEN MATCHED THEN UPDATE SET name = i.name"
    );

    @Test
    void countsEveryOuterKindWithoutRequiringChecksInsideAnExpectation() {
        Expect.statements(POSTGRESQL)
            .count(5)
            .selectCount(1)
            .insertCount(1)
            .updateCount(1)
            .deleteCount(1)
            .mergeCount(1)
            .build()
            .assertMatches(MIXED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT", "INSERT", "UPDATE", "DELETE", "MERGE"})
    void filteredCountsRestrictKindBeforeMatchingTables(String kind) {
        var builder = Expect.statements(POSTGRESQL);
        filteredCount(builder, kind, 1, query -> query.table("users"));
        builder.build()
            .assertMatches(MIXED);
        var noMatches = Expect.statements(POSTGRESQL);
        filteredCount(noMatches, kind, 0, query -> query.table("absent"));
        noMatches.build()
            .assertMatches(MIXED);
        var wrongCount = Expect.statements(POSTGRESQL);
        filteredCount(wrongCount, kind, 0, query -> query.table("users"));
        var result = wrongCount.build()
            .evaluate(MIXED);
        assertCode(result, kind + "_COUNT_MISMATCH");
        assertTrue(result.describe().contains("indices [" + List.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE").indexOf(kind) + "]"));
    }

    @Test
    void countsWithAndSetQueriesOnceAndDoesNotCountDmlInsideCteBodiesAsSeparateStatements() {
        Expect.statements(POSTGRESQL)
            .count(5)
            .selectCount(3)
            .insertCount(1)
            .updateCount(1)
            .deleteCount(0)
            .build()
            .assertMatches(List.of(
                "INSERT INTO archived (id) SELECT id FROM users",
                "UPDATE users SET name = 'Ada' WHERE id IN (SELECT id FROM archived)",
                "WITH ids AS (SELECT id FROM users) SELECT id FROM ids",
                "WITH changed AS (UPDATE users SET name = 'Ada' RETURNING id) SELECT id FROM changed",
                "SELECT id FROM users UNION ALL SELECT id FROM archived"
            ));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "WITH ids AS (SELECT id FROM users) INSERT INTO archived (id) SELECT id FROM ids",
        "WITH ids AS (SELECT id FROM users) UPDATE users SET name = 'Ada' WHERE id IN (SELECT id FROM ids)"
    })
    void unsupportedWithDmlOuterBodiesFailParsingInsteadOfSatisfyingAbsence(String sql) {
        var result = Expect.statements(POSTGRESQL)
            .updateCount(0)
            .build()
            .evaluate(List.of(sql));
        assertCode(result, "PARSE_ERROR");
    }

    @Test
    void preservesDuplicatesAndReportsOriginalIndicesForUnexpectedUpdates() {
        var expectation = Expect.statements(POSTGRESQL)
            .as("read budget")
            .count(3)
            .selectCount(2)
            .updateCount(0)
            .build();
        var result = expectation.evaluate(List.of(MIXED.getFirst(), MIXED.get(2), MIXED.getFirst()));
        assertEquals(1, result.mismatches().size());
        assertCode(result, "UPDATE_COUNT_MISMATCH");
        assertTrue(result.describe().contains("indices [1]"));
        var error = assertThrows(AssertionError.class, result::assertMatches);
        assertTrue(error.getMessage().contains("read budget"));
        Expect.statements(POSTGRESQL)
            .selectCount(2)
            .build()
            .assertMatches(List.of(MIXED.getFirst(), MIXED.getFirst()));
        var extraSelect = Expect.statements(POSTGRESQL)
            .selectCount(2)
            .build()
            .evaluate(List.of("SELECT 1", "SELECT 2", "SELECT 3"));
        assertCode(extraSelect, "SELECT_COUNT_MISMATCH");
        assertTrue(extraSelect.describe().contains("indices [0, 1, 2]"));
    }

    @Test
    void emptyCollectionsHaveExplicitQuantifierAndCountSemantics() {
        var users = Expect.sql(POSTGRESQL)
            .table("users")
            .build();
        Expect.statements(POSTGRESQL)
            .count(0)
            .selectCount(0)
            .insertCount(0)
            .updateCount(0)
            .deleteCount(0)
            .mergeCount(0)
            .matchingCount(users, 0)
            .all(users)
            .none(users)
            .build()
            .assertMatches(List.of());
        var any = Expect.statements(POSTGRESQL)
            .any(users)
            .build()
            .evaluate(List.of());
        var exact = Expect.statements(POSTGRESQL)
            .matchingCount(users, 1)
            .build()
            .evaluate(List.of());
        assertCode(any, "ANY_MATCH_MISMATCH");
        assertCode(exact, "MATCH_COUNT_MISMATCH");
    }

    @Test
    void genericMatchersHaveNoImplicitKindAndComposeWithOr() {
        var users = Expect.sql(POSTGRESQL)
            .table("users")
            .build();
        var audit = Expect.sql(POSTGRESQL)
            .table("audit")
            .build();
        var forbidden = Expect.sql(POSTGRESQL)
            .table("forbidden")
            .build();
        Expect.statements(POSTGRESQL)
            .matchingCount(users, 2)
            .any(audit)
            .all(Expect.or(users, audit))
            .none(forbidden)
            .build()
            .assertMatches(List.of("SELECT id FROM users", "UPDATE users SET name = 'Ada'", "SELECT id FROM audit"));
        var result = Expect.statements(POSTGRESQL)
            .all(users)
            .build()
            .evaluate(List.of("SELECT id FROM users", "SELECT id FROM audit"));
        assertCode(result, "ALL_MATCH_MISMATCH");
        assertTrue(result.describe().contains("statements[1]"));
        assertFalse(result.describe().contains("statements[0]"));
    }

    @Test
    void collectionPreflightReportsEveryInvalidIndexAndSkipsAllChecks() {
        var calls = new AtomicInteger();
        var selector = Expect.sql(POSTGRESQL)
            .satisfies(statement -> {
                calls.incrementAndGet();
                return List.of();
            })
            .build();
        var result = Expect.statements(POSTGRESQL)
            .count(3)
            .any(selector)
            .none(selector)
            .updateCount(0)
            .build()
            .evaluate(List.of("SELECT FROM", "SELECT 1", "invalid SQL"));
        assertFalse(result.matches());
        assertEquals(0, calls.get());
        assertTrue(result.mismatches().getFirst().path().startsWith("statements[0].sql["));
        assertTrue(result.mismatches().getLast().path().startsWith("statements[2].sql["));
        assertTrue(
            result.mismatches()
                .stream()
                .allMatch(mismatch -> mismatch.code().equals("PARSE_ERROR"))
        );
    }

    @Test
    void aGenericExpectationsOwnDialectParseErrorCannotProveAbsenceOrBeHiddenByAnEarlierMatch() {
        var ansiSelect = Expect.sql(ANSI)
            .select()
            .build();
        var statements = List.of("SELECT 1", "SELECT id::text FROM users");
        var none = Expect.statements(POSTGRESQL)
            .none(ansiSelect)
            .build()
            .evaluate(statements);
        var any = Expect.statements(POSTGRESQL)
            .any(ansiSelect)
            .build()
            .evaluate(statements);
        assertCode(none, "PARSE_ERROR");
        assertCode(any, "PARSE_ERROR");
        assertTrue(none.describe().contains("statements[1]"));
        assertTrue(any.describe().contains("statements[1]"));
    }

    @Test
    void unavailableBindingsAndMatchingOrderedSetQueriesCannotProveAZeroMatchCount() {
        var binding = Expect.sql(POSTGRESQL)
            .binding("id", 42L)
            .build();
        var unavailable = Expect.statements(POSTGRESQL)
            .none(binding)
            .build()
            .evaluate(List.of("SELECT :id"));
        assertCode(unavailable, "BINDINGS_UNAVAILABLE");
        var ordered = Expect.sql(POSTGRESQL)
            .orderBy("id")
            .build();
        var result = Expect.statements(POSTGRESQL)
            .matchingCount(ordered, 0)
            .build()
            .evaluate(List.of("SELECT id FROM users UNION ALL SELECT id FROM archived ORDER BY id"));
        assertCode(result, "MATCH_COUNT_MISMATCH");
    }

    @Test
    void preparesAllCallbackFiltersBeforeRunningAnyChecksAndSkipsPreparationForInvalidActualSql() {
        var calls = new AtomicInteger();
        var expectation = Expect.statements(POSTGRESQL)
            .selectCount(1, query -> query.satisfies(statement -> {
                calls.incrementAndGet();
                return List.of();
            }))
            .selectCount(1, query -> query.where("id ="))
            .build();
        assertEquals(0, calls.get());
        assertThrows(IllegalArgumentException.class, () -> expectation.evaluate(List.of("SELECT id FROM users")));
        assertEquals(0, calls.get());
        assertThrows(IllegalArgumentException.class, () -> expectation.evaluate(List.of()));
        assertCode(expectation.evaluate(List.of("SELECT FROM")), "PARSE_ERROR");
    }

    @Test
    void callbackFiltersReuseTheSameParsedNodesAcrossRules() {
        var first = new ArrayList<Statement>();
        var second = new ArrayList<Statement>();
        var expectation = Expect.statements(POSTGRESQL)
            .selectCount(2, query -> query.satisfies(statement -> {
                first.add(statement);
                return List.of();
            }))
            .selectCount(2, query -> query.satisfies(statement -> {
                second.add(statement);
                return List.of();
            }))
            .build();
        expectation.assertMatches(List.of("SELECT 1", "SELECT 1"));
        assertEquals(2, first.size());
        assertSame(first.getFirst(), second.getFirst());
        assertSame(first.getLast(), second.getLast());
        assertNotSame(first.getFirst(), first.getLast());
    }

    @Test
    void snapshotsInputBeforeACustomCheckCanMutateTheOriginalCollection() {
        var statements = new ArrayList<>(List.of("SELECT 1", "SELECT 2"));
        Expect.statements(POSTGRESQL)
            .count(2)
            .selectCount(2, query -> query.satisfies(statement -> {
                statements.clear();
                return List.of();
            }))
            .build()
            .assertMatches(statements);
        assertTrue(statements.isEmpty());
    }

    @Test
    void snapshotsBuildersAndEscapedCallbackBuilders() {
        var escaped = new AtomicReference<Expect.Builder>();
        var builder = Expect.statements(POSTGRESQL)
            .selectCount(1, query -> {
                escaped.set(query);
                query.table("users");
            });
        var first = builder.build();
        escaped.get().noTable("users");
        builder.updateCount(0);
        var second = builder.build();
        var statements = List.of("SELECT id FROM users", "UPDATE users SET name = 'Ada'");
        first.assertMatches(statements);
        assertCode(second.evaluate(statements), "UPDATE_COUNT_MISMATCH");
    }

    @Test
    void customExceptionsPropagateAndWrongKindsNeverInvokeTheFilter() {
        var failure = new IllegalStateException("custom failure");
        var expectation = Expect.statements(POSTGRESQL)
            .selectCount(0, query -> query.satisfies(statement -> {
                throw failure;
            }))
            .build();
        expectation.assertMatches(List.of("UPDATE users SET name = 'Ada'"));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> expectation.evaluate(List.of("SELECT 1"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ansi", "postgresql", "mysql", "oracle", "sqlserver"})
    void supportsEveryExistingParserDialect(String name) {
        Expect.statements(SqlDialectId.of(name))
            .count(4)
            .selectCount(1)
            .insertCount(1)
            .updateCount(1)
            .deleteCount(1)
            .build()
            .assertMatches(MIXED.subList(0, 4));
    }

    @Test
    void rejectsNullsNegativeCountsAndEmptyDeclarations() {
        assertThrows(NullPointerException.class, () -> Expect.statements(null));
        assertThrows(IllegalArgumentException.class, () -> Expect.statements(SqlDialectId.of("unknown")));
        assertThrows(
            IllegalStateException.class,
            () -> Expect.statements(POSTGRESQL)
                .build()
        );
        assertThrows(
            IllegalStateException.class,
            () -> Expect.statements(POSTGRESQL)
                .as("description only")
                .build()
        );
        var builder = Expect.statements(POSTGRESQL);
        var callbacks = new AtomicInteger();
        for (var kind : List.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE")) {
            assertThrows(IllegalArgumentException.class, () -> filteredCount(builder, kind, -1, query -> callbacks.incrementAndGet()));
            assertThrows(NullPointerException.class, () -> filteredCount(builder, kind, 0, null));
            assertThrows(IllegalStateException.class, () -> filteredCount(builder, kind, 0, query -> { }));
        }
        assertEquals(0, callbacks.get());
        assertThrows(IllegalArgumentException.class, () -> builder.count(-1));
        assertThrows(IllegalArgumentException.class, () -> builder.selectCount(-1));
        assertThrows(IllegalArgumentException.class, () -> builder.insertCount(-1));
        assertThrows(IllegalArgumentException.class, () -> builder.updateCount(-1));
        assertThrows(IllegalArgumentException.class, () -> builder.deleteCount(-1));
        assertThrows(IllegalArgumentException.class, () -> builder.mergeCount(-1));
        var query = Expect.sql(POSTGRESQL)
            .select()
            .build();
        assertThrows(IllegalArgumentException.class, () -> builder.matchingCount(query, -1));
        assertThrows(NullPointerException.class, () -> builder.matchingCount(null, 0));
        assertThrows(NullPointerException.class, () -> builder.any(null));
        assertThrows(NullPointerException.class, () -> builder.all(null));
        assertThrows(NullPointerException.class, () -> builder.none(null));
        assertThrows(NullPointerException.class, () -> builder.as(null));
        var expectation = builder.count(0)
            .build();
        assertThrows(NullPointerException.class, () -> expectation.evaluate(null));
        assertThrows(NullPointerException.class, () -> expectation.matches(null));
        assertThrows(NullPointerException.class, () -> expectation.assertMatches(null));
        assertThrows(NullPointerException.class, () -> expectation.evaluate(Arrays.asList("SELECT 1", null)));
    }

    @Test
    void countBoundariesAndImmutableDiagnosticsAreExplicit() {
        var result = Expect.statements(POSTGRESQL)
            .count(Integer.MAX_VALUE)
            .selectCount(Integer.MAX_VALUE)
            .build()
            .evaluate(List.of());
        assertCode(result, "STATEMENT_COUNT_MISMATCH");
        assertCode(result, "SELECT_COUNT_MISMATCH");
        assertThrows(UnsupportedOperationException.class, () -> result.mismatches().clear());
        assertFalse(
            Expect.statements(POSTGRESQL)
                .count(0)
                .build()
                .matches(List.of("SELECT 1"))
        );
    }

    @Test
    void collectionsSupportConcurrentReuseWithoutLeakingResults() throws Exception {
        var expectation = Expect.statements(POSTGRESQL)
            .count(1)
            .selectCount(1, query -> query.table("users"))
            .updateCount(0)
            .build();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = IntStream.range(0, 20)
                .mapToObj(index -> executor.submit(() -> {
                    expectation.assertMatches(List.of("SELECT id FROM users"));
                    assertFalse(expectation.matches(List.of("UPDATE users SET name = 'Ada'")));
                    assertFalse(expectation.matches(List.of("invalid SQL")));
                    return true;
                }))
                .toList();
            for (var future : futures) {
                assertTrue(future.get(10, TimeUnit.SECONDS));
            }
        }
    }

    private static void filteredCount(CollectionExpect.Builder builder, String kind, int count, Consumer<Expect.Builder> declaration) {
        switch (kind) {
            case "SELECT" -> builder.selectCount(count, declaration);
            case "INSERT" -> builder.insertCount(count, declaration);
            case "UPDATE" -> builder.updateCount(count, declaration);
            case "DELETE" -> builder.deleteCount(count, declaration);
            case "MERGE" -> builder.mergeCount(count, declaration);
            default -> throw new IllegalArgumentException(kind);
        }
    }

    private static void assertCode(Match result, String code) {
        assertFalse(result.matches());
        assertTrue(
            result.mismatches()
                .stream()
                .anyMatch(mismatch -> mismatch.code().equals(code)),
            result::describe
        );
    }
}
