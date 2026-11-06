package io.assertsql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

class BindingsTest {
    @Test
    void checksNamedValuesAndReusesOneSlotForRepeatedOccurrences() {
        var sql = "SELECT :tenant, :tenant, :name";
        var expectation = Expect.sql(POSTGRESQL)
            .parameterCount(3)
            .binding("tenant", 42L)
            .binding("name", "Ada")
            .matchAllParameters()
            .build();
        expectation.assertMatches(ParameterizedSql.named(sql, Map.of("tenant", 42L, "name", "Ada")));
        var wrong = expectation.evaluate(ParameterizedSql.named(sql, Map.of("tenant", 42L, "name", "Grace")));
        assertCode(wrong, "BINDING_MISMATCH", "bindings.named[name]");
        assertFalse(wrong.describe().contains("Ada"));
        assertFalse(wrong.describe().contains("Grace"));
        assertCode(expectation.evaluate(ParameterizedSql.named(sql, Map.of("tenant", 42L))), "BINDING_MISSING", "bindings.named[name]");
    }

    @Test
    void checksAnonymousValuesInOrderAndCatchesReversedBindings() {
        var sql = "SELECT ?, ?";
        var expectation = Expect.sql(POSTGRESQL)
            .positionalBinding(1, 42L)
            .positionalBinding(2, "Ada")
            .matchAllParameters()
            .build();
        expectation.assertMatches(ParameterizedSql.positional(sql, List.of(42L, "Ada")));
        var reversed = expectation.evaluate(ParameterizedSql.positional(sql, List.of("Ada", 42L)));
        assertEquals(2, reversed.mismatches().size());
        assertCode(reversed, "BINDING_MISMATCH", "bindings.positional[1]");
        assertCode(reversed, "BINDING_MISMATCH", "bindings.positional[2]");
        assertCode(expectation.evaluate(ParameterizedSql.positional(sql, List.of(42L))), "BINDING_MISSING", "bindings.positional[2]");
    }

    @Test
    void ordinalIndicesAreNotOccurrencesOrAnonymousPositions() {
        var sql = "SELECT $2, $2, $5";
        var expectation = Expect.sql(POSTGRESQL)
            .ordinalBinding(2, "two")
            .ordinalBinding(5, "five")
            .parameterCount(3)
            .matchAllParameters()
            .build();
        expectation.assertMatches(ParameterizedSql.ordinal(sql, Map.of(5, "five", 2, "two")));
        var missing = expectation.evaluate(ParameterizedSql.ordinal(sql, Map.of(2, "two")));
        assertCode(missing, "BINDING_MISSING", "bindings.ordinal[5]");
        var extra = expectation.evaluate(ParameterizedSql.ordinal(sql, Map.of(1, "unused", 2, "two", 5, "five")));
        assertCode(extra, "BINDING_UNEXPECTED", "bindings.ordinal[1]");
        assertCode(expectation.evaluate(ParameterizedSql.positional(sql, List.of("two", "two", "five"))), "BINDING_MISSING", "bindings.ordinal[2]");
    }

    @Test
    void mixedParameterKindsKeepIndependentBindingNamespaces() {
        var sql = "SELECT :id, ?, $1, :id, ?, $1";
        var input = ParameterizedSql.of(sql, Map.of("id", 42L), List.of("first", "second"), Map.of(1, "ordinal"));
        Expect.sql(POSTGRESQL)
            .binding("id", 42L)
            .positionalBinding(1, "first")
            .positionalBinding(2, "second")
            .ordinalBinding(1, "ordinal")
            .parameterCount(6)
            .positionalParameterCount(4)
            .matchAllParameters()
            .build()
            .assertMatches(input);
    }

    @Test
    void nullIsSuppliedRatherThanMissingInEveryNamespace() {
        var named = new LinkedHashMap<String, Object>();
        named.put("id", null);
        var ordinal = new LinkedHashMap<Integer, Object>();
        ordinal.put(2, null);
        var expectation = Expect.sql(POSTGRESQL)
            .binding("id", null)
            .positionalBinding(1, null)
            .ordinalBinding(2, null)
            .matchAllParameters()
            .build();
        expectation.assertMatches(ParameterizedSql.of("SELECT :id, ?, $2", named, Collections.singletonList(null), ordinal));
        var absent = expectation.evaluate(ParameterizedSql.of("SELECT :id, ?, $2", Map.of(), List.of(), Map.of()));
        assertEquals(6, absent.mismatches().size()); // Individual checks and the complete contract each report the three missing slots.
        assertTrue(
            absent.mismatches()
                .stream()
                .allMatch(mismatch -> mismatch.code().equals("BINDING_MISSING"))
        );
        var negative = Expect.sql(POSTGRESQL)
            .noBinding("id")
            .noPositionalBinding(1)
            .noOrdinalBinding(2)
            .build();
        var negativeResult = negative.evaluate(ParameterizedSql.of("SELECT :id, ?, $2", named, Collections.singletonList(null), ordinal));
        assertEquals(3, negativeResult.mismatches().size());
    }

