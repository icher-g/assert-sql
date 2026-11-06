package io.assertsql.examples;

import io.assertsql.Expect;
import io.assertsql.ParameterizedSql;
import io.assertsql.examples.fixtures.JooqUserQueries;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

class BindingsExampleTest {
    @Test
    void matchesAllGeneratedParameterNamesWithoutSupplyingValues() {
        /* SQL inspected (formatted for readability):
         * INSERT INTO users (tenant_id, name) VALUES (:tenantId, :userName)
         * This test checks parameter names only; no binding values are supplied.
         */
        var sql = JooqUserQueries.newUser(42L, "Ada").getSQL(ParamType.NAMED);
        Expect.sql(POSTGRESQL)
            .parameter("tenantId", "userName")
            .matchAllParameters()
            .build()
            .assertMatches(sql);
    }

    @Test
    void assertsActualNamedValuesExposedByJooqWithoutExecutingSql() {
        /* SQL inspected (formatted for readability):
         * INSERT INTO users (tenant_id, name) VALUES (:tenantId, :userName)
         * Supplied bindings come from jOOQ: tenantId = 42L, userName = "Ada".
         */
        var query = JooqUserQueries.newUser(42L, "Ada");
        var values = new LinkedHashMap<String, Object>();
        query.getParams().forEach((name, parameter) -> {
            if (!parameter.isInline()) {
                values.put(name, parameter.getValue());
            }
        });
        var input = ParameterizedSql.named(query.getSQL(ParamType.NAMED), values);
        var expectation = Expect.sql(POSTGRESQL)
            .as("new user bindings")
            .table("users")
            .parameterCount(2)
            .binding("tenantId", 42L)
            .binding("userName", "Ada")
            .matchAllParameters()
            .build();
        expectation.assertMatches(input);

        // The generated SQL shape is unchanged, but the application supplied the wrong name.
        var changed = JooqUserQueries.newUser(42L, "Grace");
        var changedValues = new LinkedHashMap<String, Object>();
        changed.getParams().forEach((name, parameter) -> {
            if (!parameter.isInline()) {
                changedValues.put(name, parameter.getValue());
            }
        });
        var changedInput = ParameterizedSql.named(changed.getSQL(ParamType.NAMED), changedValues);
        var error = assertThrows(AssertionError.class, () -> expectation.assertMatches(changedInput));
        assertTrue(error.getMessage().contains("bindings.named[userName]"));
        assertTrue(error.getMessage().contains("BINDING_MISMATCH"));
        assertFalse(error.getMessage().contains("Ada"));
        assertFalse(error.getMessage().contains("Grace"));
    }

    @Test
    void assertsActualOrderedValuesExposedByJooqWithoutExecutingSql() {
        /* SQL inspected (formatted for readability):
         * INSERT INTO users (tenant_id, name) VALUES (?, ?)
         * Supplied ordered bindings come from jOOQ: [42L, "Ada"].
         * SQM traversal and jOOQ bind order agree for this VALUES clause.
         */
        var query = JooqUserQueries.newUser(42L, "Ada");
        var sql = query.getSQL(ParamType.INDEXED);
        var expectation = Expect.sql(POSTGRESQL)
            .as("new user positional bindings")
            .table("users")
            .positionalParameterCount(2)
            .positionalBinding(1, 42L)
            .positionalBinding(2, "Ada")
            .matchAllParameters()
            .build();
        expectation.assertMatches(ParameterizedSql.positional(sql, query.getBindValues()));

        // Reversing the supplied list catches an argument-order regression, even though SQL is identical.
        var reversed = ParameterizedSql.positional(sql, query.getBindValues().reversed());
        var result = expectation.evaluate(reversed);
        assertFalse(result.matches());
        assertEquals(2, result.mismatches().size());
        assertTrue(
            result.mismatches()
                .stream()
                .allMatch(mismatch -> mismatch.code().equals("BINDING_MISMATCH")),
            result::describe
        );
    }
}
