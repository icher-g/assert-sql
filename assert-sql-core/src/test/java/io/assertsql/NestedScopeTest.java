package io.assertsql;

import io.sqm.core.ExistsPredicate;
import io.sqm.core.QueryTable;
import io.sqm.core.SelectQuery;
import io.sqm.core.dialect.SqlDialectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

class NestedScopeTest {
    @Test
    void derivedAndCorrelatedExistsQueriesHaveIndependentContracts() {
        var sql = """
            SELECT page.id FROM (
                SELECT u.id FROM users u WHERE u.active = true
            ) page
            WHERE EXISTS (SELECT 1 FROM audit a WHERE a.user_id = page.id)
            """;
        Expect.sql(POSTGRESQL)
            .outer(query -> query.noTable("users", "audit").exactProjection("page.id"))
            .derived("page", query -> query.table("users u").where("u.active = true").noTable("audit").noColumn("a.user_id"))
            .exists(query -> query
                .table("audit a")
                .where("a.user_id = page.id")
                .column("a.user_id", "page.id")
                .noTable("users", "page")
                .exactProjection("1")
            )
            .build()
            .assertMatches(sql);
        assertFalse(Expect.sql(POSTGRESQL).derived("page", query -> query.table("audit")).build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).exists(query -> query.table("users")).build().matches(sql));
    }

    @Test
    void repeatedAliasesAtDifferentLevelsRequireExplicitNestedSelection() {
        var sql = "SELECT d.id FROM (SELECT d.id FROM (SELECT id FROM users) d) d";
        Expect.sql(POSTGRESQL)
            .derived("d", query -> query
                .noTable("users")
                .derived("d", nested -> nested.table("users").exactProjection("id"))
                .exactProjection("d.id")
            )
            .build()
            .assertMatches(sql);
        var result = Expect.sql(POSTGRESQL).derived("d", query -> query.table("users")).build().evaluate(sql);
        assertEquals("TABLE_NOT_REFERENCED", result.mismatches().getFirst().code());
    }

    @Test
    void siblingExistsQueriesRequireAnIndexAndDoNotSatisfyEachOther() {
        var sql = """
            SELECT u.id FROM users u
            WHERE EXISTS (SELECT 1 FROM audit a WHERE a.user_id = u.id)
            AND EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id)
            """;
        var ambiguous = Expect.sql(POSTGRESQL).exists(query -> query.noTable("missing")).build().evaluate(sql);
        assertEquals("SCOPE_AMBIGUOUS", ambiguous.mismatches().getFirst().code());
        Expect.sql(POSTGRESQL)
            .exists(0, query -> query.table("audit a").noTable("orders", "users").where("a.user_id = u.id"))
            .exists(1, query -> query.table("orders o").noTable("audit", "users").where("o.user_id = u.id"))
            .build()
            .assertMatches(sql);
        assertFalse(Expect.sql(POSTGRESQL).exists(0, query -> query.table("orders")).build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).exists(1, query -> query.table("audit")).build().matches(sql));
        var outOfRange = Expect.sql(POSTGRESQL).exists(2, Expect.Builder::noWhere).build().evaluate(sql);
        assertEquals("SCOPE_NOT_FOUND", outOfRange.mismatches().getFirst().code());
        assertEquals("EXISTS subquery[2]", outOfRange.mismatches().getFirst().path());
    }

    @Test
    void deeplyNestedExistsPredicatesAreSelectedOneLevelAtATime() {
        var sql = """
            SELECT u.id FROM users u WHERE EXISTS (
                SELECT a.id FROM audit a WHERE EXISTS (
                    SELECT o.id FROM orders o WHERE o.user_id = u.id
                )
            )
            """;
        Expect.sql(POSTGRESQL)
            .exists(query -> query
                .table("audit a")
                .noTable("orders", "users")
                .exists(nested -> nested.table("orders o").column("u.id").noTable("audit", "users"))
            )
            .build()
            .assertMatches(sql);
        var result = Expect.sql(POSTGRESQL).exists(1, Expect.Builder::noWhere).build().evaluate(sql);
        assertEquals("SCOPE_NOT_FOUND", result.mismatches().getFirst().code());
    }

    @Test
    void definitionsAndOtherSubqueryKindsAreNotSearchedImplicitly() {
        var sql = """
            WITH inputs AS (SELECT id FROM (SELECT id FROM hidden_users) hidden)
            SELECT (SELECT id FROM (SELECT id FROM scalar_users) scalar_data) AS id
            FROM users WHERE EXISTS (SELECT id FROM (SELECT id FROM audit) events)
            """;
        var hidden = Expect.sql(POSTGRESQL).derived("hidden", Expect.Builder::noWhere).build().evaluate(sql);
        var scalar = Expect.sql(POSTGRESQL).derived("scalar_data", Expect.Builder::noWhere).build().evaluate(sql);
        var events = Expect.sql(POSTGRESQL).derived("events", Expect.Builder::noWhere).build().evaluate(sql);
        assertEquals("SCOPE_NOT_FOUND", hidden.mismatches().getFirst().code());
        assertEquals("SCOPE_NOT_FOUND", scalar.mismatches().getFirst().code());
        assertEquals("SCOPE_NOT_FOUND", events.mismatches().getFirst().code());
        Expect.sql(POSTGRESQL)
            .cte("inputs", query -> query.derived("hidden", nested -> nested.table("hidden_users")))
            .exists(query -> query.derived("events", nested -> nested.table("audit")))
            .build()
            .assertMatches(sql);
        var nestedOnly = "SELECT (SELECT id FROM users WHERE EXISTS (SELECT 1 FROM audit)) AS id";
        assertEquals(
            "SCOPE_NOT_FOUND",
            Expect.sql(POSTGRESQL).exists(Expect.Builder::noWhere).build().evaluate(nestedOnly).mismatches().getFirst().code()
        );
    }

    @Test
    void scopesCanBeComposedWithCteSelectionInEitherDirection() {
        var sql = """
            WITH report AS (
                SELECT page.id FROM (WITH inputs AS (SELECT id FROM users) SELECT id FROM inputs) page
                WHERE EXISTS (WITH events AS (SELECT id FROM audit) SELECT id FROM events)
            )
            SELECT id FROM report
            """;
        Expect.sql(POSTGRESQL)
            .cte("report", query -> query
                .derived("page", nested -> nested.cte("inputs", body -> body.table("users")).outer(body -> body.table("inputs")))
                .exists(nested -> nested.cte("events", body -> body.table("audit")).outer(body -> body.table("events")))
            )
            .build()
            .assertMatches(sql);
    }

    @Test
    void selectionFailsForMissingScopesEvenWithNegativeOnlyBlocks() {
        var derived = Expect.sql(POSTGRESQL).derived("page", query -> query.noTable("users").noWhere()).build();
        var exists = Expect.sql(POSTGRESQL).exists(query -> query.noParameter("id").noJoin()).build();
        assertEquals("SCOPE_NOT_FOUND", derived.evaluate("SELECT id FROM users page").mismatches().getFirst().code());
        assertEquals("SCOPE_NOT_FOUND", exists.evaluate("SELECT id FROM users").mismatches().getFirst().code());
        var duplicate = "SELECT 1 FROM (SELECT id FROM users) d JOIN (SELECT id FROM orders) d ON 1 = 1";
        var ambiguous = Expect.sql(POSTGRESQL).derived("d", Expect.Builder::noWhere).build().evaluate(duplicate);
        assertEquals("SCOPE_AMBIGUOUS", ambiguous.mismatches().getFirst().code());
    }

    @Test
    void localSearchesDoNotCountParametersFromParentsSiblingsOrDescendants() {
        var sql = """
            SELECT :outerValue FROM (SELECT id FROM users WHERE status = :status) page
            WHERE EXISTS (SELECT 1 FROM audit WHERE user_id = page.id AND kind = :kind)
            """;
        Expect.sql(POSTGRESQL)
            .parameterCount(3)
            .derived("page", query -> query.parameter("status").noParameter("outerValue", "kind").parameterCount(1))
            .exists(query -> query.parameter("kind").noParameter("outerValue", "status").parameterCount(1))
            .outer(query -> query.parameter("outerValue").parameterCount(1))
            .build()
            .assertMatches(sql);
    }

    @Test
    void notExistsSelectsItsBodyAndDoesNotClaimToAssertPredicatePolarity() {
        var sql = "SELECT u.id FROM users u WHERE NOT EXISTS (SELECT 1 FROM audit a WHERE a.user_id = u.id)";
        Expect.sql(POSTGRESQL).exists(query -> query.table("audit a").column("u.id")).build().assertMatches(sql);
        var result = Expect.sql(POSTGRESQL).exists(query -> query.table("missing")).build().evaluate(sql);
        assertEquals("NOT EXISTS subquery[0]", result.mismatches().getFirst().path());
    }

    @Test
    void indexedExistsUsesModelOrderAcrossProjectionAndWhere() {
        var sql = """
            SELECT CASE WHEN EXISTS (SELECT 1 FROM projection_events) THEN 1 ELSE 0 END AS seen
            FROM users WHERE EXISTS (SELECT 1 FROM filter_events)
            """;
        Expect.sql(POSTGRESQL)
            .exists(0, query -> query.table("projection_events").noTable("filter_events"))
            .exists(1, query -> query.table("filter_events").noTable("projection_events"))
            .build()
            .assertMatches(sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ansi", "postgresql", "mysql", "sqlserver", "oracle"})
    void commonNestedSelectorsWorkAcrossDialects(String dialect) {
        Expect.sql(SqlDialectId.of(dialect))
            .derived("page", query -> query.table("users").noWhere())
            .exists(query -> query.table("audit").noWhere())
            .build()
            .assertMatches("SELECT page.id FROM (SELECT id FROM users) page WHERE EXISTS (SELECT 1 FROM audit)");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "postgresql|\"Page\"|\"Page\"|true", "postgresql|\"Page\"|page|false", "postgresql|PAGE|page|true",
        "mysql|`Page`|`Page`|true", "sqlserver|[Page]|[Page]|true", "oracle|\"Page\"|\"Page\"|true"
    })
    void derivedAliasesFollowExistingIdentifierRules(String dialect, String actualAlias, String selector, boolean matches) {
        var sql = "SELECT 1 FROM (SELECT id FROM users) " + actualAlias;
        var expectation = Expect.sql(SqlDialectId.of(dialect)).derived(selector, query -> query.table("users")).build();
        assertEquals(matches, expectation.matches(sql));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "postgresql|SELECT u.id FROM users u CROSS JOIN LATERAL (SELECT id FROM audit WHERE user_id = u.id) recent",
        "sqlserver|SELECT u.id FROM users u CROSS APPLY (SELECT id FROM audit WHERE user_id = u.id) recent",
        "sqlserver|SELECT u.id FROM users u OUTER APPLY (SELECT id FROM audit WHERE user_id = u.id) recent"
    })
    void derivedSelectionIncludesLateralAndApplyQueryTables(String dialect, String sql) {
        Expect.sql(SqlDialectId.of(dialect))
            .derived("recent", query -> query.table("audit").column("u.id").where("user_id = u.id"))
            .build()
            .assertMatches(sql);
    }

    @Test
    void setTermsAreAtTheSameLevelButNestedQueriesAreNot() {
        var sql = "SELECT id FROM users WHERE EXISTS (SELECT 1 FROM audit) UNION SELECT id FROM orders WHERE EXISTS (SELECT 1 FROM payments)";
        Expect.sql(POSTGRESQL)
            .exists(0, query -> query.table("audit"))
            .exists(1, query -> query.table("payments"))
            .build()
            .assertMatches(sql);
        assertEquals("SCOPE_AMBIGUOUS", Expect.sql(POSTGRESQL).exists(Expect.Builder::noWhere).build().evaluate(sql).mismatches().getFirst().code());
    }

    @Test
    void customChecksReceiveOriginalNodesAndStructuralComparisonsKeepTheWholeBody() {
        var original = new AtomicReference<SelectQuery>();
        var sql = "SELECT page.id FROM (SELECT id FROM users) page WHERE EXISTS (SELECT 1 FROM audit)";
        Expect.sql(POSTGRESQL)
            .satisfies(statement -> {
                original.set(assertInstanceOf(SelectQuery.class, statement));
                return List.of();
            })
            .derived("page", query -> query.satisfies(statement -> {
                assertSame(assertInstanceOf(QueryTable.class, original.get().from()).query(), statement);
                return List.of();
            }).structurallyEquals("SELECT id FROM users"))
            .exists(query -> query.satisfies(statement -> {
                assertSame(assertInstanceOf(ExistsPredicate.class, original.get().where()).subquery(), statement);
                return List.of();
            }).structurallyEquals("SELECT 1 FROM audit"))
            .build()
            .assertMatches(sql);
    }

    @Test
    void diagnosticsPreserveTheCompleteSelectorChainAndRenderedSql() {
        var expectation = Expect.sql(POSTGRESQL)
            .derived("page", query -> query
                .exists(nested -> nested
                    .as("audit projection")
                    .structurallyEquals("SELECT 1 FROM audit")
                )
            )
            .build();
        var result = expectation.evaluate("SELECT id FROM (SELECT id FROM users WHERE EXISTS (SELECT 2 FROM audit)) page");
        assertEquals("derived table \"page\" / EXISTS subquery[0]", result.mismatches().getFirst().path());
        assertEquals(List.of("audit projection"), result.mismatches().getFirst().descriptions());
        assertTrue(result.describe().contains("Expected SQL <1> but was <2>"), result::describe);
        assertFalse(result.describe().contains("statement."));
    }

    @Test
    void declarationValidationAndParseFailuresDoNotInvokeNestedChecks() {
        var builder = Expect.sql(POSTGRESQL);
        var calls = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> builder.exists(-1, Expect.Builder::select));
        assertThrows(IllegalArgumentException.class, () -> builder.derived(" ", Expect.Builder::select));
        assertThrows(NullPointerException.class, () -> builder.derived(null, Expect.Builder::select));
        assertThrows(NullPointerException.class, () -> builder.exists(null));
        assertThrows(NullPointerException.class, () -> builder.derived("page", null));
        assertThrows(IllegalStateException.class, () -> builder.exists(query -> {}));
        assertThrows(IllegalStateException.class, () -> builder.derived("page", query -> query.as("empty")));
        assertThrows(IllegalStateException.class, builder::build);
        var invalidAlias = builder.derived("schema.page", Expect.Builder::select).build();
        assertEquals("PARSE_ERROR", invalidAlias.evaluate("invalid SQL").mismatches().getFirst().code());
        assertThrows(IllegalArgumentException.class, () -> invalidAlias.evaluate("SELECT 1"));
        var expectation = Expect.sql(POSTGRESQL).exists(query -> query.satisfies(statement -> {
            calls.incrementAndGet();
            return List.of();
        })).build();
        assertEquals("PARSE_ERROR", expectation.evaluate("invalid SQL").mismatches().getFirst().code());
        assertEquals("SCOPE_NOT_FOUND", expectation.evaluate("SELECT 1").mismatches().getFirst().code());
        expectation.evaluate("SELECT 1 WHERE EXISTS (SELECT 1)");
        assertEquals(1, calls.get());
    }

    @Test
    void blocksSnapshotDeclarationsAndComposeWithOrAcrossConcurrentEvaluations() throws Exception {
        var escaped = new AtomicReference<Expect.Builder>();
        var derived = Expect.sql(POSTGRESQL).derived("page", query -> {
            escaped.set(query);
            query.table("users");
        }).build();
        escaped.get().table("missing");
        var exists = Expect.sql(POSTGRESQL).exists(query -> query.table("audit")).build();
        var alternatives = Expect.or(derived, exists);
        var derivedSql = "SELECT id FROM (SELECT id FROM users) page";
        var existsSql = "SELECT id FROM users WHERE EXISTS (SELECT 1 FROM audit)";
        try (var executor = Executors.newFixedThreadPool(4)) {
            var results = IntStream.range(0, 20).mapToObj(i -> executor.submit(() -> {
                alternatives.assertMatches(derivedSql);
                alternatives.assertMatches(existsSql);
                assertFalse(alternatives.matches("SELECT 1"));
                return true;
            })).toList();
            for (var result : results) {
                assertTrue(result.get(10, TimeUnit.SECONDS));
            }
        }
        var failed = alternatives.evaluate("SELECT 1");
        assertTrue(failed.mismatches().get(1).path().contains("or[0].derived table \"page\""));
        assertTrue(failed.mismatches().get(2).path().contains("or[1].EXISTS subquery"));
    }
}
