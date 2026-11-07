package io.assertsql.examples;

import io.assertsql.Expect;
import io.assertsql.examples.fixtures.JooqReportQueries;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.Test;

import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

class ReportingExampleTest {
    @Test
    void assertsTheGroupingAndHavingContractWithoutAFullQuerySnapshot() {
        /* Actual jOOQ SQL inspected (formatted for readability):
         * SELECT o.customer_id, count(*) AS order_count FROM orders AS o
         * GROUP BY o.customer_id HAVING count(*) >= 2 ORDER BY o.customer_id
         */
        var sql = JooqReportQueries.orders(2, false)
            .getSQL(ParamType.NAMED);
        Expect.sql(POSTGRESQL)
            .as("customer order totals")
            .select()
            .table("orders o")
            .groupBy("o.customer_id")
            .having("count(*) >= 2")
            .noDistinct()
            .build()
            .assertMatches(sql);
    }

    @Test
    void identifiesTheWrongGroupingKeyInGeneratedSql() {
        /* Actual jOOQ SQL inspected (formatted for readability):
         * SELECT o.region, count(*) AS order_count FROM orders AS o
         * GROUP BY o.region HAVING count(*) >= 2 ORDER BY o.region
         * Regression: grouping by region violates the per-customer report contract.
         */
        var sql = JooqReportQueries.orders(2, true)
            .getSQL(ParamType.NAMED);
        var expectation = Expect.sql(POSTGRESQL)
            .as("customer order totals")
            .select()
            .groupBy("o.customer_id")
            .having("count(*) >= 2")
            .build();
        var error = assertThrows(AssertionError.class, () -> expectation.assertMatches(sql));
        assertTrue(error.getMessage().contains("GROUP_BY_MISMATCH"));
        assertTrue(error.getMessage().contains("Expected SQL <o.customer_id> but was <o.region>"));
    }

    @Test
    void identifiesAChangedHavingThresholdInGeneratedSql() {
        /* Actual jOOQ SQL inspected (formatted for readability):
         * SELECT o.customer_id, count(*) AS order_count FROM orders AS o
         * GROUP BY o.customer_id HAVING count(*) >= 3 ORDER BY o.customer_id
         * Regression: the minimum order count changed from 2 to 3.
         */
        var sql = JooqReportQueries.orders(3, false)
            .getSQL(ParamType.NAMED);
        var expectation = Expect.sql(POSTGRESQL)
            .as("customer order totals")
            .select()
            .groupBy("o.customer_id")
            .having("count(*) >= 2")
            .build();
        var error = assertThrows(AssertionError.class, () -> expectation.assertMatches(sql));
        assertTrue(error.getMessage().contains("HAVING_MISMATCH"));
        assertTrue(error.getMessage().contains("Expected SQL <count(*) >= 2> but was <count(*) >= 3>"));
    }

    @Test
    void distinguishesDistinctOnFromPlainDistinctInActualJooqOutput() {
        /* Actual jOOQ SQL inspected (formatted for readability):
         * SELECT DISTINCT ON (o.customer_id) o.customer_id, o.created_at
         * FROM orders AS o ORDER BY o.customer_id ASC, o.created_at DESC
         */
        var sql = JooqReportQueries.latestOrderPerCustomer()
            .getSQL(ParamType.NAMED);
        Expect.sql(POSTGRESQL)
            .as("latest order per customer")
            .select()
            .distinctOn("o.customer_id")
            .noGroupBy()
            .noHaving()
            .orderBy("o.customer_id ASC", "o.created_at DESC")
            .build()
            .assertMatches(sql);
        var wrongVariant = Expect.sql(POSTGRESQL)
            .distinct()
            .build()
            .evaluate(sql);
        assertFalse(wrongVariant.matches());
        assertEquals("DISTINCT_MISMATCH", wrongVariant.mismatches().getFirst().code());
    }
}
