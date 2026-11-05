# Testing generated SQL without a database

These executable examples accompany [story #3](https://github.com/icher-g/assert-sql/issues/3).
They test SQL produced by application code, using Assert SQL's public API.

## Run

From the repository root, with JDK 21+ and Maven 3.9+:

```shell
mvn -B -ntp -pl assert-sql-examples -am verify
```

The full `mvn verify` build also runs the examples, including in CI. The initial
build downloads Maven dependencies; no database, JDBC driver, container, schema,
generated jOOQ classes, or credentials are required.

All example code lives under `src/test/java`. The module is not installed or published to
GitHub Packages or Maven Central. Assert SQL core gains no new runtime dependencies.
The example pins jOOQ Open Source Edition **3.19.39** and the optional JAXB API
**4.0.4** needed by jOOQ's Settings annotations at compile time. Both have test
scope, as do Assert SQL and JUnit Jupiter.

## The contract

Both generators produce a user page with an optional profile:

| Requirement | Assertion | Regression it catches |
| --- | --- | --- |
| Select from users | `select().table("users u")` | Wrong statement kind or source |
| Preserve users without profiles | `leftJoinTable("user_profiles p")` | Accidentally requiring a profile through an INNER JOIN |
| Stable output mapping | `exactProjection("u.id", "u.name", "p.display_name AS profile_name")` | Extra, missing, reordered, or wrongly aliased output items |
| Tenant predicate | `where("u.tenant_id = :tenantId")` for jOOQ | Missing or changed complete outer predicate |
| Active filter when requested | Complete tenant/active `where(...)` for the custom builder | Accidentally removing the active filter |
| Stable page ordering | `orderBy("u.created_at DESC", "u.id ASC")` | Lost or reversed sort/tie-breaker |
| Requested page | `limit(20).offset(40)` | Wrong or missing row limit/offset |
| Intended placeholders | `parameter(...)` and `parameterCount(...)` | Missing or extra parameter occurrences |

These are structural SQL contracts, not executed proofs about rows, schema
constraints, tenant isolation, or query plans. The profile relation is intended
to be one-to-one per user; no catalog is loaded or validated by these examples.

Every showcase test constructs its expectation inline, alongside the commented
SQL and evaluation. This intentional repetition lets readers see the complete
contract without navigating to a helper. Application tests can still extract
and reuse immutable expectations when that suits their own test organization.

## jOOQ example

[JooqUserQueries](src/test/java/io/assertsql/examples/fixtures/JooqUserQueries.java) constructs
a PostgreSQL query through `DSL.using(SQLDialect.POSTGRES, settings)` with no
Connection or DataSource. It uses typed fields, named parameters, a selectable
LEFT/INNER JOIN, and caller-supplied pagination.

```java
var query = JooqUserQueries.users(42L, 20, 40, false);
var expectation = Expect.sql(POSTGRESQL)
    .table("users u")
    .leftJoinTable("user_profiles p")
    .where("u.tenant_id = :tenantId")
    .build();
expectation.assertMatches(query.getSQL(ParamType.NAMED));
```

The query is only rendered; there are no `fetch()` or `execute()` calls.
`requireProfile=false` selects a LEFT JOIN. The controlled regression passes
`true` and generates an INNER JOIN, which fails the existing optional-profile
contract with a standard `AssertionError`:

```text
[tenant user page with optional profiles] SQL did not match expectations:
- [JOIN_TABLE_NOT_REFERENCED] statement: Expected join_table <user_profiles p>.
```

[JooqExampleTest](src/test/java/io/assertsql/examples/JooqExampleTest.java) verifies
this failure without making the test suite fail. It also demonstrates `Match`
with JUnit and positional placeholder counting:

```java
var result = expectation.evaluate(query.getSQL(ParamType.NAMED));
assertTrue(result.matches(), result::describe);
```

The indexed SQL example uses `getSQL(ParamType.INDEXED)` and checks its positional
placeholder count. Values are obtained separately from jOOQ's `getBindValues()`
and checked with JUnit. Assert SQL cannot recover binding values from SQL text;
native binding assertions are tracked separately in story #8.

### Rendering and SQM compatibility

The rendering settings choose unquoted identifiers for these fixed, lower-case
example names, so structural projection expectations remain readable. If your
application uses jOOQ's default quoted names, preserve those quotes in structural
projection/ordering expectations. Table-name expectations use their documented
identifier matching rules.

Pagination uses `DSL.inline` for the validated numeric page size and offset, so
named/indexed rendering does not turn pagination into extra placeholders. The
tenant ID remains bound. An application that binds pagination instead should
assert parameter occurrences rather than use literal `limit`/`offset` checks.

SQM **0.5.0 rejects the parenthesized AND predicate** jOOQ renders for a normal
two-condition WHERE, for example `WHERE (u.tenant_id = :tenantId AND u.active = :active)`.
The jOOQ example therefore demonstrates a single tenant predicate. The custom
builder demonstrates tenant plus active filtering. No generated SQL is stripped,
rewritten, or replaced with a hand-written expected statement to hide this parser
boundary. Supporting that jOOQ output requires an upstream SQM parser fix.

Official jOOQ references:
[connection configuration](https://www.jooq.org/doc/3.19/manual/sql-building/dsl-context/connection-vs-datasource/),
[identifier rendering](https://www.jooq.org/doc/latest/manual/sql-building/dsl-context/custom-settings/settings-name-style/),
[SQL and binding extraction](https://www.jooq.org/javadoc/3.19.x/org.jooq/org/jooq/AttachableQueryPart.html).

## Custom application builder

[UserSearchQueryBuilder](src/test/java/io/assertsql/examples/fixtures/UserSearchQueryBuilder.java)
collects application options in a mutable builder and generates SQL from those
options. Its identifiers and parameter names are fixed; only validated integer
pagination values are appended as literals.

```java
var sql = new UserSearchQueryBuilder().page(20, 40).build();
var expectation = Expect.sql(POSTGRESQL)
    .leftJoinTable("user_profiles p")
    .where("u.tenant_id = :tenantId AND u.active = :active")
    .parameter("tenantId", "active")
    .parameterCount(2)
    .build();
expectation.assertMatches(sql);
```

[CustomBuilderExampleTest](src/test/java/io/assertsql/examples/CustomBuilderExampleTest.java)
then changes the real generator input with `activeOnly(false)`. Against the active
page contract, this reports `WHERE_NOT_REFERENCED`, `PARAMETER_NOT_REFERENCED`, and
`PARAMETER_COUNT_MISMATCH`. It also verifies a separate valid all-users contract,
using `Match` with JUnit, and rejects invalid pagination options before generation.

The `comparesTheCompleteQueryStructureRatherThanItsFormatting` test constructs
`structurallyEquals(...)` inline using a complete expected SQL text block. The
generated single-line SQL matches despite different whitespace, keyword casing,
an ordinary comment, and a trailing semicolon. Changing the builder's page size
from 20 to 10 then produces `STRUCTURE_MISMATCH`. Use this approach when the whole
query is the contract; use focused expectations when unrelated query changes
should remain acceptable. This compares SQM structure, not semantic equivalence.

The example tests and their expectation construction live in `io.assertsql.examples`;
only the SQL generators live separately in `io.assertsql.examples.fixtures`. Helpers are
public only so the tests can access them across packages. They remain test sources
and are not published as a library API. Copy the testing approach into your application.
