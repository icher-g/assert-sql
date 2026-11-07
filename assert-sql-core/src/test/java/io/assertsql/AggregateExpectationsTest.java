package io.assertsql;

import io.sqm.core.dialect.SqlDialectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

class AggregateExpectationsTest {
    @Test
    void matchesAReportingContractWithoutSnapshottingTheWholeQuery() {
        Expect.sql(POSTGRESQL)
            .select()
            .groupBy()
            .groupBy("o.customer_id")
            .having("COUNT(*) >= 2")
            .noDistinct()
            .build()
            .assertMatches("SELECT o.customer_id, COUNT(*) AS orders FROM orders o GROUP BY o.customer_id HAVING COUNT(*) >= 2");
    }

    @Test
    void presenceAndExactGroupingListsHaveDifferentContracts() {
        var sql = "SELECT country, city, COUNT(*) FROM users GROUP BY country, city";
        Expect.sql(POSTGRESQL)
            .groupBy()
            .build()
            .assertMatches(sql);
        Expect.sql(POSTGRESQL)
            .groupBy("country", "city")
            .build()
            .assertMatches(sql);
        for (var items : List.of(new String[]{"country"}, new String[]{"city", "country"}, new String[]{"country", "city", "country"})) {
            var result = Expect.sql(POSTGRESQL)
                .groupBy(items)
                .build()
                .evaluate(sql);
            assertCode(result, "GROUP_BY_MISMATCH");
            assertTrue(result.describe().contains("Expected SQL"));
        }
        var absent = Expect.sql(POSTGRESQL)
            .groupBy()
            .build()
            .evaluate("SELECT country FROM users");
        assertCode(absent, "GROUP_BY_MISSING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLLUP(country, city)", "CUBE(country, city)", "GROUPING SETS ((country), (city), ())", "(country, city)", "1"})
    void parsesGroupingExtensionsAsSingleSqmGroupItems(String item) {
        Expect.sql(POSTGRESQL)
            .groupBy(item)
            .build()
            .assertMatches("SELECT COUNT(*) FROM users GROUP BY " + item);
    }

    @Test
    void retainsGroupingStructureInsteadOfExpandingEquivalentSetsOrOrdinals() {
        var rollup = Expect.sql(POSTGRESQL)
            .groupBy("ROLLUP(country, city)")
            .build()
            .evaluate("SELECT COUNT(*) FROM users GROUP BY GROUPING SETS ((country, city), (country), ())");
        assertCode(rollup, "GROUP_BY_MISMATCH");
        var ordinal = Expect.sql(POSTGRESQL)
            .groupBy("country")
            .build()
            .evaluate("SELECT country, COUNT(*) FROM users GROUP BY 1");
        assertCode(ordinal, "GROUP_BY_MISMATCH");
    }

    @Test
    void havingMatchesTheCompletePredicateWithoutContainmentOrImplication() {
        var sql = "SELECT country, COUNT(*) FROM users GROUP BY country HAVING COUNT(*) >= 2 AND SUM(total) > 100";
        Expect.sql(POSTGRESQL)
            .having("COUNT(*) >= 2 AND SUM(total) > 100")
            .build()
            .assertMatches(sql);
        for (var predicate : List.of("COUNT(*) >= 2", "COUNT(*) >= 1 AND SUM(total) > 100", "SUM(total) > 100 AND COUNT(*) >= 2")) {
            var result = Expect.sql(POSTGRESQL)
                .having(predicate)
                .build()
                .evaluate(sql);
            assertCode(result, "HAVING_MISMATCH");
        }
        var absent = Expect.sql(POSTGRESQL)
            .having("COUNT(*) >= 2")
            .build()
            .evaluate("SELECT COUNT(*) FROM users");
        assertCode(absent, "HAVING_MISMATCH");
        assertTrue(absent.describe().contains("absent"));
    }

