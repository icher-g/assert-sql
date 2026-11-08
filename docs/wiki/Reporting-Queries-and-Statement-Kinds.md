[Wiki home](https://github.com/icher-g/assert-sql/wiki) · [Repository](https://github.com/icher-g/assert-sql)

# Reporting queries and statement kinds

Use focused checks when grouping, aggregate filtering, or a DISTINCT variant is
the contract, and other query details may evolve independently:

```java
var customerTotals = Expect.sql(POSTGRESQL)
    .as("customer order totals")
    .select()
    .table("orders o")
    .groupBy("o.customer_id")
    .having("count(*) >= 2")
    .noDistinct()
    .build();

customerTotals.assertMatches("""
    SELECT o.customer_id, count(*) AS order_count FROM orders o
    GROUP BY o.customer_id HAVING count(*) >= 2
    """);
```

`groupBy()` requires any GROUP BY clause. `groupBy(String... items)` instead
compares the **exact ordered list** using SQM equality: extra, missing, reordered,
or duplicate items matter. Each argument is one complete grouping item, without
the GROUP BY keyword. Functions and grouping extensions keep their own commas
inside that item:

```java
.groupBy("country", "ROLLUP(city, district)")
```

Or, for a grouping-sets contract:

```java
.groupBy("GROUPING SETS ((country, city), (country), ())")
```

These are alternative grouping contracts, not successive additions to a list.
Repeated `groupBy(...)` calls are independent AND requirements, as with `orderBy`.
`groupBy("country, city")` is an invalid single item; supply two arguments instead.
`groupBy(new String[0])` is invalid, whereas the dedicated `groupBy()` overload is
the presence check. Grouping sets, ROLLUP, CUBE, and ordinals use SQM's configured
dialect parser and model. No grouping expansion, ordinal resolution, semantic
reordering, or equivalence inference is performed. `noGroupBy()` requires absence.

`having(predicate)` accepts one complete predicate without HAVING and compares
its SQM structure. For example, `having("count(*) >= 2")` does not match the larger
predicate `count(*) >= 2 AND sum(total) > 100`. Supply the whole predicate when
that conjunction is the contract. Parentheses, expression structure, identifier
spelling, and function names follow SQM's model equality. `noHaving()` requires
the selected outer body to have no HAVING. These checks do not establish logical
implication, aggregate validity, or correctness of returned rows.

DISTINCT variants are explicit:

| Check | Contract |
| --- | --- |
| `distinct()` | Plain SELECT DISTINCT only |
| `distinctOn("customer_id", "region")` | DISTINCT ON with that exact ordered expression list |
| `noDistinct()` | Neither plain DISTINCT nor DISTINCT ON |

`distinctOn(...)` takes individual expressions without the DISTINCT ON syntax.
Each expression and the complete clause are parsed during evaluation, including
SQM's feature gate. An unsupported expected clause raises a configuration error;
unsupported actual SQL returns `PARSE_ERROR`. Plain DISTINCT never matches DISTINCT
ON, and DISTINCT inside `count(DISTINCT id)` is not a SELECT modifier. UNION's
deduplication likewise does not imply SELECT DISTINCT.

These clause checks apply to the selected outer SELECT. WITH uses its main body;
use `.cte(...)`, `.derived(...)`, or `.exists(...)` to inspect another body. Grouping,
HAVING, and DISTINCT in set-operation terms cannot satisfy an outer clause check.
A set query has none of these outer SELECT clauses, so absence checks pass even
when a branch contains them. INSERT SELECT is an INSERT whose source clauses are
nested; its source GROUP BY, HAVING, and DISTINCT do not count as outer clauses.
More generally, absence on a valid body with no applicable clause passes; combine
it with `.select()` when a query kind is also required. Invalid actual SQL or a
missing/ambiguous requested scope still fails before negative checks can pass.

For writes, assert the kind alongside the existing structural or binding checks:

```java
Expect.sql(POSTGRESQL)
    .insert()
    .table("users")
    .binding("tenantId", 42L)
    .binding("userName", "Ada")
    .matchAllParameters()
    .build()
    .assertMatches(parameterizedSql);
```

Kind checks share the collection counters' outer-body classification. MERGE and
other DML syntax must be supported by the chosen SQM parser. SQM 0.5.1 rejects
WITH followed by an outer INSERT/UPDATE; no alternative parser is introduced.
Data-changing CTEs remain separate selected bodies, so a SELECT with an UPDATE
inside a CTE is still an outer SELECT. Kind checks are not a read-only guarantee.
Assignment and INSERT target-column expectations are outside this vocabulary.

All fragments are copied at declaration and parsed only during terminal
evaluation. Builders, snapshots, OR composition, selected scopes, and redaction
follow the existing contracts. Mismatches retain codes such as `GROUP_BY_MISMATCH`,
`HAVING_MISMATCH`, `DISTINCT_ON_MISMATCH`, or `NOT_UPDATE`, with SQL fragments
rendered where useful. `Redaction.ALL` hides literal detail without changing the
comparison.

Use `structurallyEquals(...)` when the entire statement is fixed, including its
projection, joins, predicates, grouping, ordering, and pagination. Use these
focused methods when only particular clauses must remain stable.
[ReportingExampleTest](https://github.com/icher-g/assert-sql/blob/main/assert-sql-examples/src/test/java/io/assertsql/examples/ReportingExampleTest.java)
asserts actual jOOQ aggregate and DISTINCT ON queries and shows changed grouping
and HAVING failures. The [INSERT binding examples](https://github.com/icher-g/assert-sql/blob/main/assert-sql-examples/src/test/java/io/assertsql/examples/BindingsExampleTest.java)
also use the new statement-kind check.
