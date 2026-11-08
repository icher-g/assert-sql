[Wiki home](https://github.com/icher-g/assert-sql/wiki) · [Repository](https://github.com/icher-g/assert-sql)

# Statement collections

Use `Expect.statements(dialect)` to assert an ordered `List<String>` from an
application-owned capture. This API is in core, with no additional dependencies.
It does not capture or execute SQL itself.

```java
var readBudget = Expect.statements(ANSI)
    .as("tenant lookup budget")
    .count(2)
    .selectCount(2)
    .updateCount(0)
    .build();

readBudget.assertMatches(capture.statements());
```

`count(...)` checks the exact total. `selectCount`, `insertCount`, `updateCount`,
`deleteCount`, and `mergeCount` check the exact count of their statement kind;
zero forbids that kind. Every supplied entry counts once, including duplicate SQL.
Subqueries, set-operation terms, and CTE bodies do not become separate entries.
WITH is classified by its outer body. For example, a SELECT with an UPDATE inside
a CTE is one SELECT; `updateCount(0)` checks outer statement kinds, not all possible
database side effects. The chosen SQM parser must support the SQL: SQM 0.5.1
currently rejects WITH followed by an outer INSERT or UPDATE.

Add a callback when only statements satisfying additional conditions should count:

```java
Expect.statements(ANSI)
    .selectCount(2, query -> query
        .table("users")
        .parameterCount(1)
    )
    .updateCount(0)
    .build()
    .assertMatches(statements);
```

The kind is restricted **before** the callback's checks are evaluated. A users
UPDATE cannot count toward `selectCount`, and callers need no `.select()` in the
callback. The callback above permits additional SELECTs that do not meet its
conditions; combine it with `.selectCount(2)` if those must also be excluded.

Declarations are AND-combined. Mutable builders snapshot their requirements on
`build()` and require at least one check. Filter callbacks run during declaration,
must add at least one check, and are snapshotted when they return. SQL is parsed
only at a terminal operation. Callback filters reuse the same original parsed
SQM nodes across rules, with fragments prepared before any custom check runs.
Built expectations support concurrent reuse when their custom checks are thread-safe.

For advanced cases, an existing `Expect` can match whole statements through
`matchingCount(expectation, count)`, `any(expectation)`, `all(expectation)`, and
`none(expectation)`. These methods apply exactly the supplied expectation, with
no implicit statement-kind restriction: a table expectation can match SELECTs
and DML. They accept OR-combined expectations:

```java
var users = Expect.sql(ANSI)
    .table("users")
    .build();
var audit = Expect.sql(ANSI)
    .table("audit")
    .build();

Expect.statements(ANSI)
    .all(Expect.or(users, audit))
    .build()
    .assertMatches(statements);
```

| Rule | Empty collection |
| --- | --- |
| `any(expectation)` | Fails |
| `all(expectation)` | Passes |
| `none(expectation)` | Passes |
| `matchingCount(expectation, 0)` | Passes |
| Any exact count greater than zero | Fails |

Every terminal defensively copies the input list, rejects null lists/elements,
preserves order, and parses every entry in the collection dialect first. Entries
are individual statements, not scripts to split. Any parse error fails the result
and skips all requirements, even count-only or absence rules. Valid SQL that fails
an ordinary filter is a non-match. Matching rules inspect every candidate; an
early success cannot hide a later error. Existing-Expect matchers evaluate each
original SQL string using their own configured dialect, so they can parse it again.
Their parse errors also fail the rule. Unavailable bindings or unsupported SQM
checks (`BINDINGS_UNAVAILABLE`, `SQM_SCOPE_UNSUPPORTED`, `VALIDATION_UNSUPPORTED`)
likewise cannot prove a zero count or absence. This API supplies SQL strings only,
without binding values. Configuration and unexpected custom-check exceptions
propagate unchanged instead of becoming assertion mismatches.

`evaluate`, `matches`, and `assertMatches` have the same roles as for single SQL.
Diagnostics preserve zero-based input indices, for example:

```text
- [UPDATE_COUNT_MISMATCH] collection.updateCount: Expected 0 matching UPDATE statements but found 1 at indices [1].
```

Member parsing and filter failures use paths such as `statements[1].sql[20]` or
`statements[1].statement.parameters`. The collection description is included in
throwing assertion messages.

[StatementCollectionExampleTest](https://github.com/icher-g/assert-sql/blob/main/assert-sql-examples/src/test/java/io/assertsql/examples/StatementCollectionExampleTest.java)
captures real Hibernate SQL, enforces an explicit two-SELECT budget, and catches
an unexpected UPDATE between the reads. Such budgets can expose additional-query
regressions in a controlled capture window. They do not diagnose N+1 behavior from
SQL text alone; the application owns the capture window and expected query budget.
