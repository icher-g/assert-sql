package io.assertsql.examples;

import io.assertsql.Expect;
import io.assertsql.examples.fixtures.JooqUserQueries;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

class JooqExampleTest {
    @Test
    void assertsGeneratedSqlWithoutExecutingIt() {
        /* SQL inspected (formatted for readability):
         * SELECT u.id, u.name, p.display_name AS profile_name
         * FROM users AS u
         * LEFT OUTER JOIN user_profiles AS p ON p.user_id = u.id
         * WHERE u.tenant_id = :tenantId
         * ORDER BY u.created_at DESC, u.id ASC
         * OFFSET 40 ROWS FETCH NEXT 20 ROWS ONLY
         */
        var query = JooqUserQueries.users(42L, 20, 40, false);
        var sql = query.getSQL(ParamType.NAMED);
        var expectation = Expect.sql(POSTGRESQL)
            .as("tenant user page with optional profiles")
            .select()
            .table("users u")
            .leftJoinTable("user_profiles p")
            .exactProjection("u.id", "u.name", "p.display_name AS profile_name")
            .where("u.tenant_id = :tenantId")
            .orderBy("u.created_at DESC", "u.id ASC")
            .limit(20)
            .offset(40)
            .parameter("tenantId")
            .parameterCount(1)
            .positionalParameterCount(0)
            .build();
        expectation.assertMatches(sql);
    }

    @Test
    void supportsJunitAssertionsThroughMatch() {
        /* SQL inspected (formatted for readability):
         * SELECT u.id, u.name, p.display_name AS profile_name
         * FROM users AS u
         * LEFT OUTER JOIN user_profiles AS p ON p.user_id = u.id
         * WHERE u.tenant_id = :tenantId
         * ORDER BY u.created_at DESC, u.id ASC
         * OFFSET 40 ROWS FETCH NEXT 20 ROWS ONLY
         */
        var query = JooqUserQueries.users(42L, 20, 40, false);
        var expectation = Expect.sql(POSTGRESQL)
            .as("tenant user page with optional profiles")
            .select()
            .table("users u")
            .leftJoinTable("user_profiles p")
            .exactProjection("u.id", "u.name", "p.display_name AS profile_name")
            .where("u.tenant_id = :tenantId")
            .orderBy("u.created_at DESC", "u.id ASC")
            .limit(20)
            .offset(40)
            .parameter("tenantId")
            .parameterCount(1)
            .positionalParameterCount(0)
            .build();
        var result = expectation.evaluate(query.getSQL(ParamType.NAMED));
        assertTrue(result.matches(), result::describe);
    }

    @Test
    void detectsAnInnerJoinRegressionUsingStandardAssertionError() {
        /* SQL inspected (formatted for readability):
         * SELECT u.id, u.name, p.display_name AS profile_name
         * FROM users AS u
         * JOIN user_profiles AS p ON p.user_id = u.id
         * WHERE u.tenant_id = :tenantId
         * ORDER BY u.created_at DESC, u.id ASC
         * OFFSET 40 ROWS FETCH NEXT 20 ROWS ONLY
         * Regression: this INNER JOIN violates the contract requiring a LEFT JOIN.
         */
        var query = JooqUserQueries.users(42L, 20, 40, true);
        var expectation = Expect.sql(POSTGRESQL)
            .as("tenant user page with optional profiles")
            .select()
            .table("users u")
            .leftJoinTable("user_profiles p")
            .exactProjection("u.id", "u.name", "p.display_name AS profile_name")
            .where("u.tenant_id = :tenantId")
            .orderBy("u.created_at DESC", "u.id ASC")
            .limit(20)
            .offset(40)
            .parameter("tenantId")
            .parameterCount(1)
            .positionalParameterCount(0)
            .build();
        var error = assertThrows(AssertionError.class, () -> expectation.assertMatches(query.getSQL(ParamType.NAMED)));
        assertTrue(error.getMessage().contains("tenant user page with optional profiles"));
        assertTrue(error.getMessage().contains("JOIN_TABLE_NOT_REFERENCED"));
        assertTrue(error.getMessage().contains("user_profiles p"));
    }

    @Test
    void checksIndexedPlaceholdersAndReadsBindingsSeparately() {
        /* SQL inspected (formatted for readability):
         * SELECT u.id, u.name, p.display_name AS profile_name
         * FROM users AS u
         * LEFT OUTER JOIN user_profiles AS p ON p.user_id = u.id
         * WHERE u.tenant_id = ?
         * ORDER BY u.created_at DESC, u.id ASC
         * OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY
         * Bind values: [42L]. Pagination is inline and adds no placeholders.
         */
        var query = JooqUserQueries.users(42L, 10, 0, false);
        var expectation = Expect.sql(POSTGRESQL)
            .leftJoinTable("user_profiles p")
            .limit(10)
            .offset(0)
            .parameterCount(1)
            .positionalParameterCount(1)
            .build();
        expectation.assertMatches(query.getSQL(ParamType.INDEXED));
        // Bind values come from jOOQ, not from SQL parsing. Assert SQL currently checks only the placeholders.
        assertEquals(List.of(42L), query.getBindValues());
    }
}