    @Test
    void absenceChecksRejectPresentClausesAndIncludeTheirSql() {
        var result = Expect.sql(POSTGRESQL)
            .noGroupBy()
            .noHaving()
            .noDistinct()
            .build()
            .evaluate("SELECT DISTINCT country, COUNT(*) FROM users GROUP BY country HAVING COUNT(*) > 1");
        assertCode(result, "GROUP_BY_PRESENT");
        assertCode(result, "HAVING_PRESENT");
        assertCode(result, "DISTINCT_PRESENT");
        assertTrue(result.describe().contains("GROUP BY country"));
        assertTrue(result.describe().contains("COUNT(*) > 1"));
        assertEquals(3, result.mismatches().size());
    }

    @Test
    void distinguishesPlainDistinctDistinctOnAndDistinctInsideAnAggregate() {
        var plain = Expect.sql(POSTGRESQL)
            .distinct()
            .build();
        var on = Expect.sql(POSTGRESQL)
            .distinctOn("country", "city")
            .build();
        plain.assertMatches("SELECT DISTINCT country, city FROM users");
        on.assertMatches("SELECT DISTINCT ON (country, city) country, city FROM users");
        var wrongVariant = plain.evaluate("SELECT DISTINCT ON (country) country FROM users");
        assertCode(wrongVariant, "DISTINCT_MISMATCH");
        assertTrue(wrongVariant.describe().contains("Expected SQL <DISTINCT> but was <DISTINCT ON (country)>"), wrongVariant::describe);
        assertCode(on.evaluate("SELECT DISTINCT country, city FROM users"), "DISTINCT_ON_MISMATCH");
        assertCode(on.evaluate("SELECT DISTINCT ON (city, country) country, city FROM users"), "DISTINCT_ON_MISMATCH");
        assertCode(on.evaluate("SELECT DISTINCT ON (country) country FROM users"), "DISTINCT_ON_MISMATCH");
        var absence = Expect.sql(POSTGRESQL)
            .noDistinct()
            .build();
        absence.assertMatches("SELECT COUNT(DISTINCT country) FROM users");
        assertCode(absence.evaluate("SELECT DISTINCT ON (country) country FROM users"), "DISTINCT_PRESENT");
        assertCode(plain.evaluate("SELECT COUNT(DISTINCT country) FROM users"), "DISTINCT_MISMATCH");
    }

    @Test
    void preservesDistinctOnExpressionsAndTreatsEachArgumentAsOneExpression() {
        Expect.sql(POSTGRESQL)
            .distinctOn("COALESCE(country, 'unknown')", "city")
            .build()
            .assertMatches("SELECT DISTINCT ON (COALESCE(country, 'unknown'), city) country, city FROM users");
        var commaInOneArgument = Expect.sql(POSTGRESQL)
            .distinctOn("country, city")
            .build();
        assertThrows(IllegalArgumentException.class, () -> commaInOneArgument.evaluate("SELECT country FROM users"));
        var group = Expect.sql(POSTGRESQL)
            .groupBy("country, city")
            .build();
        assertThrows(IllegalArgumentException.class, () -> group.evaluate("SELECT country FROM users"));
    }

    @Test
    void explicitScopesSelectTheAggregateQueryAndDoNotSearchSiblingBodies() {
        var sql = "WITH report AS (SELECT DISTINCT country, COUNT(*) FROM users GROUP BY country HAVING COUNT(*) >= 2) SELECT country FROM report";
        Expect.sql(POSTGRESQL)
            .noGroupBy()
            .noHaving()
            .noDistinct()
            .cte("report", query -> query
                .groupBy("country")
                .having("COUNT(*) >= 2")
                .distinct()
            )
            .build()
            .assertMatches(sql);
        var wrongLevel = Expect.sql(POSTGRESQL)
            .groupBy()
            .having("COUNT(*) >= 2")
            .distinct()
            .build()
            .evaluate(sql);
        assertEquals(3, wrongLevel.mismatches().size());
    }

    @Test
    void derivedAndExistsScopesReuseTheSameClauseVocabulary() {
        var sql = """
            SELECT r.country FROM (SELECT DISTINCT country FROM users GROUP BY country HAVING COUNT(*) > 2) r
            WHERE EXISTS (SELECT country FROM audit GROUP BY country HAVING COUNT(*) > 1)
            """;
        Expect.sql(POSTGRESQL)
            .noGroupBy()
            .noHaving()
            .noDistinct()
            .derived("r", query -> query
                .distinct()
                .groupBy("country")
                .having("COUNT(*) > 2")
            )
            .exists(query -> query
                .noDistinct()
                .groupBy("country")
                .having("COUNT(*) > 1")
            )
            .build()
            .assertMatches(sql);
    }

