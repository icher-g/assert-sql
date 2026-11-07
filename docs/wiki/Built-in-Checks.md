[Wiki home](https://github.com/icher-g/assert-sql/wiki) · [Repository](https://github.com/icher-g/assert-sql)

# Built-in checks

For GROUP BY, HAVING, and DISTINCT, see [reporting queries and statement kinds](https://github.com/icher-g/assert-sql/wiki/Reporting-Queries-and-Statement-Kinds).
Checks inside a selected query follow the [scope contracts](https://github.com/icher-g/assert-sql/wiki/Query-Scopes).

- `select()`, `insert()`, `update()`, `delete()`, and `merge()` check the outer
  statement kind. WITH uses its body; SELECT set operations are queries, while
  SELECT sources/subqueries inside DML do not change the outer kind.
- `table(String... names)` uses SQM's `TableRefsCollector` and filters its results
  to named `Table` nodes. This includes CTE references, joins, subqueries, CTE
  definitions, and DML targets. Aliases, derived tables, and function tables do
  not count as named tables. An unqualified name matches any schema; a qualified
  name requires the specified schema. An expected alias, such as `"Users AS u"`
  or `"Users u"`, must match the alias on that same table reference. Omitting an
  expected alias allows any alias or no alias. References are parsed during evaluation. This
  checks model references, without resolving CTEs to physical database tables.
- `column(String... names)` finds SQM `ColumnExpr` references anywhere in the
  statement, including expressions, predicates, joins, and nested queries. An
  unqualified name matches any table qualifier; a qualified name requires that
  qualifier. Identifier matching follows the same rules as table names. Projection
  aliases, wildcards, and DML target identifier lists are not column expressions.
- `join(String... joins)` matches complete join fragments using SQM model equality,
  including join type, right-side source, and condition. It searches all query
  scopes. For example, `join("LEFT JOIN orders o ON o.user_id = u.id")`.
- `where(String... predicates)` matches the complete outer WHERE predicate using
  SQM model equality. Supply predicates without the `WHERE` keyword. WITH queries
  inspect their body, and UPDATE/DELETE inspect their own WHERE. A predicate found
  only in a subquery does not match; parts of an AND/OR predicate do not match the
  complete predicate. Join and WHERE equality preserve identifier spelling.
- `noTable(...)`, `noColumn(...)`, `noJoin(...)`, and `noWhere(...)` require each
  supplied fragment to be absent, using their positive counterpart's matching
  rules. `noJoin()` forbids all joins, including nested ones. `noWhere()` requires
  no outer WHERE; WHERE clauses inside subqueries are allowed. A different outer
  WHERE satisfies `noWhere("id = 1")`, whereas `noWhere()` rejects any outer WHERE.
- `noSelectStar()` rejects both `*` and qualified wildcard projections
  in every query scope. `COUNT(*)` is allowed.
- `joinTable(String... names)` checks immediate named table sources of joins in
  all query scopes, without fixing the ON/USING condition. `innerJoinTable`,
  `leftJoinTable`, `rightJoinTable`, and `fullJoinTable` also require the join kind.
  `joinTable(JoinKind, String...)` exposes SQM's kinds directly, including STRAIGHT.
  ON and USING joins can match a kind; CROSS/NATURAL joins match the untyped
  `joinTable` check. `noJoinTable(...)` and `noJoinTable(JoinKind, ...)` forbid those
  matches. These methods use table name/schema/alias matching, not full structural
  join equality. A table only inside a joined derived query is not that join's
  immediate named source.
- `projection(String... items)` requires individual structural SELECT items in
  the outer projection; additional items and different list order are allowed.
  `noProjection(...)` forbids those items. `exactProjection(...)` requires exactly
  those items in that order, preserving duplicates. Aliases, expression structure,
  identifier spelling, and wildcards are significant. Omitting an expected alias
  does not ignore an actual alias. WITH uses its body; a set operation uses its
  first term's projection. DML has no SELECT projection for these checks.
- `orderBy(String... items)` checks the entire outer ORDER BY list in order,
  including direction, NULL ordering, expressions, and ordinals. Supply individual
  items without `ORDER BY`. Explicit ASC and omitted direction are distinct SQM
  structures. `noOrderBy()` requires no outer ordering. Neither method searches
  subqueries or window ordering.
- `limit(long)` and `offset(long)` check literal counts on the outer query.
  SQM maps LIMIT/OFFSET and OFFSET/FETCH to the same model. Ordinary SQL Server
  TOP is recognized as a limit; TOP PERCENT and WITH TIES are not fixed row limits.
  Parameterized expressions do not match literal counts. Absence differs from
  explicit zero. `noLimit()` requires no limit specification (including TOP or
  LIMIT ALL); `noOffset()` requires no offset. Negative expected counts are rejected.
- `parameter(String... names)` and `noParameter(...)` inspect named parameters
  throughout the statement using SQM's `ParametersCollector`. Supply canonical
  names without `:`, `@`, or template prefixes. Names are case-sensitive.
  `parameterCount(int)` counts all named, anonymous, and ordinal placeholder
  occurrences; `positionalParameterCount(int)` counts only anonymous/ordinal ones.
  Repeated names/ordinals count repeatedly, not as distinct bindings. Strings and
  comments containing placeholder-like text do not count. Expected counts must be
  non-negative, and no supplied bind values are inspected.
- `structurallyEquals(expectedSql)` compares parsed SQM models. Whitespace,
  ordinary comments, and keyword casing do not affect equality. Identifier
  spelling and quoting, aliases, projection order, predicates, literals, and
  parameter names remain significant. This does not prove semantic equivalence.
  A failure renders the enclosing expected/actual SQL fragments using the selected
  dialect, for example `LIMIT 20 OFFSET 40` versus
  `LIMIT 10 OFFSET 40`. These are canonical SQL fragments, not excerpts of the original
  formatting. If SQM cannot render the fragment or its enclosing SQL, diagnostics fall
  back to the differing model path and values. Model paths are omitted when rendered
  SQL provides the context.

Table, schema, alias, and column-reference names compare case-insensitively when both identifiers are
unquoted. If either identifier is quoted, their values must match exactly.
This rule is the same for every dialect. Structural comparison continues to
preserve identifier spelling in all dialects.

Checks are combined with AND and all applicable failures are collected in declaration
order. Invalid actual SQL produces `PARSE_ERROR` diagnostics and skips model checks.
Invalid expected SQL or fragments throw `IllegalArgumentException` during evaluation
after actual SQL parses successfully. A parse failure skips requirement preparation
and evaluation. Null arguments are rejected. Unexpected errors
from custom checks propagate rather than becoming mismatches.