    @Test
    void individualChecksPermitOtherSlotsWhileCompleteContractRejectsExtras() {
        var input = ParameterizedSql.named("SELECT :id, :other", Map.of("id", 42L, "unused", "private-value"));
        Expect.sql(POSTGRESQL)
            .binding("id", 42L)
            .build()
            .assertMatches(input);
        var complete = Expect.sql(POSTGRESQL)
            .parameter("id", "other")
            .matchAllParameters()
            .build()
            .evaluate(input);
        assertCode(complete, "BINDING_MISSING", "bindings.named[other]");
        assertCode(complete, "BINDING_UNEXPECTED", "bindings.named[unused]");
        assertFalse(complete.describe().contains("private-value"));
        var noParameter = Expect.sql(POSTGRESQL)
            .binding("unused", "private-value")
            .build()
            .evaluate(input);
        assertCode(noParameter, "PARAMETER_NOT_REFERENCED", "bindings.named[unused]");
    }

    @Test
    void matchAllWithoutDeclarationsRequiresNoParametersWithOrWithoutBindingInformation() {
        var expectation = Expect.sql(POSTGRESQL)
            .matchAllParameters()
            .build();
        expectation.assertMatches(ParameterizedSql.named("SELECT 1", Map.of()));
        expectation.assertMatches(ParameterizedSql.positional("SELECT 1", List.of()));
        expectation.assertMatches("SELECT 1");
        expectation.assertMatches(ParameterizedSql.of("SELECT 1"));
        assertCode(expectation.evaluate("SELECT :id"), "PARAMETER_UNEXPECTED", "parameters.named[id]");
        assertCode(expectation.evaluate("SELECT ?, ?"), "PARAMETER_UNEXPECTED", "parameters.positional[2]");
        assertCode(expectation.evaluate(ParameterizedSql.named("SELECT 1", Map.of("unused", 42))), "BINDING_UNEXPECTED", "bindings.named[unused]");
        var structural = Expect.sql(POSTGRESQL)
            .parameter("id")
            .parameterCount(1)
            .build();
        structural.assertMatches("SELECT :id");
        structural.assertMatches(ParameterizedSql.positional("SELECT :id", List.of("unrelated")));
    }

    @Test
    void absenceChecksRequireExplicitBindingsAndValidSql() {
        var expectation = Expect.sql(POSTGRESQL)
            .noBinding("secret")
            .noPositionalBinding(1)
            .noOrdinalBinding(1)
            .build();
        expectation.assertMatches(ParameterizedSql.named("SELECT 1", Map.of()));
        assertEquals(3, expectation.evaluate("SELECT 1").mismatches().size());
        var invalid = expectation.evaluate(ParameterizedSql.named("SELECT FROM", Map.of()));
        assertFalse(invalid.matches());
        assertTrue(
            invalid.mismatches()
                .stream()
                .allMatch(mismatch -> mismatch.code().equals("PARSE_ERROR"))
        );
        var supplied = ParameterizedSql.of("SELECT 1", Map.of("secret", "hidden"), List.of(123), Map.of(1, 456));
        var result = expectation.evaluate(supplied);
        assertEquals(3, result.mismatches().size());
        assertTrue(
            result.mismatches()
                .stream()
                .allMatch(mismatch -> mismatch.code().equals("BINDING_UNEXPECTED"))
        );
    }

