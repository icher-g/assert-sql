package io.assertsql.examples;

import io.assertsql.Expect;
import io.assertsql.Redaction;
import io.assertsql.examples.fixtures.JooqUserQueries;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.Test;

import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticsExampleTest {
    @Test
    void keepsNamedOrAlternativesAndCteScopeVisibleWhileHidingSqlLiterals() {
        /* Actual jOOQ SQL inspected (formatted for readability):
         * WITH active_users(id) AS (SELECT u.id FROM users AS u WHERE u.active = true)
         * SELECT a.id FROM active_users AS a
         * WHERE EXISTS (SELECT 1 FROM audit AS x WHERE x.user_id = a.id)
         */
        var sql = JooqUserQueries.activeUsersWithAudit().getSQL(ParamType.NAMED);
        var activeFlag = Expect.sql(POSTGRESQL)
            .as("active flag contract")
            .cte("active_users", query -> query
                .as("CTE filter")
                .structurallyEquals("SELECT u.id FROM users AS u WHERE u.active = false")
            )
            .build();
        var tenantParameter = Expect.sql(POSTGRESQL)
            .as("tenant contract")
            .parameter("tenantId")
            .build();
        var result = Expect.or(Redaction.ALL, activeFlag, tenantParameter)
            .evaluate(sql);
        var error = assertThrows(AssertionError.class, result::assertMatches);
        assertTrue(error.getMessage().contains("or[0].CTE \"active_users\" (WITH item 1)"));
        assertTrue(error.getMessage().contains("[active flag contract] [CTE filter]"));
        assertTrue(error.getMessage().contains("[tenant contract]"));
        assertTrue(error.getMessage().contains("Expected SQL"));
        assertFalse(error.getMessage().contains("true"));
        assertFalse(error.getMessage().contains("false"));
        assertEquals("or[0].CTE \"active_users\" (WITH item 1)", result.mismatches().get(1).path());
        assertEquals(java.util.List.of("active flag contract", "CTE filter"), result.mismatches().get(1).descriptions());
    }
}
