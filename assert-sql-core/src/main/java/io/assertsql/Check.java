package io.assertsql;

import io.sqm.core.Statement;

import java.util.List;

/**
 * Inspects a parsed SQM statement without executing SQL.
 */
@FunctionalInterface
public interface Check {

    /**
     * Evaluates this check against a parsed statement.
     * Implementations intended for reusable expectations must be thread-safe.
     *
     * @param statement non-null SQM statement
     * @return non-null mismatches, or an empty list on success; no null elements
     */
    List<Mismatch> evaluate(Statement statement);
}
