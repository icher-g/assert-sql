# Assert SQL

Declarative SQL assertions powered by [SQM](https://github.com/icher-g/sqm).
Test SQL structure without executing queries or relying on fragile string comparisons.

- Works with any assertion framework; failures use standard `AssertionError`.
- Immutable expectations are reusable across tests.
- Inspect tables, joins, projections, predicates, grouping, pagination, and parameters.
- Supports SQM's ANSI, PostgreSQL, MySQL, SQL Server, and Oracle parsers.

## Installation

Requires **JDK 21+** and **Maven 3.9+**. The current version is `0.1.0-SNAPSHOT`;
build and install it from this repository with `mvn install`, then add:

```xml
<dependency>
    <groupId>io.github.icher-g</groupId>
    <artifactId>assert-sql-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

Snapshots are also available from GitHub Packages, which requires credentials.
See [getting started](https://github.com/icher-g/assert-sql/wiki/Getting-Started)
for setup and the optional catalog-validation module.

## Quick start

Declare the parts of a generated query that must stay stable:

```java
import io.assertsql.Expect;
import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;

var users = Expect.sql(POSTGRESQL)
    .as("user page")
    .select()
    .table("users u")
    .leftJoinTable("user_profiles p")
    .noSelectStar()
    .limit(20)
    .build();

users.assertMatches("""
    SELECT u.id, p.display_name
    FROM users u LEFT JOIN user_profiles p ON p.user_id = u.id
    ORDER BY u.id LIMIT 20
    """);
```

Checks combine with AND. SQL is parsed only when `assertMatches`, `matches`, or
`evaluate` is called. Use `matches(sql)` for a boolean or `evaluate(sql)` for
structured diagnostics that work with your existing assertion framework.

See [built-in checks](https://github.com/icher-g/assert-sql/wiki/Built-in-Checks)
and [combining expectations](https://github.com/icher-g/assert-sql/wiki/Combining-Expectations).

## Compare the complete query

Use `structurallyEquals` when the whole statement is the contract:

```java
Expect.sql(POSTGRESQL)
    .as("active user page")
    .structurallyEquals("SELECT id FROM users WHERE active = true LIMIT 20 OFFSET 40")
    .build()
    .assertMatches("select id from users where active = TRUE limit 10 offset 40;");
```

This fails with a SQL fragment that identifies the change:

```text
[active user page] SQL did not match expectations:
- [STRUCTURE_MISMATCH] Expected SQL <LIMIT 20 OFFSET 40> but was <LIMIT 10 OFFSET 40>.
```

Formatting and keyword case do not matter; identifier spelling, literals, and
query structure remain significant. See [structural equality](https://github.com/icher-g/assert-sql/wiki/Structural-Equality)
and [diagnostics and redaction](https://github.com/icher-g/assert-sql/wiki/Diagnostics-and-Redaction).

## Check a reporting contract

Focused checks let unrelated query details evolve:

```java
Expect.sql(POSTGRESQL)
    .select()
    .groupBy("customer_id")
    .having("count(*) >= 2")
    .noDistinct()
    .build()
    .assertMatches("""
        SELECT customer_id, count(*) FROM orders
        GROUP BY customer_id HAVING count(*) >= 2
        """);
```

See [reporting queries and statement kinds](https://github.com/icher-g/assert-sql/wiki/Reporting-Queries-and-Statement-Kinds)
for exact grouping lists, DISTINCT variants, absence checks, and DML.

## More guides

| Need | Guide |
| --- | --- |
| Inspect a CTE, derived table, EXISTS, or just the outer query | [Query scopes](https://github.com/icher-g/assert-sql/wiki/Query-Scopes) |
| Assert supplied parameter values and complete slot coverage | [Parameter bindings](https://github.com/icher-g/assert-sql/wiki/Parameter-Bindings) |
| Check query budgets or unexpected writes | [Statement collections](https://github.com/icher-g/assert-sql/wiki/Statement-Collections) |
| Validate against an explicit in-memory schema | [Catalog validation](https://github.com/icher-g/assert-sql/wiki/Catalog-Validation) |
| Capture real Hibernate SQL | [Hibernate integration](https://github.com/icher-g/assert-sql/wiki/Hibernate) |
| Write a check against SQM's public model | [Custom checks](https://github.com/icher-g/assert-sql/wiki/Custom-Checks) |
| Understand parser and model boundaries | [SQM compatibility](https://github.com/icher-g/assert-sql/wiki/Compatibility) |

Browse the [full wiki](https://github.com/icher-g/assert-sql/wiki)
or the [runnable examples](assert-sql-examples/README.md) using real jOOQ,
Hibernate/H2, and an application-owned query builder.

## Build and contribute

```shell
mvn verify
```

The build includes all examples. Assert SQL itself never executes SQL;
the Hibernate examples execute against embedded H2 to demonstrate capture.

See [publishing setup](docs/PUBLISHING.md) and [wiki maintenance](docs/WIKI.md).
Licensed under the [MIT License](LICENSE).