    @Test
    void setBranchesDoNotSupplyOuterClausesAndUnionDeduplicationIsNotSelectDistinct() {
        var sql = """
            SELECT DISTINCT country FROM users GROUP BY country HAVING COUNT(*) > 1
            UNION SELECT DISTINCT country FROM archived GROUP BY country HAVING COUNT(*) > 1
            """;
        Expect.sql(POSTGRESQL)
            .select()
            .noGroupBy()
            .noHaving()
            .noDistinct()
            .build()
            .assertMatches(sql);
        var result = Expect.sql(POSTGRESQL)
            .groupBy("country")
            .having("COUNT(*) > 1")
            .distinct()
            .build()
            .evaluate(sql);
        assertEquals(3, result.mismatches().size());
    }

    @Test
    void dmlSourcesDoNotSupplyOuterSelectClauses() {
        Expect.sql(POSTGRESQL)
            .insert()
            .noGroupBy()
            .noHaving()
            .noDistinct()
            .build()
            .assertMatches("INSERT INTO archived (country) SELECT DISTINCT country FROM users GROUP BY country HAVING COUNT(*) > 1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ansi", "mysql", "sqlserver", "oracle"})
    void unsupportedDistinctOnFailsForExpectedAndActualSql(String dialect) {
        var expected = Expect.sql(SqlDialectId.of(dialect))
            .distinctOn("country")
            .build();
        assertThrows(IllegalArgumentException.class, () -> expected.evaluate("SELECT country FROM users"));
        var actual = Expect.sql(SqlDialectId.of(dialect))
            .noDistinct()
            .build()
            .evaluate("SELECT DISTINCT ON (country) country FROM users");
        assertCode(actual, "PARSE_ERROR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ansi", "postgresql", "mysql", "sqlserver", "oracle"})
    void basicAggregateAndPlainDistinctChecksUseEachConfiguredDialect(String dialect) {
        Expect.sql(SqlDialectId.of(dialect))
            .distinct()
            .groupBy("country")
            .having("COUNT(*) >= 2")
            .build()
            .assertMatches("SELECT DISTINCT country, COUNT(*) FROM users GROUP BY country HAVING COUNT(*) >= 2");
    }

    @Test
    void invalidActualSqlAndMissingScopesCannotSatisfyNegativeChecks() {
        var calls = new AtomicInteger();
        var expectation = Expect.sql(POSTGRESQL)
            .noGroupBy()
            .noHaving()
            .noDistinct()
            .satisfies(statement -> {
                calls.incrementAndGet();
                return List.of();
            })
            .build();
        assertCode(expectation.evaluate("SELECT FROM"), "PARSE_ERROR");
        assertEquals(0, calls.get());
        var missing = Expect.sql(POSTGRESQL)
            .cte("missing", query -> query
                .noGroupBy()
                .noHaving()
                .noDistinct()
            )
            .build()
            .evaluate("SELECT country FROM users");
        assertCode(missing, "SCOPE_NOT_FOUND");
    }

    @Test
    void fragmentsAreParsedOnlyAtEvaluationAndAllArePreparedBeforeCustomChecks() {
        var calls = new AtomicInteger();
        var bad = Expect.sql(POSTGRESQL)
            .satisfies(statement -> {
                calls.incrementAndGet();
                return List.of();
            })
            .groupBy("country +")
            .having("COUNT(*) >= 2")
            .build();
        assertEquals(0, calls.get());
        assertThrows(IllegalArgumentException.class, () -> bad.evaluate("SELECT 1"));
        assertEquals(0, calls.get());
        assertCode(bad.evaluate("SELECT FROM"), "PARSE_ERROR");
        var badHaving = Expect.sql(POSTGRESQL)
            .having("COUNT(*) >")
            .build();
        assertThrows(IllegalArgumentException.class, () -> badHaving.evaluate("SELECT 1"));
    }

