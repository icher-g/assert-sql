package io.assertsql;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Immutable AND-combined assertions over an ordered collection of supplied SQL strings.
 * Each terminal snapshots its input, retains duplicates, and parses every statement in the collection
 * dialect before checking counts or matches. Any parse failure fails the result and skips all checks.
 * Diagnostics use zero-based statement indices. The library neither captures nor executes SQL.
 * Built declarations can be shared when custom checks in their filters are thread-safe.
 * Configuration and unexpected custom-check exceptions propagate unchanged. Incomplete evaluations
 * (PARSE_ERROR, BINDINGS_UNAVAILABLE, SQM_SCOPE_UNSUPPORTED, VALIDATION_UNSUPPORTED) always fail a rule;
 * they cannot satisfy none or a zero matching count. String collections supply no binding values.
 */
public interface CollectionExpect {
    /**
     * Evaluates a snapshot of the supplied collection.
     *
     * @param statements non-null ordered SQL strings, with no null elements
     * @return immutable diagnostics in parse order, or in declaration order after successful parsing
     * @throws IllegalArgumentException if a filter contains invalid expected SQL
     */
    Match evaluate(List<String> statements);

    /**
     * Evaluates the collection.
     *
     * @param statements non-null ordered SQL strings, with no null elements
     * @return whether all collection requirements matched
     */
    default boolean matches(List<String> statements) {
        return evaluate(Objects.requireNonNull(statements, "statements")).matches();
    }

    /**
     * Evaluates the collection and throws on mismatch.
     *
     * @param statements non-null ordered SQL strings, with no null elements
     * @throws AssertionError if any requirement or parsing fails
     */
    default void assertMatches(List<String> statements) {
        evaluate(Objects.requireNonNull(statements, "statements")).assertMatches();
    }

    /**
     * Mutable declaration builder. Counts must be non-negative. Each filtered count restricts statement
     * kind first, then applies the callback's expectation; callers need no select/update check in it.
     * WITH is classified by its outer body. Nested queries and CTE bodies are not separate statements.
     * Callbacks execute at declaration time and their non-empty expectations are snapshotted on return.
     * Parsing, filter preparation, and custom checks run only during evaluation. Builders are not thread-safe.
     * Callback filters reuse the collection's parsed SQM nodes. Advanced existing-Expect matchers evaluate
     * each SQL string in their own configured dialect, and may parse it again. All candidate elements are
     * inspected without quantifier short-circuiting, so later errors remain visible.
     */
    interface Builder {
        /**
         * Sets a description for the collection result.
         *
         * @param description non-null description
         * @return this builder
         */
        Builder as(String description);

        /**
         * Requires the exact total count, including duplicate statements and every statement kind.
         *
         * @param count non-negative statement count
         * @return this builder
         */
        Builder count(int count);

        /**
         * Requires the exact number of SELECT queries, including set queries and WITH wrapping a query.
         *
         * @param count non-negative match count
         * @return this builder
         */
        Builder selectCount(int count);

        /**
         * Requires the exact number of SELECT queries matching additional conditions.
         *
         * @param count       non-negative match count
         * @param declaration non-null callback declaring at least one additional check
         * @return this builder
         */
        Builder selectCount(int count, Consumer<Expect.Builder> declaration);

        /**
         * Requires the exact number of INSERT statements.
         *
         * @param count non-negative match count
         * @return this builder
         */
        Builder insertCount(int count);

        /**
         * Requires the exact number of INSERT statements matching additional conditions.
         *
         * @param count       non-negative match count
         * @param declaration non-null callback declaring at least one additional check
         * @return this builder
         */
        Builder insertCount(int count, Consumer<Expect.Builder> declaration);

        /**
         * Requires the exact number of UPDATE statements; zero forbids them.
         *
         * @param count non-negative match count
         * @return this builder
         */
        Builder updateCount(int count);

        /**
         * Requires the exact number of UPDATE statements matching additional conditions.
         *
         * @param count       non-negative match count
         * @param declaration non-null callback declaring at least one additional check
         * @return this builder
         */
        Builder updateCount(int count, Consumer<Expect.Builder> declaration);

        /**
         * Requires the exact number of DELETE statements.
         *
         * @param count non-negative match count
         * @return this builder
         */
        Builder deleteCount(int count);

        /**
         * Requires the exact number of DELETE statements matching additional conditions.
         *
         * @param count       non-negative match count
         * @param declaration non-null callback declaring at least one additional check
         * @return this builder
         */
        Builder deleteCount(int count, Consumer<Expect.Builder> declaration);

        /**
         * Requires the exact number of MERGE statements supported by the chosen parser.
         *
         * @param count non-negative match count
         * @return this builder
         */
        Builder mergeCount(int count);

        /**
         * Requires the exact number of MERGE statements matching additional conditions.
         *
         * @param count       non-negative match count
         * @param declaration non-null callback declaring at least one additional check
         * @return this builder
         */
        Builder mergeCount(int count, Consumer<Expect.Builder> declaration);

        /**
         * Counts whole statements matching an existing expectation, without any implicit statement-kind restriction.
         * All elements are evaluated, even after the requested count is exceeded. The expectation uses its own
         * dialect; a member's parse error always fails this rule rather than counting as a normal non-match.
         *
         * @param expectation non-null immutable expectation, including Expect.or alternatives
         * @param count       non-negative exact match count; empty collections have zero matches
         * @return this builder
         */
        Builder matchingCount(Expect expectation, int count);

        /**
         * Requires at least one whole statement to match; false for an empty collection.
         * Uses the same explicit expectation and parse-error policy as matchingCount.
         *
         * @param expectation non-null expectation, with no implicit statement-kind restriction
         * @return this builder
         */
        Builder any(Expect expectation);

        /**
         * Requires every whole statement to match; true for an empty collection.
         * Uses the same explicit expectation and parse-error policy as matchingCount.
         *
         * @param expectation non-null expectation, with no implicit statement-kind restriction
         * @return this builder
         */
        Builder all(Expect expectation);

        /**
         * Requires no whole statement to match; true for an empty collection of valid SQL.
         * Invalid SQL cannot prove absence. All elements are evaluated, so a later parse error cannot be hidden.
         *
         * @param expectation non-null expectation, with no implicit statement-kind restriction
         * @return this builder
         */
        Builder none(Expect expectation);

        /**
         * Snapshots the declaration. Later builder changes do not affect earlier expectations.
         *
         * @return immutable collection expectation
         * @throws IllegalStateException if no requirements have been declared
         */
        CollectionExpect build();
    }
}
