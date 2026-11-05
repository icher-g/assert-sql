# Assert SQL

Declarative SQL assertions powered by [SQM](https://github.com/icher-g/sqm).
Test SQL structure without executing queries or relying on fragile string comparisons.

## Direction

- Lightweight Java library with no assertion-framework dependency.
- SQM provides the SQL model and, as features are introduced, parsing and inspection.
- Immutable, reusable expectations describe the checks to perform.
- Terminal operations evaluate expectations: `matches`, `evaluate`, and `assertMatches`.
- Assertion failures use standard `AssertionError`; SQL is never executed.

The core contracts are available in `io.assertsql`:

- `Expect`: immutable declarations with `evaluate`, `matches`, and `assertMatches` terminals.
- `Expect.Builder`: mutable declaration API; `build()` produces an immutable `Expect`.
- `Check`: custom checks receiving SQM's `Statement` directly.
- `Match`: immutable results created with `Match.of(...)`, including readable diagnostics.
- `Mismatch`: a diagnostic code, statement path, and message.

Java reserves `assert`, so the throwing terminal operation is named `assertMatches`.

## Declare and evaluate expectations

```java
import io.assertsql.Expect;
import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;

var usersQuery = Expect.sql(POSTGRESQL)
    .as("active users query")
    .select()
    .table("users")
    .noSelectStar()
    .structurallyEquals("SELECT id FROM users WHERE active = true")
    .build();

// Declaration and build() do not parse SQL or invoke checks.
// The built expectation can be reused across calls and threads.
usersQuery.assertMatches("select id from users where active = TRUE;");

boolean matches = usersQuery.matches("SELECT * FROM users");
var result = usersQuery.evaluate("SELECT id FROM orders");
System.out.println(result.describe());
```

`Expect.sql(...)` supports SQM's `ANSI`, `POSTGRESQL`, `MYSQL`, `SQLSERVER`, and
`ORACLE` dialect identifiers. Builders collect requirements by mutating themselves;
their implementation class is private. `build()` requires at least one check and
copies the declaration into an immutable expectation. A description alone does
not count as a check. Further builder changes do not affect earlier snapshots.
Builders are not thread-safe. A trailing semicolon is allowed; statement sequences
are rejected. No schema validation or SQL execution takes place.

All three terminal methods use the same evaluation path. `matches` returns a
boolean, `evaluate` returns a `Match`, and `assertMatches` throws a standard
`AssertionError` with the diagnostics on failure. Results can also be used with
another assertion framework, for example JUnit's
`assertTrue(result.matches(), result::describe)`.

## Built-in checks

- `select()` accepts queries, including WITH queries and set operations, and rejects DML.
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
- `structurallyEquals(expectedSql)` compares parsed SQM models. Whitespace,
  ordinary comments, and keyword casing do not affect equality. Identifier
  spelling and quoting, aliases, projection order, predicates, literals, and
  parameter names remain significant. This does not prove semantic equivalence.
  A failure reports the first differing model component and its expected/actual values.

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

## Multiple requirements and alternatives

```java
var activeUsers = Expect.sql(POSTGRESQL)
    .table("users u", "orders o")
    .column("u.id", "o.user_id")
    .join("INNER JOIN orders o ON o.user_id = u.id")
    .where("u.active = true")
    .noColumn("password", "secret")
    .noTable("legacy_users", "archive")
    .build();

activeUsers.assertMatches("""
    SELECT u.id FROM users u
    INNER JOIN orders o ON o.user_id = u.id
    WHERE u.active = true
    """);

var archivedUsers = Expect.sql(POSTGRESQL)
    .table("archived_users")
    .column("id")
    .noJoin()
    .build();

Expect.or(activeUsers, archivedUsers)
    .assertMatches("SELECT id FROM archived_users");
```

Varargs have the same meaning as repeated calls: all positive arguments must
match, and all negative arguments must be absent. They are not OR lists. Every
fragment-taking varargs method rejects empty arrays and null elements. In
particular, `where("a = 1", "b = 2")` requires the same outer predicate to equal
both inputs; it does not construct `a = 1 AND b = 2`. Supply the complete predicate
as one fragment, or use separate built expectations with `Expect.or(...)` for
alternatives. `structurallyEquals` continues to take one complete expected statement.

`Expect.or(...)` accepts one or more built expectations, defensively copies the
alternatives, and evaluates them in order until one matches. Requirements inside
each branch remain AND requirements. If none matches, the result contains all
branch failures under paths such as `or[0].statement`. Each evaluated alternative
uses its own dialect and parse context. Exceptions from evaluated alternatives
propagate; alternatives after a successful one are skipped. Invalid SQL does not
match any built branch, including branches containing only negative checks.

## Custom checks using SQM

```java
import io.assertsql.Mismatch;
import io.sqm.core.SelectQuery;
import java.util.List;

var singleProjection = Expect.sql(POSTGRESQL)
    .satisfies(statement -> {
        if (statement instanceof SelectQuery query && query.items().size() == 1) {
            return List.of();
        }
        return List.of(new Mismatch(
            "SINGLE_PROJECTION", "statement.items", "Expected one SELECT projection."));
    })
    .build();

singleProjection.assertMatches("SELECT id FROM users");
```

Each SQL expectation evaluation parses actual SQL once (OR evaluates its branches
independently). Custom checks receive that same SQM
`Statement` instance and return an empty list for success or non-null diagnostics
for failure. Custom checks used in shared expectations must be thread-safe.

## Build

Requires JDK 21 or later and Maven 3.9 or later.

```shell
mvn verify
```

The parent manages dependency and build plugin versions. `assert-sql-core` is the
initial library module, with Maven coordinates `io.github.icher-g:assert-sql-core`
and package `io.assertsql`. JUnit Jupiter is used only for tests.

SQM is an external dependency (`io.github.icher-g:sqm-core:0.5.0`), resolved from
Maven Central. No extra repository configuration, GitHub credentials, or local
SQM checkout is needed. Its version is managed by the parent's `sqm.version` property.

## CI and publication

GitHub Actions verifies pull requests and `main`, saves test and coverage reports,
and publishes snapshots from `main` to GitHub Packages. Release tags (`v0.1.0`)
upload signed artifacts to Maven Central Portal for validation and manual publication.
See [publishing setup](docs/PUBLISHING.md) for the required secrets and release steps.

Licensed under the [MIT License](LICENSE), matching SQM.
