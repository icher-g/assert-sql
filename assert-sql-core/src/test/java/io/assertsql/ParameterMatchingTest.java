package io.assertsql;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

class ParameterMatchingTest {
    @Test
    void parameterOnlyDeclarationsCoverActualSqlWithoutBindingInformation() {
        var expectation = Expect.sql(POSTGRESQL)
            .parameter("tenantId", "userName")
            .matchAllParameters()
            .build();
        expectation.assertMatches("SELECT :tenantId, :userName");
        expectation.assertMatches(ParameterizedSql.of("SELECT :tenantId, :userName"));
        var extra = expectation.evaluate("SELECT :tenantId, :userName, :status");
        assertCode(extra, "PARAMETER_UNEXPECTED", "parameters.named[status]");
        assertCode(expectation.evaluate("SELECT :tenantId"), "PARAMETER_NOT_REFERENCED", "statement.parameters");
    }

    @Test
    void parameterAndBindingDeclarationsContributeToTheSameCoverage() {
        var expectation = Expect.sql(POSTGRESQL)
            .parameter("tenantId")
            .binding("userName", "Ada")
            .matchAllParameters()
            .build();
        expectation.assertMatches(ParameterizedSql.named("SELECT :tenantId, :userName", Map.of("tenantId", 42L, "userName", "Ada")));
        var extra = ParameterizedSql.named("SELECT :tenantId, :userName, :status", Map.of("tenantId", 42L, "userName", "Ada", "status", "active"));
        assertCode(expectation.evaluate(extra), "PARAMETER_UNEXPECTED", "parameters.named[status]");
        assertCode(expectation.evaluate("SELECT :tenantId, :userName"), "BINDINGS_UNAVAILABLE", "bindings");
    }

    @Test
    void repeatedNamesAreOneCoveredSlotWhileCountsStillMeasureOccurrences() {
        var expectation = Expect.sql(POSTGRESQL)
            .parameter("id")
            .parameterCount(3)
            .matchAllParameters()
            .build();
        expectation.assertMatches("SELECT :id, :id, :id");
        var result = expectation.evaluate("SELECT :id, :id, :other");
        assertEquals(1, result.mismatches().size());
        assertCode(result, "PARAMETER_UNEXPECTED", "parameters.named[other]");
    }

    @Test
    void countAndNegativeChecksDoNotDeclareAllowedSlots() {
        var expectation = Expect.sql(POSTGRESQL)
            .parameterCount(1)
            .noParameter("absent")
            .matchAllParameters()
            .build();
        assertCode(expectation.evaluate("SELECT :id"), "PARAMETER_UNEXPECTED", "parameters.named[id]");
        var negativeBinding = Expect.sql(POSTGRESQL)
            .noBinding("id")
            .matchAllParameters()
            .build()
            .evaluate(ParameterizedSql.named("SELECT :id", Map.of()));
        assertCode(negativeBinding, "PARAMETER_UNEXPECTED", "parameters.named[id]");
        assertCode(negativeBinding, "BINDING_MISSING", "bindings.named[id]");
    }

    @Test
    void coverageIsOptInAndMatchAllPlacementDoesNotMatter() {
        Expect.sql(POSTGRESQL)
            .parameter("id")
            .build()
            .assertMatches("SELECT :id, :extra");
        var builder = Expect.sql(POSTGRESQL)
            .matchAllParameters()
            .parameter("id");
        var first = builder.build();
        builder.parameter("name");
        var second = builder.build();
        first.assertMatches("SELECT :id");
        second.assertMatches("SELECT :id, :name");
        assertCode(first.evaluate("SELECT :id, :name"), "PARAMETER_UNEXPECTED", "parameters.named[name]");
    }

    @Test
    void nestedDeclarationsCoverRootSlotsAndScopedMatchingExcludesOtherLevels() {
        var sql = "WITH ids AS (SELECT :cteId AS id) SELECT :outerId FROM ids WHERE EXISTS (SELECT :auditId)";
        var escaped = new AtomicReference<Expect.Builder>();
        var expectation = Expect.sql(POSTGRESQL)
            .matchAllParameters()
            .cte("ids", query -> {
                escaped.set(query);
                query.parameter("cteId");
            })
            .outer(query -> query
                .parameter("outerId")
                .matchAllParameters()
            )
            .exists(query -> query
                .parameter("auditId")
                .matchAllParameters()
            )
            .build();
        escaped.get().parameter("later");
        expectation.assertMatches(sql);
        expectation.assertMatches(ParameterizedSql.named(sql, Map.of("cteId", 1, "outerId", 2, "auditId", 3)));
        var undeclared = sql.replace("SELECT :auditId", "SELECT :auditId, :extra");
        assertCode(expectation.evaluate(undeclared), "PARAMETER_UNEXPECTED", "parameters.named[extra]");
    }

    @Test
    void explicitBindingsAreValidatedEvenForParameterOnlyDeclarations() {
        var expectation = Expect.sql(POSTGRESQL)
            .parameter("id")
            .matchAllParameters()
            .build();
        expectation.assertMatches("SELECT :id");
        expectation.assertMatches(ParameterizedSql.named("SELECT :id", java.util.Collections.singletonMap("id", null)));
        assertCode(expectation.evaluate(ParameterizedSql.named("SELECT :id", Map.of())), "BINDING_MISSING", "bindings.named[id]");
        var extra = ParameterizedSql.named("SELECT :id", Map.of("id", 1, "unused", "private-value"));
        var result = expectation.evaluate(extra);
        assertCode(result, "BINDING_UNEXPECTED", "bindings.named[unused]");
        assertFalse(result.describe().contains("private-value"));
    }

    @Test
    void invalidSqlFailsEvenWhenNoParametersAreDeclared() {
        var expectation = Expect.sql(POSTGRESQL)
            .matchAllParameters()
            .matchAllParameters()
            .build();
        expectation.assertMatches("SELECT 1");
        var invalid = expectation.evaluate("SELECT FROM");
        assertFalse(invalid.matches());
        assertTrue(
            invalid.mismatches()
                .stream()
                .allMatch(mismatch -> mismatch.code().equals("PARSE_ERROR"))
        );
    }

    private static void assertCode(Match result, String code, String path) {
        assertFalse(result.matches());
        assertTrue(
            result.mismatches()
                .stream()
                .anyMatch(mismatch -> mismatch.code().equals(code) && mismatch.path().equals(path)),
            result::describe
        );
    }
}
