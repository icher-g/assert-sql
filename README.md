# Assert SQL

Declarative SQL assertions powered by [SQM](https://github.com/icher-g/sqm).
Test SQL structure without executing queries or relying on fragile string comparisons.

## Direction

- Lightweight Java library with no assertion-framework dependency.
- SQM provides the SQL model and, as features are introduced, parsing and inspection.
- Immutable, reusable expectations describe the checks to perform.
- Terminal operations evaluate expectations: `matches`, `evaluate`, and `assertMatches`.
- Assertion failures use standard `AssertionError`; SQL is never executed.

This repository currently contains the Maven scaffold only. The public assertion API
has not been implemented. Java reserves `assert`, so the throwing terminal operation
is planned as `assertMatches`.

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
