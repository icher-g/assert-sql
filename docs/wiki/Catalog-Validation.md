[Wiki home](https://github.com/icher-g/assert-sql/wiki) · [Repository](https://github.com/icher-g/assert-sql)

# Catalog validation

Add the optional module when your tests need SQM schema validation. It includes
`assert-sql-core` transitively; applications using core alone do not acquire the
catalog or validator dependencies.

```xml
<dependency>
    <groupId>io.github.icher-g</groupId>
    <artifactId>assert-sql-validation</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

Supply SQM's public catalog types directly. The catalog is explicit test data;
Assert SQL does not discover it from a database or ORM mappings.

```java
import io.assertsql.Expect;
import io.assertsql.validation.SchemaChecks;
import io.sqm.catalog.model.CatalogColumn;
import io.sqm.catalog.model.CatalogSchema;
import io.sqm.catalog.model.CatalogTable;
import io.sqm.catalog.model.CatalogType;
import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;

var schema = CatalogSchema.of(
    CatalogTable.of("app", "users", CatalogColumn.of("id", CatalogType.LONG), CatalogColumn.of("name", CatalogType.STRING))
);
var validUsers = Expect.sql(POSTGRESQL)
    .as("users schema contract")
    .satisfies(SchemaChecks.validAgainst(schema, POSTGRESQL))
    .table("app.users")
    .noSelectStar()
    .build();

validUsers.assertMatches("SELECT u.id, u.name FROM app.users u");
var invalid = validUsers.evaluate("SELECT u.missing FROM app.users u");
System.out.println(invalid.describe()); // Includes SQM's COLUMN_NOT_FOUND diagnostic.
```

`validAgainst(...)` returns a reusable `Check`. Validation runs only at evaluation,
on the original parsed statement, with SQM's name resolution, type checks, and
dialect rules. Invalid SQL produces `PARSE_ERROR` and skips validation. Use the
same dialect for parsing and validation: ANSI uses SQM's generic validator; the
other four dialects use their SQM extensions and default dialect versions.

Each SQM problem retains its code, message, available clause path, and reporting
order in `Match`. SQM 0.5.0 exposes no severity or warning field: every reported
problem fails the check. Unsupported dialect identifiers are rejected when creating
the check. Custom `DialectQuery` models and MERGE statements unsupported by SQM's
configured dialect capabilities produce `VALIDATION_UNSUPPORTED`; native SQM
dialect feature diagnostics retain their original codes.

Attach validation at the root to preserve the full CTE and correlated-reference
context. Inside `.outer(...)`, `.cte(...)`, `.derived(...)`, or `.exists(...)`, it
validates only the selected body as a standalone statement, without enclosing
bindings. SQM supports query and DML validation, including the CTE/subquery forms
covered by the tests; its coverage and limitations apply here. In particular,
SQM 0.5.0 can accept an unknown derived-table output column such as
`SELECT q.missing FROM (SELECT id FROM app.users) q`. A successful check means SQM
reported no problems; it does not prove runtime results, constraints, or plans.

`CatalogSchema`, `CatalogTable`, and their contents are immutable; SQM copies
caller-owned collections during construction. The check retains the supplied
catalog reference and creates fresh validation state for each evaluation, so it
can be reused concurrently. Create a new catalog/check for a different schema.
SQM's permissive catalogs, such as `CatalogSchema.allowEverything()`, retain
their permissive behavior and cannot verify table/column existence.

[CatalogValidationExampleTest](https://github.com/icher-g/assert-sql/blob/main/assert-sql-examples/src/test/java/io/assertsql/examples/CatalogValidationExampleTest.java)
validates actual jOOQ-generated SQL with a CTE and correlated EXISTS using an
in-memory catalog, including a missing-column failure, without a database.
