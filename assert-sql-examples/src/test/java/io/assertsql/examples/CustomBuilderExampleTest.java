package io.assertsql.examples;

import io.assertsql.Expect;
import io.assertsql.examples.fixtures.UserSearchQueryBuilder;
import org.junit.jupiter.api.Test;

import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

class CustomBuilderExampleTest {
    @Test
    void comparesTheCompleteQueryStructureRatherThanItsFormatting() {
        /* SQL inspected (the builder generates this on one line):
         * SELECT u.id, u.name, p.display_name AS profile_name
         * FROM users u
         * LEFT JOIN user_profiles p ON p.user_id = u.id
         * WHERE u.tenant_id = :tenantId AND u.active = :active
         * ORDER BY u.created_at DESC, u.id ASC
         * LIMIT 20 OFFSET 40
         */
        var sql = new UserSearchQueryBuilder().page(20, 40).build();
        var expectation = Expect.sql(POSTGRESQL)
            .as("complete active-user page structure")
            .structurallyEquals("""
                select u.id, u.name, p.display_name as profile_name
                from users u
                left join user_profiles p on p.user_id = u.id
                where u.tenant_id = :tenantId and u.active = :active
                -- Formatting, ordinary comments, keyword case, and the trailing semicolon do not change the structure.
                order by u.created_at desc, u.id asc
                limit 20 offset 40;
                """)
            .build();
        expectation.assertMatches(sql);

        // The same query with LIMIT 10 instead of LIMIT 20 has a different structure and must fail.
        var otherPageSql = new UserSearchQueryBuilder().page(10, 40).build();
        var error = assertThrows(AssertionError.class, () -> expectation.assertMatches(otherPageSql));
        assertEquals("""
            [complete active-user page structure] SQL did not match expectations:
            - [STRUCTURE_MISMATCH] Expected SQL <LIMIT 20 OFFSET 40> but was <LIMIT 10 OFFSET 40>.""", error.getMessage());
    }

    @Test
    void assertsSqlProducedByTheApplicationBuilder() {
        /* SQL inspected (formatted for readability):
         * SELECT u.id, u.name, p.display_name AS profile_name
         * FROM users u
         * LEFT JOIN user_profiles p ON p.user_id = u.id
         * WHERE u.tenant_id = :tenantId AND u.active = :active
         * ORDER BY u.created_at DESC, u.id ASC
         * LIMIT 20 OFFSET 40
         */
        var sql = new UserSearchQueryBuilder().page(20, 40).build();
        var expectation = Expect.sql(POSTGRESQL)
            .as("active user page with optional profiles")
            .select()
            .table("users u")
            .leftJoinTable("user_profiles p")
            .exactProjection("u.id", "u.name", "p.display_name AS profile_name")
            .where("u.tenant_id = :tenantId AND u.active = :active")
            .orderBy("u.created_at DESC", "u.id ASC")
            .limit(20)
            .offset(40)
            .parameter("tenantId", "active")
            .parameterCount(2)
            .positionalParameterCount(0)
            .build();
        expectation.assertMatches(sql);
    }

    @Test
    void detectsAMissingActiveFilterThroughMatchDiagnostics() {
        /* SQL inspected (formatted for readability):
         * SELECT u.id, u.name, p.display_name AS profile_name
         * FROM users u
         * LEFT JOIN user_profiles p ON p.user_id = u.id
         * WHERE u.tenant_id = :tenantId
         * ORDER BY u.created_at DESC, u.id ASC
         * LIMIT 20 OFFSET 40
         * Regression: the active-user predicate and its :active parameter are missing.
         */
        var sql = new UserSearchQueryBuilder().page(20, 40).activeOnly(false).build();
        var expectation = Expect.sql(POSTGRESQL)
            .as("active user page with optional profiles")
            .select()
            .table("users u")
            .leftJoinTable("user_profiles p")
            .exactProjection("u.id", "u.name", "p.display_name AS profile_name")
            .where("u.tenant_id = :tenantId AND u.active = :active")
            .orderBy("u.created_at DESC", "u.id ASC")
            .limit(20)
            .offset(40)
            .parameter("tenantId", "active")
            .parameterCount(2)
            .positionalParameterCount(0)
            .build();
        var result = expectation.evaluate(sql);
        assertFalse(result.matches());
        assertTrue(result.mismatches().stream().anyMatch(mismatch -> mismatch.code().equals("WHERE_NOT_REFERENCED")));
        assertTrue(result.mismatches().stream().anyMatch(mismatch -> mismatch.code().equals("PARAMETER_COUNT_MISMATCH")));
        var error = assertThrows(AssertionError.class, result::assertMatches);
        assertTrue(error.getMessage().contains("active user page with optional profiles"));
    }

    @Test
    void assertsASeparateContractForTheAllUsersMode() {
        /* SQL inspected (formatted for readability):
         * SELECT u.id, u.name, p.display_name AS profile_name
         * FROM users u
         * LEFT JOIN user_profiles p ON p.user_id = u.id
         * WHERE u.tenant_id = :tenantId
         * ORDER BY u.created_at DESC, u.id ASC
         * LIMIT 5 OFFSET 0
         * This contract intentionally includes active and inactive users.
         */
        var sql = new UserSearchQueryBuilder().page(5, 0).activeOnly(false).build();
        var expectation = Expect.sql(POSTGRESQL)
            .where("u.tenant_id = :tenantId")
            .limit(5)
            .offset(0)
            .parameter("tenantId")
            .noParameter("active")
            .parameterCount(1)
            .build();
        var result = expectation.evaluate(sql);
        assertTrue(result.matches(), result::describe);
    }
}
