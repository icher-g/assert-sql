package io.assertsql;

import io.sqm.core.JoinKind;
import io.sqm.core.dialect.SqlDialectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static io.sqm.core.dialect.SqlDialectId.*;
import static org.junit.jupiter.api.Assertions.*;

class QueryShapeTest {
    @Test
    void joinTablesMatchTypeAndAliasOnTheSameJoin() {
        var sql = "SELECT u.id FROM users u LEFT JOIN orders o ON o.user_id = u.id "
            + "JOIN payments p USING (id) RIGHT JOIN audit a ON a.id = u.id FULL JOIN history h ON h.id = u.id";
        Expect.sql(POSTGRESQL)
            .joinTable("orders", "payments p")
            .leftJoinTable("Orders O")
            .innerJoinTable("payments p")
            .rightJoinTable("audit a")
            .fullJoinTable("history h")
            .noJoinTable("missing", "orders other")
            .noJoinTable(JoinKind.INNER, "orders")
            .build()
            .assertMatches(sql);
        assertFalse(Expect.sql(POSTGRESQL).innerJoinTable("orders").build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).joinTable("users u").build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).noJoinTable("orders").build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).noJoinTable(JoinKind.LEFT, "orders").build().matches(sql));
    }

    @Test
    void joinTablesIncludeNestedCrossAndNaturalButNotTablesInsideDerivedJoinSources() {
        var sql = "SELECT q.id FROM (SELECT id FROM users CROSS JOIN orders NATURAL JOIN payments) q "
            + "JOIN (SELECT id FROM audit) a ON a.id = q.id";
        Expect.sql(POSTGRESQL).joinTable("orders", "payments").noJoinTable("audit", "a").build().assertMatches(sql);
        assertFalse(Expect.sql(POSTGRESQL).innerJoinTable("payments").build().matches(sql));
    }

    @Test
    void projectionPresenceUsesOuterSelectItemsIncludingAliasesAndExpressions() {
        var sql = "SELECT u.id, u.name AS display_name, COUNT(*) AS total FROM users u GROUP BY u.id, u.name";
        Expect.sql(POSTGRESQL)
            .projection("u.name AS display_name", "u.id", "COUNT(*) AS total")
            .noProjection("u.password", "u.name")
            .build()
            .assertMatches(sql);
        assertFalse(Expect.sql(POSTGRESQL).projection("u.name").build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).projection("u.id").build().matches("SELECT u.name FROM users u WHERE u.id = 1"));
        assertFalse(Expect.sql(POSTGRESQL).noProjection("u.id").build().matches(sql));
    }

    @Test
    void exactProjectionChecksOrderDuplicatesAndExtraItems() {
        var expectation = Expect.sql(POSTGRESQL).exactProjection("id", "name AS display").build();
        expectation.assertMatches("SELECT id, name AS display FROM users");
        for (
            var sql : List.of(
                "SELECT name AS display, id FROM users",
                "SELECT id FROM users",
                "SELECT id, name AS display, secret FROM users",
                "SELECT id, name FROM users"
            )
        ) {
            assertFalse(expectation.matches(sql), sql);
        }
        Expect.sql(POSTGRESQL).exactProjection("id", "id").build().assertMatches("SELECT id, id FROM users");
        assertFalse(Expect.sql(POSTGRESQL).exactProjection("id", "id").build().matches("SELECT id FROM users"));
        Expect.sql(POSTGRESQL).exactProjection("u.*").build().assertMatches("SELECT u.* FROM users u");
        var result = expectation.evaluate("SELECT name AS display, id FROM users");
        assertEquals("PROJECTION_MISMATCH", result.mismatches().getFirst().code());
        assertTrue(result.mismatches().getFirst().path().contains("[0]"));
    }

    @Test
    void projectionUsesWithBodyAndFirstSetTermWithoutSearchingNestedSelects() {
        Expect.sql(POSTGRESQL)
            .exactProjection("id")
            .noProjection("secret")
            .build()
            .assertMatches("WITH c AS (SELECT id, secret FROM users) SELECT id FROM c");
        Expect.sql(POSTGRESQL)
            .exactProjection("id AS result")
            .build()
            .assertMatches("SELECT id AS result FROM users UNION SELECT other_id FROM orders");
        assertFalse(Expect.sql(POSTGRESQL).projection("id").build().matches("DELETE FROM users WHERE id = 1"));
    }

    @Test
    void orderByRequiresTheCompleteOuterListWithDirectionAndNullOrdering() {
        var expectation = Expect.sql(POSTGRESQL).orderBy("created_at DESC NULLS LAST", "id ASC").build();
        expectation.assertMatches("SELECT id FROM users ORDER BY created_at DESC NULLS LAST, id ASC");
        for (
            var order : List.of(
                "id ASC, created_at DESC NULLS LAST",
                "created_at ASC NULLS LAST, id ASC",
                "created_at DESC NULLS FIRST, id ASC",
                "created_at DESC NULLS LAST",
                "created_at DESC NULLS LAST, id"
            )
        ) {
            assertFalse(expectation.matches("SELECT id FROM users ORDER BY " + order), order);
        }
        assertFalse(expectation.matches("SELECT id FROM users"));
        Expect.sql(POSTGRESQL).orderBy("1 DESC", "LOWER(name)").build().assertMatches("SELECT id, name FROM users ORDER BY 1 DESC, LOWER(name)");
        assertFalse(Expect.sql(POSTGRESQL).noOrderBy().build().matches("SELECT id FROM users ORDER BY id"));
    }

    @Test
    void orderingAndPaginationInspectTheOuterQueryAndSetOperation() {
        Expect.sql(POSTGRESQL)
            .noOrderBy()
            .noLimit()
            .noOffset()
            .build()
            .assertMatches("SELECT q.id FROM (SELECT id FROM users ORDER BY id LIMIT 5 OFFSET 2) q");
        Expect.sql(POSTGRESQL)
            .orderBy("id")
            .limit(5)
            .offset(2)
            .build()
            .assertMatches("WITH c AS (SELECT id FROM users) SELECT id FROM c ORDER BY id LIMIT 5 OFFSET 2");
        Expect.sql(POSTGRESQL)
            .orderBy("id DESC")
            .limit(5)
            .offset(2)
            .build()
            .assertMatches("(SELECT id FROM users) UNION (SELECT id FROM orders) ORDER BY id DESC LIMIT 5 OFFSET 2");
    }

    @Test
    void ambiguousSqmSetTailCannotPassPositiveOrNegativeOuterClauseChecks() {
        var sql = "SELECT id FROM users UNION SELECT id FROM orders ORDER BY id LIMIT 5 OFFSET 2";
        var result = Expect.sql(POSTGRESQL).orderBy("id").limit(5).offset(2).noOrderBy().noLimit().noOffset().build().evaluate(sql);
        assertEquals(6, result.mismatches().size());
        assertTrue(result.mismatches().stream().allMatch(m -> m.code().equals("SQM_SCOPE_UNSUPPORTED")));
        assertThrows(AssertionError.class, result::assertMatches);
        // Unrelated checks continue to operate on SQM's model normally.
        Expect.sql(POSTGRESQL).table("users", "orders").build().assertMatches(sql);
        Expect.sql(POSTGRESQL).noOrderBy().noLimit().noOffset().build().assertMatches("SELECT id FROM users UNION SELECT id FROM orders");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "postgresql|SELECT id FROM users LIMIT 20 OFFSET 40",
        "mysql|SELECT id FROM users LIMIT 40, 20",
        "sqlserver|SELECT id FROM users ORDER BY id OFFSET 40 ROWS FETCH NEXT 20 ROWS ONLY",
        "oracle|SELECT id FROM users OFFSET 40 ROWS FETCH NEXT 20 ROWS ONLY",
        "ansi|SELECT id FROM users LIMIT 20 OFFSET 40"
    })
    void literalPaginationSupportsDialectModels(String dialect, String sql) {
        var id = SqlDialectId.of(dialect);
        Expect.sql(id).limit(20).offset(40).build().assertMatches(sql);
        assertFalse(Expect.sql(id).limit(21).build().matches(sql));
        assertFalse(Expect.sql(id).offset(41).build().matches(sql));
        assertFalse(Expect.sql(id).noLimit().build().matches(sql));
        assertFalse(Expect.sql(id).noOffset().build().matches(sql));
    }

    @Test
    void paginationDoesNotInferAbsentOrParameterizedCounts() {
        Expect.sql(POSTGRESQL).limit(0).offset(0).build().assertMatches("SELECT id FROM users LIMIT 0 OFFSET 0");
        assertFalse(Expect.sql(POSTGRESQL).offset(0).build().matches("SELECT id FROM users"));
        assertFalse(Expect.sql(POSTGRESQL).limit(20).build().matches("SELECT id FROM users LIMIT :pageSize"));
        assertFalse(Expect.sql(POSTGRESQL).offset(20).build().matches("SELECT id FROM users OFFSET :skipRows"));
        assertFalse(Expect.sql(POSTGRESQL).limit(20).build().matches("SELECT id FROM users LIMIT ALL"));
        assertFalse(Expect.sql(POSTGRESQL).noLimit().build().matches("SELECT id FROM users LIMIT ALL"));
        Expect.sql(POSTGRESQL).noLimit().noOffset().build().assertMatches("SELECT id FROM users");
    }

    @Test
    void plainSqlServerTopMatchesButPercentAndTiesAreNotFixedLimits() {
        Expect.sql(SQLSERVER).limit(20).noOffset().build().assertMatches("SELECT TOP (20) id FROM users");
        for (
            var sql : List.of(
                "SELECT TOP (20) PERCENT id FROM users",
                "SELECT TOP (20) WITH TIES id FROM users ORDER BY id",
                "SELECT TOP (@count) id FROM users"
            )
        ) {
            assertFalse(Expect.sql(SQLSERVER).limit(20).build().matches(sql), sql);
            assertFalse(Expect.sql(SQLSERVER).noLimit().build().matches(sql), sql);
        }
    }

    @Test
    void parametersCountOccurrencesRatherThanDistinctBindingsAndIgnoreText() {
        var sql = "SELECT ':fake ? $9' AS text FROM users WHERE id = :id OR parent_id = :id "
            + "AND status = :status AND other_id = $1 AND last_id = $1 /* :ignored */";
        Expect.sql(POSTGRESQL)
            .parameter("id", "status")
            .noParameter("fake", "ignored", "ID")
            .parameterCount(5)
            .positionalParameterCount(2)
            .build()
            .assertMatches(sql);
        assertFalse(Expect.sql(POSTGRESQL).parameterCount(3).build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).parameter("ID").build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).noParameter("id").build().matches(sql));
        Expect.sql(POSTGRESQL).parameterCount(0).positionalParameterCount(0).noParameter("id").build().assertMatches("SELECT 1");
        Expect.sql(MYSQL).parameterCount(2).positionalParameterCount(2).build().assertMatches("SELECT id FROM users WHERE id = ? AND name = ?");
    }

    @Test
    void parametersIncludeNestedQueriesDmlAndPagination() {
        Expect.sql(POSTGRESQL)
            .parameter("tenantId", "pageSize", "id")
            .parameterCount(3)
            .build()
            .assertMatches("WITH c AS (SELECT id FROM users WHERE tenant_id = :tenantId) " + "SELECT id FROM c WHERE id = :id LIMIT :pageSize");
        Expect.sql(POSTGRESQL).parameter("name", "id").parameterCount(2).build().assertMatches("UPDATE users SET name = :name WHERE id = :id");
        Expect.sql(SQLSERVER).parameter("id").parameterCount(1).build().assertMatches("SELECT id FROM users WHERE id = :id");
    }

    @ParameterizedTest
    @MethodSource("fragments")
    void fragmentDeclarationsValidateContainersButDeferParsing(BiFunction<Expect.Builder, String[], Expect.Builder> method) {
        var builder = Expect.sql(POSTGRESQL);
        assertThrows(IllegalArgumentException.class, () -> method.apply(builder, new String[0]));
        assertThrows(NullPointerException.class, () -> method.apply(builder, null));
        assertThrows(NullPointerException.class, () -> method.apply(builder, new String[]{"id", null}));
        assertThrows(IllegalStateException.class, builder::build);
        var expectation = method.apply(builder, new String[]{"SELECT FROM"}).build();
        assertEquals("PARSE_ERROR", expectation.evaluate("bad sql").mismatches().getFirst().code());
        assertThrows(IllegalArgumentException.class, () -> expectation.evaluate("SELECT 1"));
    }

    @Test
    void rejectsInvalidNumbersKindsAndParameterNamesWithoutAddingChecks() {
        var builder = Expect.sql(POSTGRESQL);
        assertThrows(IllegalArgumentException.class, () -> builder.limit(-1));
        assertThrows(IllegalArgumentException.class, () -> builder.offset(-1));
        assertThrows(IllegalArgumentException.class, () -> builder.parameterCount(-1));
        assertThrows(IllegalArgumentException.class, () -> builder.positionalParameterCount(-1));
        assertThrows(NullPointerException.class, () -> builder.joinTable((JoinKind) null, "users"));
        assertThrows(NullPointerException.class, () -> builder.noJoinTable((JoinKind) null, "users"));
        assertThrows(IllegalArgumentException.class, builder::parameter);
        assertThrows(NullPointerException.class, () -> builder.parameter((String[]) null));
        assertThrows(NullPointerException.class, () -> builder.noParameter("id", null));
        for (var name : List.of("", " ", ":id", "@id", "#{id}")) {
            assertThrows(IllegalArgumentException.class, () -> builder.parameter("valid", name));
            assertThrows(IllegalArgumentException.class, () -> builder.noParameter("valid", name));
        }
        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void capturesArraysAtDeclarationAndKeepsBuiltSnapshotsIndependent() {
        var items = new String[]{"id", "name"};
        var names = new String[]{"id"};
        var builder = Expect.sql(POSTGRESQL).exactProjection(items).parameter(names);
        var expectation = builder.build();
        items[0] = "other";
        names[0] = "other";
        builder.parameter("missing");
        expectation.assertMatches("SELECT id, name FROM users WHERE id = :id");
        assertFalse(builder.build().matches("SELECT id, name FROM users WHERE id = :id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ansi", "postgresql", "mysql", "sqlserver", "oracle"})
    void commonShapeChecksWorkAcrossDialects(String dialect) {
        Expect.sql(SqlDialectId.of(dialect))
            .leftJoinTable("orders o")
            .projection("u.id")
            .exactProjection("u.id", "u.name AS display")
            .orderBy("u.id DESC")
            .noParameter("other")
            .parameterCount(0)
            .build()
            .assertMatches("SELECT u.id, u.name AS display FROM users u LEFT JOIN orders o " + "ON u.id = o.user_id ORDER BY u.id DESC");
    }

    @Test
    void negativeChecksDoNotPassOnInvalidSqlAndComposeWithOr() {
        var noClauses = Expect.sql(POSTGRESQL).noJoinTable("users").noProjection("id").noOrderBy().noLimit().noOffset().noParameter("id").build();
        assertFalse(noClauses.matches("bad SQL"));
        Expect.or(Expect.sql(POSTGRESQL).exactProjection("id").build(), Expect.sql(POSTGRESQL).exactProjection("name").build())
            .assertMatches("SELECT name FROM users");
    }

    private static Stream<BiFunction<Expect.Builder, String[], Expect.Builder>> fragments() {
        return Stream.of(
            Expect.Builder::joinTable,
            Expect.Builder::noJoinTable,
            Expect.Builder::projection,
            Expect.Builder::noProjection,
            Expect.Builder::exactProjection,
            Expect.Builder::orderBy
        );
    }
}
