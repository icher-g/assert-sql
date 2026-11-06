package io.assertsql.examples.fixtures;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Test-only SQL capture for Hibernate ORM 6.6.59.Final. Copy into application tests as needed.
 * Prefer a fresh instance registered on each session to isolate parallel tests.
 * Methods are synchronized for shared registration, but a shared capture does not separate sessions or tests.
 */
public final class HibernateSqlCapture implements StatementInspector {
    @Serial
    private static final long serialVersionUID = 1L;
    private final List<String> statements = new ArrayList<>();

    /**
     * Records SQL verbatim and returns the same String instance to Hibernate.
     *
     * @param sql non-null SQL supplied by Hibernate before JDBC statement preparation
     * @return the unchanged SQL; no parsing, filtering, or assertion occurs in the callback
     */
    @Override
    public synchronized String inspect(String sql) {
        statements.add(Objects.requireNonNull(sql, "sql"));
        return sql;
    }

    /**
     * Returns an immutable snapshot, including duplicate statements in callback order.
     * The snapshot is independent of subsequent capture or cleanup.
     *
     * @return the captured SQL texts, without JDBC bind values
     */
    public synchronized List<String> statements() {
        return List.copyOf(statements);
    }

    /**
     * Clears captured SQL before a new operation or in test cleanup.
     * For a shared capture, coordinate all writers before clearing or asserting a snapshot.
     */
    public synchronized void clear() {
        statements.clear();
    }
}
