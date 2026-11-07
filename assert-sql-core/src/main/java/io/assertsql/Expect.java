package io.assertsql;

import io.sqm.core.JoinKind;
import io.sqm.core.dialect.SqlDialectId;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * An immutable SQL expectation produced by a mutable {@link Builder}.
 * Requirements are combined with AND. Evaluation parses actual SQL once and
 * collects all applicable mismatches. Parse failures skip requirement preparation.
 * Invalid expected SQL is reported after actual SQL parses successfully.
 * Built expectations support concurrent reuse when their custom checks are
 * thread-safe. SQL is never executed.
 * Ordering/pagination checks return SQM_SCOPE_UNSUPPORTED for set-query models
 * whose final term carries ordering/pagination, since SQM 0.5.0 loses the
 * distinction between local and outer clauses in that shape.
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
     * Starts a declaration over an ordered collection of caller-supplied SQL strings.
     * The collection dialect parses every statement before collection requirements run.
     * No statements are captured or executed by the library.
     *
     * @param dialect supported SQM parsing dialect
     * @return a mutable collection builder with a private implementation
     * @throws IllegalArgumentException if the dialect is unsupported
     */
    static CollectionExpect.Builder statements(SqlDialectId dialect) {
        return DefaultCollectionExpect.builder(dialect);
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
     * Combines alternatives with an explicit diagnostic policy applied to every evaluated branch.
     * Matching and short-circuit order are unchanged; omitted alternatives are never evaluated.
     * @param redaction non-null policy overriding branch policies
     * @param expects non-empty immutable alternatives
     * @return an immutable OR expectation
     */
    static Expect or(Redaction redaction, Expect... expects) {
        return OrExpect.of(Objects.requireNonNull(redaction, "redaction"), expects);
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
     * Evaluates SQL and explicitly supplied bindings without executing SQL.
     * Structural checks inspect the same parsed statement; bindings are checked only when declared.
     *
     * @param input non-null SQL input
     * @return an immutable result containing all applicable diagnostics
     * @throws IllegalArgumentException if expected SQL or a declared fragment is invalid
     */
    Match evaluate(ParameterizedSql input);

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
     * Evaluates SQL and supplied bindings.
     *
     * @param input non-null SQL input
     * @return whether all requirements matched
     */
    default boolean matches(ParameterizedSql input) {
        return evaluate(Objects.requireNonNull(input, "input")).matches();
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
     * Evaluates SQL and supplied bindings and throws on mismatch.
     *
     * @param input non-null SQL input
     * @throws AssertionError if SQL or bindings do not match
     */
    default void assertMatches(ParameterizedSql input) {
        evaluate(Objects.requireNonNull(input, "input")).assertMatches();
    }

    /**
     * Mutable declaration contract. Builders are not thread-safe.
     * Fragment varargs must contain at least one non-null string; the dedicated
     * no-argument {@link #noJoin()} and {@link #noWhere()} methods are exceptions.
     * Each supplied positive fragment must match, and each negative fragment must
     * be absent. Arguments are defensively copied during declaration and parsed
     * only during evaluation.
     * Recursive-search descriptions below apply outside scope blocks. Inside
     * {@link Builder#outer(Consumer)}, {@link Builder#cte(String, Consumer)},
     * {@link Builder#derived(String, Consumer)}, and {@link Builder#exists(Consumer)},
     * reference, join, wildcard, and parameter searches stay at the selected level.
     * Set-operation terms belong to that level, excluding their nested queries and
     * WITH definitions. Complete clause comparisons and custom checks keep the
     * original selected model, including its nested SQL. INSERT SELECT sources
     * are nested queries for scoped searches; local DML/RETURNING expressions count.
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
         * Sets the root diagnostic policy. BINDINGS is the default, retaining existing SQL literal detail.
         * ALL hides SQL literals and bindings; NONE explicitly permits values. Codes and paths are unaffected.
         * Matching is unchanged. The policy covers nested scopes; configure it on their root.
         * @param redaction non-null policy
         * @return this builder
         * @throws IllegalStateException if called within a query scope
         */
        Builder redaction(Redaction redaction);

        /**
         * Declares checks on the outer body of the current statement, excluding its WITH definitions.
         * Reference, join, wildcard, and parameter searches exclude nested queries; set-operation terms
         * belong to this level. Clause comparisons and custom checks retain the complete selected model.
         * The callback runs now; its non-empty declaration is snapshotted when it returns. SQL is parsed
         * only during evaluation. Calls outside this block retain their existing behavior.
         *
         * @param declaration non-null callback using the existing assertion vocabulary
         * @return this parent builder
         * @throws IllegalStateException if the block declares no checks
         */
        Builder outer(Consumer<Builder> declaration);

        /**
         * Declares checks on a CTE body defined by the current statement's immediate WITH clause.
         * Nested definitions require nested cte blocks; no descendant or inherited-definition search
         * or recursive-reference expansion occurs. CTE names follow table identifier matching rules.
         * Missing or ambiguous selections fail even for negative checks. Searches are local to the
         * selected body, as in {@link #outer(Consumer)}; its complete model is retained for clause
         * comparisons, structural equality, custom checks, and further CTE selection.
         *
         * @param name        one unqualified SQL identifier, optionally quoted; parsed during evaluation
         * @param declaration non-null callback, snapshotted when it returns
         * @return this parent builder
         * @throws IllegalArgumentException if the name is blank, or invalid when parsed during evaluation
         * @throws IllegalStateException    if the block declares no checks
         */
        Builder cte(String name, Consumer<Builder> declaration);

        /**
         * Declares checks on the query of a derived table at the current query level, selected by alias.
         * FROM, JOIN, and supported LATERAL/APPLY wrappers are searched using SQM's model; nested query
         * bodies and WITH definitions are excluded. Alias matching follows table identifier rules.
         * Exactly one derived table must match, including when the block contains only negative checks.
         * The callback is snapshotted as in {@link #outer(Consumer)} and can select deeper query scopes.
         *
         * @param alias       one unqualified SQL identifier, optionally quoted; parsed during evaluation
         * @param declaration non-null callback declaring at least one check
         * @return this parent builder
         * @throws IllegalArgumentException if the alias is blank, or invalid when parsed during evaluation
         * @throws IllegalStateException    if the block declares no checks
         */
        Builder derived(String alias, Consumer<Builder> declaration);

        /**
         * Declares checks on the single direct EXISTS/NOT EXISTS subquery at the current query level.
         * Missing or multiple predicates produce a scope diagnostic; no sibling or descendant query
         * is selected implicitly. This selects the subquery and does not assert predicate negation.
         * Use {@link #exists(int, Consumer)} when more than one direct EXISTS predicate is present.
         *
         * @param declaration non-null callback declaring at least one check
         * @return this parent builder
         * @throws IllegalStateException if the block declares no checks
         */
        Builder exists(Consumer<Builder> declaration);

        /**
         * Declares checks on one direct EXISTS/NOT EXISTS subquery by its zero-based occurrence index.
         * Candidates follow SQM visitor order at the current query level, not SQL text offsets.
         * In a SELECT, projection items precede FROM/JOIN and WHERE; set terms follow their model order. Nested
         * subqueries and WITH definitions are excluded. The index counts EXISTS and NOT EXISTS alike.
         * An out-of-range index fails selection even for negative expectations. SQL is parsed once;
         * selected query bodies retain the complete original SQM model for custom and clause checks.
         *
         * @param index       non-negative index in the direct EXISTS predicate sequence
         * @param declaration non-null callback declaring at least one check
         * @return this parent builder
         * @throws IllegalArgumentException if the index is negative
         * @throws IllegalStateException    if the block declares no checks
         */
        Builder exists(int index, Consumer<Builder> declaration);

        /**
         * Requires an outer query, including SELECT set operations and WITH wrapping a query.
         * Nested SELECTs in DML do not change the outer statement's kind.
         *
         * @return this builder
         */
        Builder select();

        /**
         * Requires an outer INSERT, including INSERT SELECT. Nested queries do not change the kind.
         * @return this builder
         */
        Builder insert();

        /**
         * Requires an outer UPDATE. In a scope block, checks the selected body's kind.
         * @return this builder
         */
        Builder update();

        /**
         * Requires an outer DELETE, using the same outer-body rules as select().
         * @return this builder
         */
        Builder delete();

        /**
         * Requires an outer MERGE supported by the configured SQM parser.
         * @return this builder
         */
        Builder merge();

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
         * Requires joined named tables anywhere, ignoring join type and condition.
         * Only the immediate right-hand Table of a join counts, not tables inside
         * a joined derived query. Uses table name/schema/alias matching rules.
         *
         * @param names one or more table references
         * @return this builder
         */
        Builder joinTable(String... names);

        /**
         * Requires joined named tables with the specified SQM kind (ON or USING).
         * CROSS and NATURAL joins are distinct model types and do not match a kind.
         *
         * @param kind  required SQM join kind
         * @param names one or more table references
         * @return this builder
         */
        Builder joinTable(JoinKind kind, String... names);

        /**
         * Forbids joined named tables of any join type.
         *
         * @param names one or more table references
         * @return this builder
         */
        Builder noJoinTable(String... names);

        /**
         * Forbids joined named tables with the specified ON/USING join kind.
         *
         * @param kind  required SQM join kind
         * @param names one or more table references
         * @return this builder
         */
        Builder noJoinTable(JoinKind kind, String... names);

        /**
         * Requires inner-joined named tables.
         *
         * @param names one or more table references
         * @return this builder
         */
        default Builder innerJoinTable(String... names) {
            return joinTable(JoinKind.INNER, names);
        }

        /**
         * Requires left-joined named tables.
         *
         * @param names one or more table references
         * @return this builder
         */
        default Builder leftJoinTable(String... names) {
            return joinTable(JoinKind.LEFT, names);
        }

        /**
         * Requires right-joined named tables.
         *
         * @param names one or more table references
         * @return this builder
         */
        default Builder rightJoinTable(String... names) {
            return joinTable(JoinKind.RIGHT, names);
        }

        /**
         * Requires full-joined named tables.
         *
         * @param names one or more table references
         * @return this builder
         */
        default Builder fullJoinTable(String... names) {
            return joinTable(JoinKind.FULL, names);
        }

        /**
         * Requires every structural SELECT item in the outer projection, ignoring
         * list order and permitting extra items. Aliases are part of item equality.
         * WITH uses its body; set operations use their first term's projection.
         *
         * @param items one or more individual SELECT item fragments
         * @return this builder
         */
        Builder projection(String... items);

        /**
         * Forbids every structural SELECT item in the outer projection.
         *
         * @param items one or more individual SELECT item fragments
         * @return this builder
         */
        Builder noProjection(String... items);

        /**
         * Requires the complete outer projection in the given order, including
         * aliases, duplicates, and wildcards, using SQM item equality.
         *
         * @param items one or more individual SELECT item fragments
         * @return this builder
         */
        Builder exactProjection(String... items);

        /**
         * Requires any GROUP BY on the selected outer SELECT, without constraining its items.
         * Does not search CTEs, subqueries, or set-operation terms.
         * @return this builder
         */
        Builder groupBy();

        /**
         * Requires the exact ordered GROUP BY item list, using SQM equality and preserving duplicates.
         * Each argument is one item, including a complete ROLLUP, CUBE, grouping set, or GROUPING SETS.
         * No partial-list, semantic reordering, or grouping expansion is performed. Fragments are parsed only
         * during evaluation in the configured dialect. WITH uses its body; set terms and nested queries are excluded.
         * @param items one or more non-null grouping items, without GROUP BY
         * @return this builder
         */
        Builder groupBy(String... items);

        /**
         * Requires no GROUP BY on the selected outer body. Branch/nested grouping does not count.
         * @return this builder
         */
        Builder noGroupBy();

        /**
         * Requires structural equality with the complete selected outer SELECT's HAVING predicate.
         * Does not imply subtree containment, logical implication, or schema/aggregate validation.
         * @param predicate non-null complete predicate without HAVING, parsed only during evaluation
         * @return this builder
         */
        Builder having(String predicate);

        /**
         * Requires no HAVING on the selected outer body; nested and set-branch predicates are excluded.
         * @return this builder
         */
        Builder noHaving();

        /**
         * Requires plain SELECT DISTINCT. DISTINCT ON, aggregate DISTINCT, and UNION deduplication do not match.
         * @return this builder
         */
        Builder distinct();

        /**
         * Requires DISTINCT ON with the exact ordered expression list, using SQM equality.
         * Plain DISTINCT does not match. The expected clause and each expression are parsed during evaluation,
         * with SQM's dialect feature gate; no PostgreSQL semantics are invented for another dialect.
         * @param expressions one or more non-null individual expressions, without DISTINCT ON
         * @return this builder
         */
        Builder distinctOn(String... expressions);

        /**
         * Requires neither plain DISTINCT nor DISTINCT ON on the selected outer body.
         * DISTINCT in aggregate arguments or set branches does not count. A body with no outer SELECT modifier
         * satisfies this absence check; use select() as well to require a query kind.
         * @return this builder
         */
        Builder noDistinct();

        /**
         * Requires the complete outer ORDER BY list in the given order, using SQM
         * equality. Explicit ASC and omitted direction remain distinct. WITH uses
         * its body; set operations use their own ORDER BY, not a term's ordering.
         *
         * @param items one or more individual order item fragments, without ORDER BY
         * @return this builder
         */
        Builder orderBy(String... items);

        /**
         * Requires no outer ORDER BY clause.
         *
         * @return this builder
         */
        Builder noOrderBy();

        /**
         * Requires a literal outer row limit. Ordinary SQL Server TOP counts match;
         * TOP PERCENT, WITH TIES, LIMIT ALL, parameters, and absence do not.
         *
         * @param count non-negative row count
         * @return this builder
         */
        Builder limit(long count);

        /**
         * Requires no outer limit specification, including TOP or LIMIT ALL.
         *
         * @return this builder
         */
        Builder noLimit();

        /**
         * Requires a literal outer offset. Absence is different from explicit zero.
         *
         * @param count non-negative row count
         * @return this builder
         */
        Builder offset(long count);

        /**
         * Requires no outer offset specification.
         *
         * @return this builder
         */
        Builder noOffset();

        /**
         * Requires named parameters anywhere in the statement. Names are canonical
         * (no prefix) and case-sensitive, unlike column/table identifier matching.
         *
         * @param names one or more non-blank parameter names
         * @return this builder
         */
        Builder parameter(String... names);

        /**
         * Forbids the supplied canonical named parameters throughout the statement.
         *
         * @param names one or more non-blank parameter names
         * @return this builder
         */
        Builder noParameter(String... names);

        /**
         * Requires the total number of named, anonymous, and ordinal placeholder
         * occurrences. Repeated names/ordinals count repeatedly; bindings are not inspected.
         *
         * @param count non-negative occurrence count
         * @return this builder
         */
        Builder parameterCount(int count);

        /**
         * Requires the number of anonymous and ordinal placeholder occurrences.
         *
         * @param count non-negative positional occurrence count
         * @return this builder
         */
        Builder positionalParameterCount(int count);

        /**
         * Requires a referenced named slot and a supplied value equal to the expected value.
         * Extra bindings and unrelated missing bindings are permitted unless {@link #matchAllParameters()} is declared.
         * Names are canonical and case-sensitive. Explicit null is distinct from absence.
         * Expected arrays and Date values are snapshotted now; see {@link ParameterizedSql} for ownership and equality.
         * Diagnostics never include expected or supplied values. SQL-only input fails with BINDINGS_UNAVAILABLE.
         *
         * @param name  canonical parameter name without a prefix
         * @param value expected value, including null
         * @return this builder
         */
        Builder binding(String name, Object value);

        /**
         * Requires a supplied value at a referenced anonymous slot, using {@link #binding(String, Object)} value rules.
         * Positions follow SQM collector traversal over the full statement, not driver bind order or source offsets.
         * Named and ordinal parameters do not consume positions. Scoped blocks retain the full-statement numbering.
         *
         * @param position positive one-based anonymous position
         * @param value    expected value, including null
         * @return this builder
         */
        Builder positionalBinding(int position, Object value);

        /**
         * Requires a supplied value at a referenced explicit ordinal slot, using {@link #binding(String, Object)} value rules.
         * Repeated SQL indices share a binding; sparse indices do not imply bindings for skipped indices.
         *
         * @param index positive index written in SQL, such as 2 for $2
         * @param value expected value, including null
         * @return this builder
         */
        Builder ordinalBinding(int index, Object value);

        /**
         * Forbids a supplied named binding, whether or not SQL references it. A supplied null still fails.
         * Requires valid SQL and explicit binding containers; SQL-only input fails rather than proving absence.
         * Scope blocks still inspect the full supplied binding container.
         *
         * @param name canonical parameter name
         * @return this builder
         */
        Builder noBinding(String name);

        /**
         * Forbids a supplied anonymous position, following {@link #noBinding(String)} absence rules.
         *
         * @param position positive full-statement anonymous position
         * @return this builder
         */
        Builder noPositionalBinding(int position);

        /**
         * Forbids a supplied explicit ordinal slot, following {@link #noBinding(String)} absence rules.
         *
         * @param index positive SQL ordinal index
         * @return this builder
         */
        Builder noOrdinalBinding(int index);

        /**
         * Requires every actual SQL parameter slot to be covered by a positive parameter or binding expectation.
         * Declarations in this builder and its nested scopes contribute coverage; negative and occurrence-count
         * checks do not. Names and ordinals identify distinct slots; each anonymous occurrence has its own slot.
         * Without positive declarations, requires no parameters. Prefer parameterCount(0) to express that directly.
         * SQL-only evaluation is supported. Value checks still require supplied bindings. When explicit binding
         * containers are supplied, an unscoped matchAllParameters also rejects missing or unused bindings.
         * In a query scope, coverage is checked only at the selected level, retaining global anonymous positions;
         * supplied bindings elsewhere in the input are allowed. Invalid SQL still fails before any checks run.
         * Placement in the fluent chain does not matter; build snapshots the complete declaration.
         *
         * @return this builder
         */
        Builder matchAllParameters();

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
         * Mismatches show enclosing SQL rendered in this dialect. Model paths
         * and values are included only as a fallback if rendering is unavailable.
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
