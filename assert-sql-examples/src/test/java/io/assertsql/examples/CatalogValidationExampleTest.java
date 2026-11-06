package io.assertsql.examples;

import io.assertsql.Expect;
import io.assertsql.examples.fixtures.JooqUserQueries;
import io.assertsql.validation.SchemaChecks;
import io.sqm.catalog.model.CatalogColumn;
import io.sqm.catalog.model.CatalogSchema;
import io.sqm.catalog.model.CatalogTable;
import io.sqm.catalog.model.CatalogType;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.Test;

import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

class CatalogValidationExampleTest {
    @Test
    void validatesGeneratedSqlIncludingCteAndCorrelatedReferencesWithoutADatabase() {
        /* SQL inspected (formatted for readability):
         * WITH active_users(id) AS (
         *     SELECT u.id FROM users AS u WHERE u.active = true
         * )
         * SELECT a.id FROM active_users AS a
         * WHERE EXISTS (SELECT 1 FROM audit AS x WHERE x.user_id = a.id)
         */
        var schema = CatalogSchema.of(
            CatalogTable.of("app", "users", CatalogColumn.of("id", CatalogType.LONG), CatalogColumn.of("active", CatalogType.BOOLEAN)),
            CatalogTable.of("app", "audit", CatalogColumn.of("user_id", CatalogType.LONG))
        );
        var sql = JooqUserQueries.activeUsersWithAudit().getSQL(ParamType.NAMED);
        var expectation = Expect.sql(POSTGRESQL)
            .as("active users with valid catalog references")
            .satisfies(SchemaChecks.validAgainst(schema, POSTGRESQL))
            .cte("active_users", query -> query.table("users u").where("u.active = true"))
            .build();
        expectation.assertMatches(sql);
    }

    @Test
    void reportsAColumnMissingFromTheSuppliedCatalog() {
        /* SQL inspected (formatted for readability):
         * WITH active_users(id) AS (
         *     SELECT u.id FROM users AS u WHERE u.active = true
         * )
         * SELECT a.id FROM active_users AS a
         * WHERE EXISTS (SELECT 1 FROM audit AS x WHERE x.user_id = a.id)
         * Catalog mismatch: audit has id, but no user_id column.
         */
        var schema = CatalogSchema.of(
            CatalogTable.of("app", "users", CatalogColumn.of("id", CatalogType.LONG), CatalogColumn.of("active", CatalogType.BOOLEAN)),
            CatalogTable.of("app", "audit", CatalogColumn.of("id", CatalogType.LONG))
        );
        var sql = JooqUserQueries.activeUsersWithAudit().getSQL(ParamType.NAMED);
        var expectation = Expect.sql(POSTGRESQL)
            .as("audit schema contract")
            .satisfies(SchemaChecks.validAgainst(schema, POSTGRESQL))
            .build();
        var result = expectation.evaluate(sql);
        assertFalse(result.matches());
        assertTrue(result.mismatches().stream().anyMatch(mismatch -> mismatch.code().equals("COLUMN_NOT_FOUND")), result::describe);
        var error = assertThrows(AssertionError.class, () -> expectation.assertMatches(sql));
        assertTrue(error.getMessage().contains("COLUMN_NOT_FOUND"));
        assertTrue(error.getMessage().contains("user_id"));
    }
}
