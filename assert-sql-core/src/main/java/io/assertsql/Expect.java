package io.assertsql;

import io.sqm.core.dialect.SqlDialectId;

import java.util.Objects;

/**
 * An immutable SQL expectation produced by a mutable {@link Builder}.
 * Requirements are combined with AND. Evaluation parses actual SQL once and
 * collects all applicable mismatches. Parse failures skip requirement preparation.
 * Invalid expected SQL is reported after actual SQL parses successfully.
 * Built expectations support concurrent reuse when their custom checks are
 * thread-safe. SQL is never executed.
 */
public interface Expect {
    /**
     * Starts a mutable declaration builder. Neither declaration nor building
     * parses SQL or invokes checks.
     *
     * @param dialect ANSI, PostgreSQL, MySQL, SQL Server, or Oracle
     * @return a builder with a private implementation
     * @throws IllegalArgumentException if the dialect is unsupported
     */
    static Builder sql(SqlDialectId dialect) {
        return DefaultExpect.builder(Objects.requireNonNull(dialect, "dialect"));
    }

    /**
     * Combines built expectations using OR. Alternatives are evaluated in order
     * until one matches. If none matches, diagnostics identify each alternative.
     * Each evaluated alternative parses SQL in its own configured dialect.
     * Configuration and custom-check exceptions propagate from evaluated branches.
     *
     * @param expects one or more non-null expectations; defensively copied
     * @return an immutable expectation
     * @throws IllegalArgumentException if no alternatives are supplied
     */
    static Expect or(Expect... expects) {
        return OrExpect.of(expects);
    }

    /**
     * Evaluates all requirements without executing SQL.
     *
     * @param actualSql non-null SQL text
     * @return an immutable result containing all applicable diagnostics
     * @throws IllegalArgumentException if expected SQL or a declared fragment is invalid
     */
    Match evaluate(String actualSql);

    /**
     * Evaluates the expectation.
     *
     * @param actualSql non-null SQL text
     * @return whether all requirements matched
     */
    default boolean matches(String actualSql) {
        return evaluate(Objects.requireNonNull(actualSql, "actualSql")).matches();
    }

    /**
     * Evaluates the expectation and throws on mismatch.
     *
     * @param actualSql non-null SQL text
     * @throws AssertionError if SQL does not match
     */
    default void assertMatches(String actualSql) {
        evaluate(Objects.requireNonNull(actualSql, "actualSql")).assertMatches();
    }

    /**
     * Mutable declaration contract. Builders are not thread-safe.
     * Fragment varargs must contain at least one non-null string; the dedicated
     * no-argument {@link #noJoin()} and {@link #noWhere()} methods are exceptions.
     * Each supplied positive fragment must match, and each negative fragment must
     * be absent. Arguments are defensively copied during declaration and parsed
     * only during evaluation.
     */
    interface Builder {
        /**
         * Sets the description used in evaluation diagnostics.
         *
         * @param description non-null description
         * @return this builder
         */
        Builder as(String description);

        /**
         * Requires a query, including WITH queries and SELECT set operations.
         *
         * @return this builder
         */
        Builder select();

        /**
         * Requires a named table reference collected by SQM, including CTE
         * references and nested queries. Aliases alone do not count as table names.
         * An expected alias must match; omitting it allows any alias or no alias.
         * Unqualified names
         * match any schema; qualified names require the same schema. Names compare
         * case-insensitively when both identifiers are unquoted. If either is
         * quoted, their values must match exactly. This applies separately to
         * table, schema, and alias names, regardless of dialect.
         *
         * @param names one or more table references with optional aliases, without
         *              hints or modifiers; all must be present, parsed during evaluation
         * @return this builder
         */
        Builder table(String... names);

        /**
         * Forbids every supplied table reference, using {@link #table(String...)}
         * matching rules. Invalid actual SQL still fails evaluation.
         *
         * @param names one or more non-null table references
         * @return this builder
         */
        Builder noTable(String... names);

        /**
         * Requires every named SQM ColumnExpr reference anywhere in the statement,
         * including predicates, joins, and subqueries. Projection aliases, wildcards,
         * and DML target identifier lists are not ColumnExpr references.
         * An omitted qualifier matches any table qualifier;
         * an explicit qualifier must match. Identifier rules are the same as table names.
         *
         * @param names one or more column references, such as {@code id} or {@code u.id}
         * @return this builder
         */
        Builder column(String... names);

        /**
         * Forbids every supplied column reference throughout the statement.
         *
         * @param names one or more column references, using {@link #column(String...)} rules
         * @return this builder
         */
        Builder noColumn(String... names);

        /**
         * Requires every complete join fragment somewhere in the statement,
         * including nested queries. SQM model equality compares type, source,
         * condition, identifier spelling, and quoting.
         *
         * @param joins one or more fragments such as {@code LEFT JOIN orders o ON o.user_id = u.id}
         * @return this builder
         */
        Builder join(String... joins);

        /**
         * Forbids every supplied complete join fragment throughout the statement.
         *
         * @param joins one or more fragments, using {@link #join(String...)} rules
         * @return this builder
         */
        Builder noJoin(String... joins);

        /**
         * Forbids all joins, including in nested queries.
         *
         * @return this builder
         */
        Builder noJoin();

        /**
         * Requires SQM model equality with the complete outer WHERE predicate.
         * WITH queries use their body; UPDATE and DELETE use their own WHERE.
         * Multiple predicates are independent AND requirements on that same clause,
         * not fragments to merge. No predicate-subtree or semantic matching occurs.
         *
         * @param predicates one or more predicate fragments without the WHERE keyword
         * @return this builder
         */
        Builder where(String... predicates);

        /**
         * Forbids every supplied complete outer WHERE predicate. A different WHERE
         * predicate or no WHERE satisfies the requirement; subqueries are ignored.
         *
         * @param predicates one or more fragments, using {@link #where(String...)} rules
         * @return this builder
         */
        Builder noWhere(String... predicates);

        /**
         * Requires no outer WHERE predicate. Nested WHERE clauses do not count.
         *
         * @return this builder
         */
        Builder noWhere();

        /**
         * Forbids SELECT wildcard projections in all query scopes, including
         * qualified wildcards. Function arguments such as COUNT(*) are allowed.
         *
         * @return this builder
         */
        Builder noSelectStar();

        /**
         * Requires SQM model equality. Formatting, ordinary comments, and keyword
         * casing are ignored. Identifier spelling and quoting, aliases, values,
         * and ordering are preserved; no semantic normalization is performed.
         *
         * @param expectedSql non-null SQL parsed only during evaluation
         * @return this builder
         */
        Builder structurallyEquals(String expectedSql);

        /**
         * Adds a custom check receiving the parsed SQM statement.
         *
         * @param check non-null check invoked only during evaluation
         * @return this builder
         */
        Builder satisfies(Check check);

        /**
         * Takes an immutable snapshot of the declaration. Subsequent builder
         * changes do not affect previously built expectations.
         *
         * @return an immutable expectation with at least one check
         * @throws IllegalStateException if no checks have been declared
         */
        Expect build();
    }
}