    @Test
    void groupInputsAndBuildersAreSnapshottedAndRepeatedCallsRemainIndependent() {
        var items = new String[]{"country", "city"};
        var builder = Expect.sql(POSTGRESQL)
            .groupBy(items);
        var first = builder.build();
        items[0] = "changed";
        builder.having("COUNT(*) > 5");
        first.assertMatches("SELECT country, city, COUNT(*) FROM users GROUP BY country, city");
        var next = builder.build();
        assertCode(next.evaluate("SELECT country, city, COUNT(*) FROM users GROUP BY country, city"), "HAVING_MISMATCH");
        var independent = Expect.sql(POSTGRESQL)
            .groupBy("country")
            .groupBy("city")
            .build()
            .evaluate("SELECT country, city FROM users GROUP BY country, city");
        assertEquals(2, independent.mismatches().size());
    }

    @Test
    void rejectsNullAndEmptyContainersWhileBlankFragmentsRemainDeferred() {
        var builder = Expect.sql(POSTGRESQL);
        assertThrows(NullPointerException.class, () -> builder.groupBy((String[]) null));
        assertThrows(NullPointerException.class, () -> builder.groupBy((String) null));
        assertThrows(IllegalArgumentException.class, () -> builder.groupBy(new String[0]));
        assertThrows(NullPointerException.class, () -> builder.having(null));
        assertThrows(NullPointerException.class, () -> builder.distinctOn((String[]) null));
        assertThrows(NullPointerException.class, () -> builder.distinctOn((String) null));
        assertThrows(IllegalArgumentException.class, builder::distinctOn);
        var blank = Expect.sql(POSTGRESQL)
            .having(" ")
            .build();
        assertThrows(IllegalArgumentException.class, () -> blank.evaluate("SELECT 1"));
    }

    @Test
    void aggregateDifferencesKeepOrScopeLabelsAndHonorRedaction() {
        var report = Expect.sql(POSTGRESQL)
            .as("report")
            .cte("totals", query -> query
                .as("minimum orders")
                .having("COUNT(*) >= 918273")
            )
            .build();
        var other = Expect.sql(POSTGRESQL)
            .as("plain report")
            .distinct()
            .build();
        var result = Expect.or(Redaction.ALL, report, other)
            .evaluate("WITH totals AS (SELECT country FROM users GROUP BY country HAVING COUNT(*) >= 827364) SELECT country FROM totals");
        assertCode(result, "HAVING_MISMATCH");
        assertTrue(result.describe().contains("CTE \"totals\""));
        assertTrue(result.describe().contains("[report] [minimum orders]"));
        assertTrue(result.describe().contains("Expected SQL"));
        assertFalse(result.describe().contains("918273"));
        assertFalse(result.describe().contains("827364"));
        var on = Expect.sql(POSTGRESQL)
            .redaction(Redaction.ALL)
            .distinctOn("COALESCE(country, 'expected-secret')")
            .build()
            .evaluate("SELECT DISTINCT ON (COALESCE(country, 'actual-secret')) country FROM users");
        assertCode(on, "DISTINCT_ON_MISMATCH");
        assertTrue(on.describe().contains("DISTINCT ON"));
        assertFalse(on.describe().contains("expected-secret"));
        assertFalse(on.describe().contains("actual-secret"));
    }

    @Test
    void aggregateExpectationsSupportConcurrentReuse() throws Exception {
        var expectation = Expect.sql(POSTGRESQL)
            .groupBy("country")
            .having("COUNT(*) >= 2")
            .noDistinct()
            .build();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = IntStream.range(0, 16)
                .mapToObj(index -> executor.submit(() -> {
                    expectation.assertMatches("SELECT country FROM users GROUP BY country HAVING COUNT(*) >= 2");
                    assertFalse(expectation.matches("SELECT country FROM users GROUP BY country HAVING COUNT(*) >= 3"));
                    return true;
                }))
                .toList();
            for (var future : futures) {
                assertTrue(future.get(10, TimeUnit.SECONDS));
            }
        }
    }

    private static void assertCode(Match result, String code) {
        assertFalse(result.matches());
        assertTrue(
            result.mismatches()
                .stream()
                .anyMatch(mismatch -> mismatch.code().equals(code)),
            result::describe
        );
    }
}