    @Test
    void valuesCompareDeeplyWithoutNumericConversions() {
        var expectation = Expect.sql(POSTGRESQL)
            .binding("value", new Object[]{new int[]{1, 2}, new String[]{"a", null}})
            .build();
        expectation.assertMatches(ParameterizedSql.named("SELECT :value", Map.of("value", new Object[]{new int[]{1, 2}, new String[]{"a", null}})));
        var differentValues = ParameterizedSql.named("SELECT :value", Map.of("value", new Object[]{new int[]{2, 1}, new String[]{"a", null}}));
        assertFalse(expectation.matches(differentValues));
        assertFalse(
            Expect.sql(POSTGRESQL)
                .binding("n", new int[]{1})
                .build()
                .matches(ParameterizedSql.named("SELECT :n", Map.of("n", new long[]{1})))
        );
        assertFalse(
            Expect.sql(POSTGRESQL)
                .binding("n", 42)
                .build()
                .matches(ParameterizedSql.named("SELECT :n", Map.of("n", 42L)))
        );
        var decimal = Expect.sql(POSTGRESQL)
            .binding("n", new BigDecimal("1.0"))
            .build();
        assertFalse(decimal.matches(ParameterizedSql.named("SELECT :n", Map.of("n", new BigDecimal("1.00")))));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void copiesContainersArraysDatesAndExpectedValuesOnDeclarationAndAccess() {
        var bytes = new byte[]{1, 2};
        var date = new Date(123);
        var nested = new Object[]{bytes, date};
        var named = new LinkedHashMap<String, Object>();
        named.put("value", nested);
        var positional = new ArrayList<>(List.of((Object) nested));
        var ordinal = new LinkedHashMap<Integer, Object>();
        ordinal.put(3, nested);
        var input = ParameterizedSql.of("SELECT :value, ?, $3", named, positional, ordinal);
        var builder = Expect.sql(POSTGRESQL)
            .binding("value", nested)
            .ordinalBinding(3, nested);
        var expectation = builder.positionalBinding(1, nested)
            .matchAllParameters()
            .build();
        named.clear();
        positional.clear();
        ordinal.clear();
        bytes[0] = 99;
        date.setTime(999);
        ((byte[]) ((Object[]) input.namedBindings().get("value"))[0])[0] = 88;
        ((Date) ((Object[]) input.ordinalBindings().get(3))[1]).setTime(888);
        ((byte[]) ((Object[]) input.positionalBindings().getFirst())[0])[0] = 77;
        expectation.assertMatches(input);
        assertThrows(UnsupportedOperationException.class, () -> input.namedBindings().clear());
        assertThrows(UnsupportedOperationException.class, () -> input.positionalBindings().clear());
        assertThrows(UnsupportedOperationException.class, () -> input.ordinalBindings().clear());
        builder.binding("other", 7);
        expectation.assertMatches(input);
    }

    @Test
    void scopesUseGlobalAnonymousPositionsAndNeverRenumberSelectedBodies() {
        var sql = "WITH ids AS (SELECT ? AS id) SELECT ? FROM ids WHERE EXISTS (SELECT ? WHERE ? = 1)";
        var expectation = Expect.sql(POSTGRESQL)
            .matchAllParameters()
            .cte("ids", query -> query.positionalBinding(1, "cte"))
            .outer(query -> query.positionalBinding(2, "outer"))
            .exists(query -> query
                .positionalBinding(3, "projection")
                .positionalBinding(4, 1)
            )
            .build();
        var input = ParameterizedSql.positional(sql, List.of("cte", "outer", "projection", 1));
        expectation.assertMatches(input);
        var wrongScope = Expect.sql(POSTGRESQL)
            .outer(query -> query.positionalBinding(1, "cte"))
            .build()
            .evaluate(input);
        assertCode(wrongScope, "PARAMETER_NOT_REFERENCED", "outer query / bindings.positional[1]");
        var emptyOuterContract = Expect.sql(POSTGRESQL)
            .outer(Expect.Builder::matchAllParameters)
            .build()
            .evaluate(input);
        assertCode(emptyOuterContract, "PARAMETER_UNEXPECTED", "outer query / parameters.positional[2]");
        var missingScope = Expect.sql(POSTGRESQL)
            .cte("missing", query -> query.noBinding("absent"))
            .build()
            .evaluate(input);
        assertTrue(
            missingScope.mismatches()
                .stream()
                .anyMatch(mismatch -> mismatch.code().equals("SCOPE_NOT_FOUND"))
        );
    }

    @Test
    void anonymousContractUsesSqmTraversalRatherThanGuessingDriverPaginationOrder() {
        // SQL text presents OFFSET before FETCH, but SQM visits limit before offset.
        var sql = "SELECT ? OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        var expectation = Expect.sql(POSTGRESQL)
            .positionalBinding(1, "projection")
            .positionalBinding(2, 20)
            .positionalBinding(3, 40)
            .matchAllParameters()
            .build();
        expectation.assertMatches(ParameterizedSql.positional(sql, List.of("projection", 20, 40)));
        assertFalse(expectation.matches(ParameterizedSql.positional(sql, List.of("projection", 40, 20))));
    }

    @Test
    void namedMatchingIsCaseSensitiveAndDoesNotUseIdentifierRules() {
        var expectation = Expect.sql(POSTGRESQL)
            .matchAllParameters()
            .build();
        var result = expectation.evaluate(ParameterizedSql.named("SELECT :Id", Map.of("id", 42)));
        assertCode(result, "BINDING_MISSING", "bindings.named[Id]");
        assertCode(result, "BINDING_UNEXPECTED", "bindings.named[id]");
    }

    @Test
    void orBranchesRetainInputAndThrowingTerminalsKeepDiagnosticsRedacted() {
        var first = Expect.sql(POSTGRESQL)
            .binding("name", "Ada")
            .build();
        var second = Expect.sql(POSTGRESQL)
            .binding("name", "Grace")
            .build();
        var either = Expect.or(first, second);
        either.assertMatches(ParameterizedSql.named("SELECT :name", Map.of("name", "Grace")));
        assertTrue(either.matches(ParameterizedSql.named("SELECT :name", Map.of("name", "Ada"))));
        var input = ParameterizedSql.named("SELECT :name", Map.of("name", "sensitive-user"));
        var error = assertThrows(AssertionError.class, () -> either.assertMatches(input));
        assertTrue(error.getMessage().contains("or[1].bindings.named[name]"));
        assertFalse(error.getMessage().contains("sensitive-user"));
        assertFalse(error.getMessage().contains("Ada"));
        assertFalse(input.toString().contains("sensitive-user"));
        assertFalse(input.toString().contains(":name"));
    }

    @Test
    void concurrentReuseKeepsBindingStateIsolatedPerEvaluation() throws Exception {
        var expectation = Expect.sql(POSTGRESQL)
            .binding("id", 42L)
            .matchAllParameters()
            .build();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = IntStream.range(0, 24)
                .mapToObj(i -> executor.submit(() -> {
                    expectation.assertMatches(ParameterizedSql.named("SELECT :id", Map.of("id", 42L)));
                    assertFalse(expectation.matches(ParameterizedSql.named("SELECT :id", Map.of("id", (long) i))));
                    assertFalse(expectation.matches(ParameterizedSql.named("not SQL", Map.of("id", 42L))));
                    return true;
                }))
                .toList();
            for (var future : futures) {
                assertTrue(future.get(10, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ":id", "@id", "$1", "?", "with space"})
    void rejectsNonCanonicalNames(String name) {
        assertThrows(IllegalArgumentException.class, () -> ParameterizedSql.named("SELECT 1", Map.of(name, 1)));
        assertThrows(
            IllegalArgumentException.class,
            () -> Expect.sql(POSTGRESQL)
                .binding(name, 1)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> Expect.sql(POSTGRESQL)
                .noBinding(name)
        );
    }

    @Test
    void rejectsInvalidPositionsNullInputsAndCyclicArrays() {
        assertThrows(IllegalArgumentException.class, () -> ParameterizedSql.ordinal("SELECT 1", Map.of(0, 1)));
        assertThrows(
            IllegalArgumentException.class,
            () -> Expect.sql(POSTGRESQL)
                .positionalBinding(0, 1)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> Expect.sql(POSTGRESQL)
                .ordinalBinding(-1, 1)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> Expect.sql(POSTGRESQL)
                .noPositionalBinding(0)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> Expect.sql(POSTGRESQL)
                .noOrdinalBinding(0)
        );
        assertThrows(NullPointerException.class, () -> ParameterizedSql.of(null));
        assertThrows(NullPointerException.class, () -> ParameterizedSql.named("SELECT 1", null));
        assertThrows(NullPointerException.class, () -> ParameterizedSql.positional("SELECT 1", null));
        assertThrows(NullPointerException.class, () -> ParameterizedSql.ordinal("SELECT 1", null));
        assertThrows(
            NullPointerException.class,
            () -> Expect.sql(POSTGRESQL)
                .binding(null, 1)
        );
        var expectation = Expect.sql(POSTGRESQL)
            .matchAllParameters()
            .build();
        assertThrows(NullPointerException.class, () -> expectation.evaluate((ParameterizedSql) null));
        assertThrows(NullPointerException.class, () -> expectation.matches((ParameterizedSql) null));
        assertThrows(NullPointerException.class, () -> expectation.assertMatches((ParameterizedSql) null));
        assertThrows(
            NullPointerException.class,
            () -> Expect.or(expectation)
                .evaluate((ParameterizedSql) null)
        );
        var cyclic = new Object[1];
        cyclic[0] = cyclic;
        assertThrows(IllegalArgumentException.class, () -> ParameterizedSql.named("SELECT :value", Map.of("value", cyclic)));
        assertThrows(
            IllegalArgumentException.class,
            () -> Expect.sql(POSTGRESQL)
                .binding("value", cyclic)
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
