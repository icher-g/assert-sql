[Wiki home](https://github.com/icher-g/assert-sql/wiki) · [Repository](https://github.com/icher-g/assert-sql)

# Query scopes

Use `.outer(...)` and `.cte(name, ...)` to apply checks to a particular query level.
For example, the outer query below reads `active_users`; the physical `users` table
is referenced in its CTE, and `audit` is referenced in an EXISTS subquery:

```java
var sql = """
    WITH active_users AS (
        SELECT id FROM users WHERE active = true
    )
    SELECT a.id FROM active_users a
    WHERE EXISTS (SELECT 1 FROM audit x WHERE x.user_id = a.id)
    """;

Expect.sql(POSTGRESQL)
    .outer(query -> query
        .table("active_users a")
        .column("a.id")
        .noTable("users", "audit")
    )
    .cte("active_users", query -> query
        .table("users")
        .exactProjection("id")
        .where("active = true")
    )
    .build()
    .assertMatches(sql);
```

Each callback receives an independent mutable `Expect.Builder` and runs during
declaration. Its non-empty declaration is copied when the callback returns. Do not
call `build()` inside the block; the parent builds the complete expectation.
Descriptions set with `.as(...)` inside a block label that scope's diagnostics.
Changing a retained child builder later does not change the captured block.

Actual SQL is parsed once per expectation evaluation. Scope selection and traversal
use the original SQM model, with no SQL rewriting or extra resolver. Unscoped calls
retain their existing behavior, including recursive reference searches. Custom
checks in scoped blocks receive the original selected statement, including its
nested queries and WITH definitions; built-in search boundaries do not alter it.

| Check family | Behavior inside a scope block |
| --- | --- |
| `select`, `insert`, `update`, `delete`, `merge` | Check the selected body's outer kind. INSERT SELECT is an INSERT; a DML CTE body has its own kind. |
| `table`, `noTable`, `column`, `noColumn` | Search references at the selected level, including DML targets and RETURNING expressions. References in nested queries or CTE definitions do not count. CTE references are names, not expanded physical tables. |
| `join`, `noJoin`, `joinTable`, `noJoinTable` and typed join helpers | Search joins at the selected level. Complete join fragments still compare the entire join model, including its condition. |
| `noSelectStar()` | Inspects wildcard projections at this level, excluding nested queries and CTE definitions. |
| `parameter`, `noParameter`, `parameterCount`, `positionalParameterCount` | Inspect occurrences at this level, including pagination, excluding nested queries and CTE definitions. Correlated references appearing only inside a subquery remain excluded. |
| `projection`, `noProjection`, `exactProjection` | Compare complete outer SELECT items of the selected body; subqueries inside an item remain part of that comparison. Set queries use the first term's projection. |
| `groupBy`, `noGroupBy`, `having`, `noHaving`, `distinct`, `distinctOn`, `noDistinct` | Check the selected outer SELECT's clauses. Nested queries and set-operation terms cannot supply those clauses. |
| `where`, `noWhere` | Compare the complete outer WHERE of the selected body, including nested SQL inside that predicate. A set query has no single outer WHERE. |
| `orderBy`, `noOrderBy`, `limit`, `noLimit`, `offset`, `noOffset` | Inspect outer clauses of the selected body, preserving the existing `SQM_SCOPE_UNSUPPORTED` guard for ambiguous set-query clause ownership. |
| `structurallyEquals`, `satisfies` | Compare or inspect the complete selected statement, including nested SQL. An `outer` selection removes surrounding WITH wrappers; a `cte` selection retains the whole CTE body. |

A set-operation scope includes its member terms at the same search level, while
excluding subqueries and WITH definitions inside those terms. For INSERT, the target
and local expressions belong to the level; an INSERT's SELECT source is a nested query.
CTE bodies can be any statements supported by the selected SQM parser, including
PostgreSQL's data-modifying CTEs. These selectors do not add SQL semantic validation.

## CTE names and nesting

`.cte("active_users", ...)` searches only definitions in the current statement's
immediate WITH clause. Names are unqualified SQL identifiers; supply SQL quoting
when needed, for example `.cte("\"ActiveUsers\"", ...)`. Unquoted names compare
case-insensitively; if either name is quoted, their values must match exactly.
Aliases, schema qualifiers, and table modifiers are invalid selector inputs.

Nested and shadowed names are selected explicitly:

```java
Expect.sql(POSTGRESQL)
    .cte("report", report -> report
        .cte("inputs", inputs -> inputs.table("users"))
        .outer(query -> query.table("inputs"))
    )
    .build();
```

Here `report` must itself contain `WITH inputs AS (...)`. Its `inputs` definition is
independent of any equally named definition in the root WITH or other subqueries.
No descendant search, inherited-definition lookup, sibling-body traversal, or
SQL name-resolution/definition-order validation occurs. A recursive self-reference
remains a table reference; it is never expanded or revisited as a definition.

A missing CTE produces `SCOPE_NOT_FOUND`; multiple matching definitions produce
`SCOPE_AMBIGUOUS`. Both fail the block even when it contains only negative checks.
Invalid actual SQL still produces parse diagnostics and skips selection/checks.
Invalid selector syntax or expected fragments throw during evaluation after actual
SQL parses; missing selectors do not suppress declaration errors.

Diagnostics identify `outer query` or the CTE's name and one-based WITH definition
number. Nested blocks retain their scope chain, and underlying diagnostic paths
are relative to that selection. Rendered structural differences show the scope and
SQL fragments; model paths appear only in the structural fallback. SQM does not
retain SQL source spans on these nodes, so scope locations are not line/column offsets.
Scopes combine with AND like other checks and work inside `Expect.or(...)` alternatives.

The [jOOQ scope showcase](https://github.com/icher-g/assert-sql/blob/main/assert-sql-examples/src/test/java/io/assertsql/examples/JooqExampleTest.java)
demonstrates these checks on generated SQL with a CTE and a correlated EXISTS query.

## Derived tables and EXISTS subqueries

Use `.derived(alias, ...)` for a derived-table query and `.exists(...)` for an
EXISTS subquery at the current level. Each block uses the same declaration,
snapshot, and check-boundary rules as the outer/CTE blocks above:

```java
var sql = """
    SELECT page.id FROM (
        SELECT u.id FROM users u WHERE u.active = true
    ) page
    WHERE EXISTS (SELECT 1 FROM audit a WHERE a.user_id = page.id)
    """;

Expect.sql(POSTGRESQL)
    .derived("page", query -> query
        .table("users u")
        .where("u.active = true")
        .noTable("audit")
    )
    .exists(query -> query
        .table("audit a")
        .where("a.user_id = page.id")
        .column("a.user_id", "page.id")
        .noTable("users")
    )
    .build()
    .assertMatches(sql);
```

Selectors always search the current query level, even when called outside
`.outer(...)`. They do not search nested bodies, sibling bodies, or WITH definitions.
The alias must belong to a derived query in FROM/JOIN, including parser-supported
LATERAL/APPLY wrappers. A base-table alias or alias on a CTE reference does not
select a derived query. Alias parsing and matching follow the CTE identifier rules.
Two matching aliases produce `SCOPE_AMBIGUOUS`; no match produces `SCOPE_NOT_FOUND`.

`.exists(...)` requires exactly one direct EXISTS/NOT EXISTS predicate. For several
siblings, use the zero-based indexed form to select each intended body explicitly:

```java
Expect.sql(POSTGRESQL)
    .exists(0, query -> query
        .table("audit")
        .noTable("orders")
    )
    .exists(1, query -> query
        .table("orders")
        .noTable("audit")
    )
    .build();
```

Indices follow SQM visitor order within the current level, not SQL character
offsets. In a SELECT, projection expressions are visited before FROM/JOIN and
WHERE; predicates within each clause follow model traversal order. Set terms
follow their model order. A nested EXISTS is excluded until its containing query
is explicitly selected. Adding another sibling or moving predicates between
clauses can change the indices. The indexed form selects one body and does not
assert the total candidate count. Out-of-range indices produce `SCOPE_NOT_FOUND`;
negative indices are rejected during declaration. All selectors select one query,
so no implicit any/all quantification applies.

Both EXISTS and NOT EXISTS are eligible. This selector asserts the subquery's
contract, not the surrounding predicate's polarity or truth. Use a complete
`where(...)` expectation when that surrounding predicate is part of the contract.
Correlated columns such as `page.id` count as references in the selected subquery
where they appear; this does not resolve them to a catalog table or prove runtime
properties such as tenant isolation.

Deep nesting uses nested blocks, for example
`.derived("page", page -> page.exists(audit -> audit.table("events")))`.
Derived and EXISTS scopes can also contain `.cte(...)` and `.outer(...)` blocks.
Missing and ambiguous selection fails before any child check runs, including
negative-only blocks. Expected fragment/selector validation still happens during
preparation. Selection reuses the original parsed nodes; no SQL substring is reparsed.
Scalar and IN subqueries are not selected implicitly by these initial selectors.

Diagnostics retain the selector chain, for example
`derived table "page" / EXISTS subquery[0]`. The
[jOOQ nested-query showcase](https://github.com/icher-g/assert-sql/blob/main/assert-sql-examples/src/test/java/io/assertsql/examples/JooqExampleTest.java)
constructs and checks a real derived table plus correlated EXISTS query.
