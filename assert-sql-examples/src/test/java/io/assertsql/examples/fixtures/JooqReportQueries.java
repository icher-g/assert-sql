package io.assertsql.examples.fixtures;

import org.jooq.Select;
import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

import static org.jooq.SQLDialect.POSTGRES;
import static org.jooq.impl.DSL.*;

/** Real jOOQ reporting queries rendered without a Connection or DataSource. */
public final class JooqReportQueries {
    private JooqReportQueries() {
    }

    /**
     * Creates a grouped order-count report with a configurable grouping key and HAVING threshold.
     * @param minimumOrders literal minimum count
     * @param byRegion whether to report per region rather than per customer
     * @return an unexecuted query
     */
    public static Select<?> orders(int minimumOrders, boolean byRegion) {
        var context = DSL.using(
            POSTGRES,
            new Settings()
                .withRenderQuotedNames(RenderQuotedNames.EXPLICIT_DEFAULT_UNQUOTED)
        );
        var key = field(name("o", byRegion ? "region" : "customer_id"));
        var orderCount = count();
        var orders = table(name("orders"))
            .as("o");
        return context.select(key, orderCount.as("order_count"))
            .from(orders)
            .groupBy(key)
            .having(orderCount.ge(inline(minimumOrders)))
            .orderBy(key);
    }

    /**
     * Creates a PostgreSQL DISTINCT ON query selecting each customer's latest order timestamp.
     * @return an unexecuted query
     */
    public static Select<?> latestOrderPerCustomer() {
        var context = DSL.using(
            POSTGRES,
            new Settings()
                .withRenderQuotedNames(RenderQuotedNames.EXPLICIT_DEFAULT_UNQUOTED)
        );
        var customer = field(name("o", "customer_id"));
        var created = field(name("o", "created_at"));
        var orders = table(name("orders"))
            .as("o");
        return context.select(customer, created)
            .distinctOn(customer)
            .from(orders)
            .orderBy(customer.asc(), created.desc());
    }
}
