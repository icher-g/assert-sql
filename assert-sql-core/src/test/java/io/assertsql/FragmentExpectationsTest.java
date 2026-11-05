package io.assertsql;

import io.sqm.core.dialect.SqlDialectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

class FragmentExpectationsTest {
    private static final String SQL = "SELECT u.id, u.name FROM users u "
        + "INNER JOIN orders o ON o.user_id = u.id "
        + "LEFT JOIN payments p ON p.order_id = o.id WHERE u.active = true";

    @Test
    void positiveVarargsRequireEveryItemAndCollectAllFailures() {
        Expect.sql(POSTGRESQL).table("users u", "orders o", "payments p")
            .column("u.id", "u.name", "o.user_id", "p.order_id")
            .join("INNER JOIN orders o ON o.user_id = u.id", "LEFT JOIN payments p ON p.order_id = o.id")
            .where("u.active = true").build().assertMatches(SQL);

        var result = Expect.sql(POSTGRESQL).table("users", "missing", "other")
            .column("u.id", "secret", "password").build().evaluate(SQL);
        assertEquals(List.of("TABLE_NOT_REFERENCED", "TABLE_NOT_REFERENCED",
            "COLUMN_NOT_REFERENCED", "COLUMN_NOT_REFERENCED"),
            result.mismatches().stream().map(Mismatch::code).toList());
    }

    @Test
    void negativeVarargsRequireEveryItemToBeAbsent() {
        Expect.sql(POSTGRESQL).noTable("legacy_users", "archive")
            .noColumn("password", "secret")
            .noJoin("CROSS JOIN legacy_users", "RIGHT JOIN orders o ON o.user_id = u.id")
            .noWhere("u.active = false", "u.id = 1").build().assertMatches(SQL);

        var result = Expect.sql(POSTGRESQL).noTable("users", "orders", "absent")
            .noColumn("u.id", "u.name", "absent")
            .noJoin("INNER JOIN orders o ON o.user_id = u.id", "LEFT JOIN payments p ON p.order_id = o.id")
            .noWhere("u.active = true", "u.active = false").build().evaluate(SQL);
        assertEquals(List.of("TABLE_PRESENT", "TABLE_PRESENT", "COLUMN_PRESENT", "COLUMN_PRESENT",
            "JOIN_PRESENT", "JOIN_PRESENT", "WHERE_PRESENT"),
            result.mismatches().stream().map(Mismatch::code).toList());
    }

