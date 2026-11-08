package io.assertsql;

import io.sqm.core.WithQuery;
import io.sqm.core.dialect.SqlDialectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

class ScopeExpectationsTest {
    @Test
    void separatesOuterReferencesFromCtesAndCorrelatedSubqueries() {
        var sql = """
            WITH active_users AS (SELECT id FROM users WHERE active = true)
            SELECT a.id FROM active_users a
            WHERE EXISTS (SELECT x.user_id FROM audit x WHERE x.user_id = a.secret)
            """;
        Expect.sql(POSTGRESQL)
            .table("users", "audit")
            .column("x.user_id", "a.secret")
            .outer(query -> query.table("active_users a").column("a.id").noTable("users", "audit").noColumn("x.user_id", "a.secret"))
            .cte("active_users", query -> query.table("users").exactProjection("id").where("active = true").noTable("audit"))
            .build()
            .assertMatches(sql);
        assertFalse(Expect.sql(POSTGRESQL).outer(query -> query.table("users")).build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).outer(query -> query.column("x.user_id")).build().matches(sql));
    }

    @Test
    void excludesDerivedAndScalarQueriesEvenWhenAliasesCollide() {
        var sql = """
            SELECT u.id, (SELECT u.secret FROM secrets u) AS secret
            FROM users u JOIN (SELECT id FROM orders u) q ON q.id = u.id
            """;
        Expect.sql(POSTGRESQL)
            .outer(query -> query.table("users u").column("u.id").noTable("orders u", "secrets u").noColumn("u.secret"))
            .build()
            .assertMatches(sql);
        assertFalse(Expect.sql(POSTGRESQL).outer(query -> query.table("orders u")).build().matches(sql));
    }

    @Test
    void callsAfterAScopeBlockRetainTheirUnscopedSearchBehavior() {
        var sql = "SELECT u.id FROM users u WHERE EXISTS (SELECT x.user_id FROM audit x WHERE x.user_id = u.id)";
        Expect.sql(POSTGRESQL)
            .outer(query -> query.noTable("audit").noColumn("x.user_id"))
            .table("audit")
            .column("x.user_id")
            .build()
            .assertMatches(sql);
    }

    @Test
    void wildcardChecksExcludeOtherDefinitionsAndDetectTheSelectedCteWildcard() {
        var sql = "WITH raw_users AS (SELECT * FROM users) SELECT id FROM raw_users";
        Expect.sql(POSTGRESQL).outer(Expect.Builder::noSelectStar).build().assertMatches(sql);
        var result = Expect.sql(POSTGRESQL).cte("raw_users", Expect.Builder::noSelectStar).build().evaluate(sql);
        assertEquals("SELECT_STAR", result.mismatches().getFirst().code());
        assertTrue(result.mismatches().getFirst().path().contains("CTE \"raw_users\""));
    }

    @Test
    void limitsAllJoinSearchesAndWildcardSearchesToTheSelectedLevel() {
        var sql = """
            SELECT u.id FROM users u LEFT JOIN orders o ON o.user_id = u.id
            WHERE EXISTS (SELECT x.* FROM audit x JOIN payments p ON p.id = x.payment_id)
            """;
        Expect.sql(POSTGRESQL)
            .outer(query -> query
                .join("LEFT JOIN orders o ON o.user_id = u.id")
                .leftJoinTable("orders o")
                .noJoin("JOIN payments p ON p.id = x.payment_id")
                .noJoinTable("payments p")
                .noSelectStar()
            )
            .build()
            .assertMatches(sql);
        assertFalse(Expect.sql(POSTGRESQL).outer(query -> query.joinTable("payments")).build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).outer(Expect.Builder::noJoin).build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).noSelectStar().build().matches(sql));
        Expect.sql(POSTGRESQL).outer(query -> query.noJoin().noSelectStar()).build()
            .assertMatches("SELECT id FROM users WHERE EXISTS (SELECT * FROM audit JOIN payments USING (id))");
    }

    @Test
    void countsOnlyParametersAtTheSelectedLevelIncludingPagination() {
        var sql = """
            WITH inputs AS (SELECT id FROM users WHERE tenant_id = :cteTenant)
            SELECT :outerValue, s.id FROM inputs s
            WHERE s.id = :outerValue AND EXISTS (SELECT ? FROM audit WHERE id = :innerValue)
            LIMIT :pageSize
            """;
        Expect.sql(POSTGRESQL)
            .parameterCount(6)
            .outer(query -> query
                .parameter("outerValue", "pageSize")
                .noParameter("cteTenant", "innerValue")
                .parameterCount(3)
                .positionalParameterCount(0)
            )
            .cte("inputs", query -> query.parameter("cteTenant").parameterCount(1).noParameter("outerValue"))
            .build()
            .assertMatches(sql);
        assertFalse(Expect.sql(POSTGRESQL).outer(query -> query.parameter("cteTenant")).build().matches(sql));
    }

    @Test
    void checksAllOuterClausesRelativeToTheSelectedBody() {
        var sql = """
            WITH page AS (
                SELECT id, name FROM users WHERE active = true ORDER BY id DESC LIMIT 20 OFFSET 40
            )
            SELECT id FROM page
            """;
        Expect.sql(POSTGRESQL)
            .outer(query -> query.select().exactProjection("id").noProjection("name").noWhere().noOrderBy().noLimit().noOffset())
            .cte("page", query -> query
                .projection("name")
                .exactProjection("id", "name")
                .where("active = true")
                .noWhere("active = false")
                .orderBy("id DESC")
                .limit(20)
                .offset(40)
            )
            .build()
            .assertMatches(sql);
    }

    @Test
    void preservesNestedSqlForCompleteClauseAndStructuralComparisons() {
        var body = "SELECT u.id FROM users u WHERE EXISTS (SELECT a.id FROM audit a WHERE a.id = u.id)";
        Expect.sql(POSTGRESQL)
            .outer(query -> query
                .noTable("audit")
                .where("EXISTS (SELECT a.id FROM audit a WHERE a.id = u.id)")
                .structurallyEquals(body)
            )
            .build()
            .assertMatches("WITH ignored AS (SELECT id FROM unrelated) " + body);
        assertFalse(Expect.sql(POSTGRESQL).outer(query -> query.structurallyEquals("SELECT u.id FROM users u")).build().matches(body));
    }

    @Test
    void selectsNestedCtesExplicitlyAndKeepsShadowedNamesSeparate() {
        var sql = """
            WITH inputs AS (SELECT id FROM outer_users),
            report AS (
                WITH inputs AS (SELECT id FROM inner_users)
                SELECT id FROM inputs
            )
            SELECT id FROM report
            """;
        Expect.sql(POSTGRESQL)
            .cte("inputs", query -> query.table("outer_users").noTable("inner_users"))
            .cte("report", query -> query
                .table("inputs")
                .noTable("outer_users", "inner_users")
                .cte("inputs", nested -> nested.table("inner_users").noTable("outer_users"))
                .outer(outer -> outer.table("inputs"))
            )
            .outer(query -> query.table("report").noTable("inputs", "outer_users", "inner_users"))
            .build()
            .assertMatches(sql);
        var missing = Expect.sql(POSTGRESQL).outer(query -> query.cte("inputs", nested -> nested.noTable("users"))).build().evaluate(sql);
        assertEquals("SCOPE_NOT_FOUND", missing.mismatches().getFirst().code());
    }

    @Test
    void doesNotSearchForCteNamesInsideUnselectedDescendants() {
        var sql = "SELECT id FROM (WITH hidden AS (SELECT id FROM users) SELECT id FROM hidden) q";
        var result = Expect.sql(POSTGRESQL).cte("hidden", query -> query.noTable("missing")).build().evaluate(sql);
        assertEquals("SCOPE_NOT_FOUND", result.mismatches().getFirst().code());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "active|active|true", "ACTIVE|active|true", "\"Active\"|Active|true", "\"Active\"|active|false", "active|\"Active\"|false"
    })
    void cteNamesUseTheExistingQuoteAwareIdentifierRules(String actual, String selector, boolean matches) {
        var expectation = Expect.sql(POSTGRESQL).cte(selector, query -> query.table("users")).build();
        assertEquals(matches, expectation.matches("WITH " + actual + " AS (SELECT id FROM users) SELECT id FROM " + actual));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ansi", "postgresql", "mysql", "sqlserver", "oracle"})
    void commonScopesWorkAcrossDialects(String dialect) {
        Expect.sql(SqlDialectId.of(dialect))
            .cte("inputs", query -> query.table("users").column("id"))
            .outer(query -> query.table("inputs").noTable("users"))
            .build()
            .assertMatches("WITH inputs AS (SELECT id FROM users) SELECT id FROM inputs");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"mysql|`ActiveUsers`", "sqlserver|[ActiveUsers]", "oracle|\"ActiveUsers\""})
    void selectorsReuseDialectIdentifierParsing(String dialect, String identifier) {
        Expect.sql(SqlDialectId.of(dialect))
            .cte(identifier, query -> query.table("users"))
            .build()
            .assertMatches("WITH " + identifier + " AS (SELECT id FROM users) SELECT id FROM " + identifier);
    }

    @Test
    void missingOrAmbiguousCtesFailEvenForNegativeChecks() {
        var missing = Expect.sql(POSTGRESQL).cte("inputs", query -> query.noTable("users").noWhere()).build();
        assertEquals("SCOPE_NOT_FOUND", missing.evaluate("SELECT 1").mismatches().getFirst().code());
        var duplicate = "WITH inputs AS (SELECT id FROM users), INPUTS AS (SELECT id FROM orders) SELECT id FROM inputs";
        var ambiguous = missing.evaluate(duplicate);
        assertEquals("SCOPE_AMBIGUOUS", ambiguous.mismatches().getFirst().code());
        assertFalse(ambiguous.matches());
    }

    @Test
    void inspectsRecursiveSetTermsWithoutExpandingCteReferences() {
        var sql = """
            WITH RECURSIVE chain(id) AS (
                SELECT id FROM roots WHERE id = :seedId
                UNION ALL
                SELECT c.id FROM chain c JOIN edges e ON e.parent_id = c.id WHERE e.child_id = :endId
            )
            SELECT id FROM chain
            """;
        Expect.sql(POSTGRESQL)
            .cte("chain", query -> query
                .table("roots", "chain", "edges")
                .column("c.id", "e.parent_id")
                .joinTable("edges")
                .parameter("seedId", "endId")
                .parameterCount(2)
                .noTable("unrelated")
            )
            .outer(query -> query.table("chain").noTable("roots", "edges").parameterCount(0))
            .build()
            .assertMatches(sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UPDATE users SET active = false WHERE id = :id RETURNING id",
        "DELETE FROM users WHERE id = :id RETURNING id",
        "INSERT INTO users (id) VALUES (:id) RETURNING id"
    })
    void cteBodiesCanBeSupportedDmlStatements(String body) {
        Expect.sql(POSTGRESQL)
            .cte("changed", query -> query.table("users").parameter("id").structurallyEquals(body))
            .outer(query -> query.select().table("changed").noTable("users").parameterCount(0))
            .build()
            .assertMatches("WITH changed AS (" + body + ") SELECT id FROM changed");
        var result = Expect.sql(POSTGRESQL).cte("changed", Expect.Builder::select).build()
            .evaluate("WITH changed AS (" + body + ") SELECT id FROM changed");
        assertEquals("NOT_SELECT", result.mismatches().getFirst().code());
    }

    @Test
    void excludesAnInsertSourceQueryFromLocalReferenceSearches() {
        var sql = "WITH added AS (INSERT INTO users (id) SELECT incoming_id FROM incoming RETURNING id) SELECT id FROM added";
        Expect.sql(POSTGRESQL).cte("added", query -> query.table("users").noTable("incoming").noColumn("incoming_id")).build().assertMatches(sql);
    }

    @Test
    void inspectsCompositeOrderingAndPaginationInsideACte() {
        var sql = "WITH page AS (SELECT id FROM users UNION SELECT id FROM orders ORDER BY id LIMIT 5) SELECT id FROM page";
        var expectation = Expect.sql(POSTGRESQL).cte("page", query -> query.orderBy("id").noLimit()).build();
        var result = expectation.evaluate(sql);
        assertEquals(1, result.mismatches().size());
        assertEquals("LIMIT_PRESENT", result.mismatches().getFirst().code());
        Expect.sql(POSTGRESQL).cte("page", query -> query.table("users", "orders")).build().assertMatches(sql);
    }

    @Test
    void customChecksReceiveTheOriginalSelectedNodesIncludingNestedDefinitions() {
        var parsedRoot = new AtomicReference<WithQuery>();
        var sql = "WITH report AS (WITH inputs AS (SELECT id FROM users) SELECT id FROM inputs) SELECT id FROM report";
        Expect.sql(POSTGRESQL)
            .satisfies(statement -> {
                parsedRoot.set(assertInstanceOf(WithQuery.class, statement));
                return List.of();
            })
            .cte("report", query -> query.satisfies(statement -> {
                assertSame(parsedRoot.get().ctes().getFirst().body(), statement);
                assertInstanceOf(WithQuery.class, statement);
                return List.of();
            }))
            .outer(query -> query.satisfies(statement -> {
                assertSame(parsedRoot.get().body(), statement);
                return List.of();
            }))
            .build()
            .assertMatches(sql);
    }

    @Test
    void diagnosticsIdentifyTheNamedDefinitionAndHideModelPathsForRenderedSql() {
        var result = Expect.sql(POSTGRESQL)
            .as("report contract")
            .cte("page", query -> query
                .as("page size")
                .structurallyEquals("SELECT id FROM users LIMIT 20")
            )
            .build()
            .evaluate("WITH other AS (SELECT 1), page AS (SELECT id FROM users LIMIT 10) SELECT id FROM page");
        assertEquals("CTE \"page\" (WITH item 2)", result.mismatches().getFirst().path());
        assertEquals(List.of("report contract", "page size"), result.mismatches().getFirst().descriptions());
        assertTrue(result.describe().contains("Expected SQL <LIMIT 20> but was <LIMIT 10>"), result::describe);
        assertFalse(result.describe().contains("limitOffset"));
        assertTrue(result.describe().startsWith("[report contract]"));
    }
}