    @Test
    void tableNegationUsesOptionalSchemaAliasAndQuotingRules() {
        var sql = "SELECT u.id FROM public.users u";
        assertFalse(Expect.sql(POSTGRESQL).noTable("USERS").build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).noTable("public.users AS U").build().matches(sql));
        Expect.sql(POSTGRESQL).noTable("other.users", "users x", "\"Users\"").build().assertMatches(sql);
    }

    @Test
    void columnsAreReferencesAnywhereRatherThanOnlyProjections() {
        var sql = "SELECT u.id AS display_id FROM users u JOIN orders o ON o.user_id = u.id "
            + "WHERE u.active = true GROUP BY u.id, u.active HAVING COUNT(o.id) > 1 ORDER BY u.id";
        Expect.sql(POSTGRESQL).column("u.id", "o.user_id", "u.active", "o.id")
            .noColumn("display_id").build().assertMatches(sql);
        Expect.sql(POSTGRESQL).column("id").build().assertMatches(sql);
        assertFalse(Expect.sql(POSTGRESQL).column("x.id").build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).column("name").build().matches("SELECT * FROM users"));
    }

    @Test
    void columnsFollowIdentifierRulesAndIncludeNestedQueries() {
        var sql = "WITH c AS (SELECT u.id FROM users u) SELECT c.id FROM c "
            + "WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = c.id)";
        Expect.sql(POSTGRESQL).column("U.ID", "o.user_id").build().assertMatches(sql);
        assertFalse(Expect.sql(POSTGRESQL).noColumn("o.user_id").build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).column("\"U\".id").build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).column("ID").build().matches("SELECT \"id\" FROM users"));
        Expect.sql(POSTGRESQL).column("\"id\"").build().assertMatches("SELECT id FROM users");
    }

    @Test
    void joinsMatchCompleteSqmStructure() {
        Expect.sql(POSTGRESQL).join("join orders o on o.user_id = u.id").build().assertMatches(SQL);
        assertFalse(Expect.sql(POSTGRESQL).join("LEFT JOIN orders o ON o.user_id = u.id").build().matches(SQL));
        assertFalse(Expect.sql(POSTGRESQL).join("INNER JOIN orders o ON o.user_id = u.other_id").build().matches(SQL));
        assertFalse(Expect.sql(POSTGRESQL).join("INNER JOIN orders other ON other.user_id = u.id").build().matches(SQL));
    }

    @Test
    void joinsIncludeUsingNaturalCrossAndNestedQueries() {
        var sql = "SELECT q.id FROM (SELECT u.id FROM users u JOIN orders o USING (id) "
            + "NATURAL JOIN payments CROSS JOIN audit) q";
        Expect.sql(POSTGRESQL).join("JOIN orders o USING (id)", "NATURAL JOIN payments", "CROSS JOIN audit")
            .build().assertMatches(sql);
        assertFalse(Expect.sql(POSTGRESQL).noJoin().build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).noJoin("JOIN orders o USING (id)").build().matches(sql));
        Expect.sql(POSTGRESQL).noJoin().build().assertMatches("SELECT id FROM users");
    }

    @Test
    void whereComparesTheEntireOuterPredicateRatherThanSubtrees() {
        Expect.sql(POSTGRESQL).where("u.active = TRUE /* expected */").build().assertMatches(SQL);
        assertFalse(Expect.sql(POSTGRESQL).where("u.active = true").build()
            .matches("SELECT u.id FROM users u WHERE u.active = true AND u.id = 1"));
        assertFalse(Expect.sql(POSTGRESQL).where("u.active = true", "u.id = 1").build()
            .matches("SELECT u.id FROM users u WHERE u.active = true AND u.id = 1"));
        Expect.sql(POSTGRESQL).where("u.active = true", "u.active = true").build().assertMatches(SQL);
        Expect.sql(POSTGRESQL).noWhere("u.active = false").build().assertMatches(SQL);
    }

    @Test
    void whereScopeIsOuterEvenWithCtesAndNestedPredicates() {
        var sql = "WITH c AS (SELECT id FROM users WHERE active = true) SELECT id FROM c WHERE id = 1";
        Expect.sql(POSTGRESQL).where("id = 1").noWhere("active = true").build().assertMatches(sql);
        assertFalse(Expect.sql(POSTGRESQL).where("active = true").build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).noWhere().build().matches(sql));
        Expect.sql(POSTGRESQL).noWhere().build()
            .assertMatches("SELECT q.id FROM (SELECT id FROM users WHERE active = true) q");
        Expect.sql(POSTGRESQL).noWhere("id = 1").build().assertMatches("SELECT id FROM users");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE users SET name = 'x' WHERE id = 1", "DELETE FROM users WHERE id = 1"})
    void outerWhereAlsoSupportsDml(String sql) {
        Expect.sql(POSTGRESQL).where("id = 1").column("id").build().assertMatches(sql);
        assertFalse(Expect.sql(POSTGRESQL).noWhere().build().matches(sql));
    }

    @Test
    void statementsWithoutAnOuterWherePassNoWhere() {
        for (var sql : List.of("INSERT INTO users (id) VALUES (1)", "SELECT id FROM users UNION SELECT id FROM orders")) {
            Expect.sql(POSTGRESQL).noWhere().build().assertMatches(sql);
            assertFalse(Expect.sql(POSTGRESQL).where("id = 1").build().matches(sql));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ansi", "postgresql", "mysql", "sqlserver", "oracle"})
    void fragmentsUseEachConfiguredDialect(String dialect) {
        Expect.sql(SqlDialectId.of(dialect)).table("users u", "orders o")
            .column("U.ID", "O.USER_ID").join("JOIN orders o ON o.user_id = u.id")
            .where("u.active = 1").noColumn("secret").noTable("legacy")
            .build().assertMatches("SELECT u.id FROM users u JOIN orders o ON o.user_id = u.id WHERE u.active = 1");
    }

    private static Stream<BiFunction<Expect.Builder, String[], Expect.Builder>> fragmentMethods() {
        return Stream.of(Expect.Builder::table, Expect.Builder::noTable,
            Expect.Builder::column, Expect.Builder::noColumn, Expect.Builder::join,
            Expect.Builder::noJoin, Expect.Builder::where, Expect.Builder::noWhere);
    }

    @ParameterizedTest
    @MethodSource("fragmentMethods")
    void varargsRejectEmptyAndNullInputsWithoutPartiallyMutatingTheBuilder(
        BiFunction<Expect.Builder, String[], Expect.Builder> method) {
        var builder = Expect.sql(POSTGRESQL);
        assertThrows(IllegalArgumentException.class, () -> method.apply(builder, new String[0]));
        assertThrows(NullPointerException.class, () -> method.apply(builder, null));
        assertThrows(NullPointerException.class, () -> method.apply(builder, new String[]{"id", null}));
        assertThrows(IllegalStateException.class, builder::build);
    }

    @ParameterizedTest
    @MethodSource("fragmentMethods")
    void expectedFragmentParsingIsDeferredAndNegativeChecksCannotHideInvalidSql(
        BiFunction<Expect.Builder, String[], Expect.Builder> method) {
        var expectation = method.apply(Expect.sql(POSTGRESQL), new String[]{"SELECT FROM"}).build();
        assertEquals("PARSE_ERROR", expectation.evaluate("bad actual sql").mismatches().getFirst().code());
        assertThrows(IllegalArgumentException.class, () -> expectation.evaluate("SELECT 1"));
    }

    @Test
    void negativeChecksNeverSucceedOnInvalidActualSql() {
        var expectation = Expect.sql(POSTGRESQL).noTable("users").noColumn("id")
            .noJoin().noWhere().build();
        assertFalse(expectation.matches("broken SQL"));
        assertThrows(AssertionError.class, () -> expectation.assertMatches("broken SQL"));
    }

    @Test
    void varargsAreCopiedWhenDeclaredAndBuildRemainsASnapshot() {
        var names = new String[]{"users", "orders"};
        var builder = Expect.sql(POSTGRESQL).table(names);
        var expectation = builder.build();
        names[0] = "missing";
        builder.noTable("users");
        expectation.assertMatches(SQL);
        assertFalse(builder.build().matches(SQL));
    }
}
