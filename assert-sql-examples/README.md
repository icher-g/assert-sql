# Testing application-generated SQL

These executable examples accompany [story #3](https://github.com/icher-g/assert-sql/issues/3)
through [story #5](https://github.com/icher-g/assert-sql/issues/5). They test SQL produced
by application code and capture SQL from real Hibernate HQL execution using Assert SQL's public API.

## Run

From the repository root, with JDK 21+ and Maven 3.9+:

```shell
mvn -B -ntp -pl assert-sql-examples -am verify
```

The full `mvn verify` build also runs the examples, including in CI. The initial
build downloads Maven dependencies. Hibernate tests create an embedded in-memory H2
database, create the mapped schema, persist fixture data, and execute actual HQL
queries. No external database, container, manual schema setup, or credentials are
required. The jOOQ and custom-builder examples still render SQL without executing it.

All example code lives under `src/test/java`. The module is not installed or published to
GitHub Packages or Maven Central. Assert SQL core gains no new runtime dependencies.
The example pins jOOQ Open Source Edition **3.19.39** and the optional JAXB API
**4.0.4** needed by jOOQ's Settings annotations at compile time. Both have test
scope, as do Hibernate ORM **6.6.59.Final**, H2 **2.4.240**, Assert SQL, and JUnit Jupiter.
Hibernate and H2 are confined to this example module and do not enter the core runtime.

## The contract

The jOOQ and custom builders produce a user page with an optional profile:

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
constraints, tenant isolation, or query plans. Those two examples do not load a
catalog. The Hibernate example additionally executes real queries and checks returned
rows with JUnit against its own mapped schema and fixture data.

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

### CTE and outer-query scopes

`JooqUserQueries.activeUsersWithAudit()` generates this SQL through jOOQ:

```sql
WITH active_users(id) AS (
    SELECT u.id FROM users AS u WHERE u.active = true
)
SELECT a.id FROM active_users AS a
WHERE EXISTS (SELECT 1 FROM audit AS x WHERE x.user_id = a.id)
```

`separatesCteAndOuterReferencesInGeneratedSql` constructs the scoped expectation
inline in [JooqExampleTest](src/test/java/io/assertsql/examples/JooqExampleTest.java):

```java
var sql = JooqUserQueries.activeUsersWithAudit().getSQL(ParamType.NAMED);
Expect.sql(POSTGRESQL)
    .outer(query -> query.table("active_users a").column("a.id").noTable("users", "audit"))
    .cte("active_users", query -> query.table("users u").exactProjection("u.id").where("u.active = true"))
    .build()
    .assertMatches(sql);
```

The same test verifies that `.outer(query -> query.table("users"))` fails with
`TABLE_NOT_REFERENCED` and an `outer query` location: a table inside the CTE cannot
satisfy that outer-only requirement. Captured/rendered SQL is passed unchanged to
Assert SQL. See the root README for the complete scope contract and nested CTE rules.

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

## Hibernate SQL capture

This example supports Hibernate ORM **6.6.59.Final**, pinned in the example POM:

```xml
<dependency>
    <groupId>org.hibernate.orm</groupId>
    <artifactId>hibernate-core</artifactId>
    <version>6.6.59.Final</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <version>2.4.240</version>
    <scope>test</scope>
</dependency>
```

[HibernateSqlCapture](src/test/java/io/assertsql/examples/fixtures/HibernateSqlCapture.java)
implements Hibernate's public
[StatementInspector](https://docs.hibernate.org/orm/6.6/javadocs/org/hibernate/resource/jdbc/spi/StatementInspector.html).
Copy it into your application test sources; it is an example, not a published adapter.
The callback stores every SQL string without modification, filtering, parsing, or
asserting, then returns the exact same string to Hibernate. `statements()` returns
an immutable snapshot, including duplicates, in callback order. `clear()` resets the
capture without changing existing snapshots. There is no static or thread-local state.

### Session registration and assertion lifecycle

[HibernateDatabase](src/test/java/io/assertsql/examples/fixtures/hibernate/HibernateDatabase.java)
starts a real Hibernate factory using a uniquely named `jdbc:h2:mem:...` database.
Hibernate creates and drops the schema, and persists three users across two tenants:
Ada has a profile, Linus has no profile, and a third user belongs to another tenant.
The [User](src/test/java/io/assertsql/examples/fixtures/hibernate/User.java) and
[UserProfile](src/test/java/io/assertsql/examples/fixtures/hibernate/UserProfile.java)
classes provide real Jakarta Persistence mappings. Each factory owns its database;
closing the fixture closes the connection pool and discards the database.

Obtain `sessionFactory` from this fixture, or use your application's existing factory
and database test setup. Register a fresh capture for the session performing the operation:

```java
var capture = new HibernateSqlCapture();
try (var session = sessionFactory.withOptions().statementInspector(capture).openSession()) {
    var transaction = session.beginTransaction();
    try {
        session.createQuery("select u.id from ExampleUser u where u.tenantId in :tenantIds order by u.id", Long.class)
            .setParameterList("tenantIds", List.of(42L))
            .getResultList();
        var statements = capture.statements();
        assertEquals(1, statements.size(), "Expected one SQL statement from this operation.");
        var expectation = Expect.sql(ANSI)
            .as("Hibernate tenant lookup")
            .select()
            .table("users")
            .column("tenant_id")
            .parameterCount(1)
            .positionalParameterCount(1)
            .build();
        expectation.assertMatches(statements.getFirst());
    } finally {
        transaction.rollback();
    }
} finally {
    capture.clear();
}
```

This query uses the fixture's `ExampleUser` entity and emits one statement. Import
`Expect`, your copied helper, `List`, `SqlDialectId.ANSI`, and JUnit's `assertEquals`,
as shown in the root README. The example uses Hibernate's native H2 dialect and the
ANSI SQL subset accepted by SQM for these queries. This is not general H2 dialect
support; choose the Assert SQL dialect matching your application's database.
Hibernate generates and executes the SQL; Assert SQL only inspects the captured text.

Prepare/flush fixture data before the capture window, then `clear()` immediately
before the operation if you reuse the same session. Flush the operation's pending
writes before taking a snapshot when testing deferred INSERT/UPDATE/DELETE SQL.
Assert before rollback/cleanup to exclude their statements, and clear in `finally`
even when the operation or assertion fails. Close the session with try-with-resources.

Assert the expected statement count before indexing the snapshot. Iterating an
empty capture with `forEach(expectation::assertMatches)` would otherwise pass
without checking anything. ORM caches, lazy associations, and auto-flush can change
which statements are observed; configure the application fixture for the intended
operation. If several statements are expected, assert their count and apply the
appropriate expectation to each statement rather than assuming every statement is a SELECT.

Hibernate-generated aliases can change. Use unaliased table/column expectations
when the alias is not part of the contract. Choose the Assert SQL dialect matching
your database. If SQM rejects captured SQL, report the parse diagnostic and address
the parser boundary; the capture must still forward the original SQL unchanged.

### Factory-wide registration and parallel tests

For applications that need one inspector shared across sessions, register the
instance before building the factory, using your already-configured Hibernate `metadata`:

```java
var sharedCapture = new HibernateSqlCapture();
var sessionFactory = metadata.getSessionFactoryBuilder().applyStatementInspector(sharedCapture).build();
```

Alternatively, set `AvailableSettings.STATEMENT_INSPECTOR`
(`hibernate.session_factory.statement_inspector`) to the capture instance in your
bootstrap settings. These are alternatives to session-local registration. A session
inspector explicitly supplied through `withOptions().statementInspector(...)` takes
precedence over the factory inspector. See the public
[SessionBuilder](https://docs.hibernate.org/orm/6.6/javadocs/org/hibernate/SessionBuilder.html)
and [SessionFactoryBuilder](https://docs.hibernate.org/orm/6.6/javadocs/org/hibernate/boot/SessionFactoryBuilder.html) APIs.

All methods of this helper synchronize on the capture instance. Concurrent callbacks
and snapshots are safe, but synchronization does not associate SQL with a particular
session, transaction, or test. A shared `clear()` affects every writer, and ordering
between threads is unspecified. Use fresh session-local instances for parallel
tests; if factory registration is required, isolate the factory per test or serialize
the complete clear/operation/assert/cleanup window across every session using it.
The `Session` itself is not made thread-safe by the capture helper. A session-local
inspector only sees operations on that session, so ensure application code actually
uses the inspected session rather than opening another one.

### Real Hibernate tests

- [HibernateExampleTest](src/test/java/io/assertsql/examples/HibernateExampleTest.java)
  executes HQL over the mapped entities. It asserts Hibernate-generated projection,
  LEFT JOIN, filtering, ordering, and bound pagination, then verifies the returned
  user without a profile. A second query changes the HQL to an INNER JOIN: Assert SQL
  reports `JOIN_TABLE_NOT_REFERENCED`, and JUnit verifies that only Ada remains.
  Each test constructs its expectation inline and comments the SQL being inspected.
- [HibernateSqlCaptureTest](src/test/java/io/assertsql/examples/HibernateSqlCaptureTest.java)
  executes real queries to test duplicate callbacks, immutable snapshots, reset/reuse,
  independent captures for parallel sessions, and concurrent shared capture.
- [HibernateRegistrationTest](src/test/java/io/assertsql/examples/HibernateRegistrationTest.java)
  executes queries through factory-wide and session-local inspectors. It verifies
  that a session inspector actually receives the SQL in place of the factory
  inspector, and that local cleanup leaves the factory capture unchanged.

All Hibernate tests exercise the real ORM and embedded H2 in the default Maven/CI
build. Tests never call `inspect(...)` themselves or substitute a hand-written SQL
string for Hibernate output. Query result assertions are separate from SQL assertions.

### SQM 0.5.0 parser boundary

Hibernate emits compact equality predicates such as `u1_0.tenant_id=?`. SQM 0.5.0
does not separate the adjacent `=` and `?` tokens, so parsing fails, for example
with `Unsupported expression token: order` when an ORDER BY follows. The real
`reportsTheSqmBoundaryForCompactEqualityProducedByHibernate` query executes
successfully against H2 and verifies that Assert SQL returns `PARSE_ERROR` for its
unchanged SQL. This needs an upstream parser fix for ordinary equality-based queries.

The passing showcase uses a bound tenant-list query (`u.tenantId in :tenantIds`),
which Hibernate renders as `tenant_id in (?)`. That SQL parses successfully without
rewriting. The integration example demonstrates supported output and this boundary;
it does not claim all Hibernate-generated SQL is supported.

`StatementInspector` observes SQL before JDBC statement preparation, not bind calls
or successful execution. It sees placeholders such as `?`, not the value `42L` or
the original HQL parameter name `tenantIds`. Placeholder counts can be checked, but
bound pagination cannot be asserted with literal `limit(20)`/`offset(40)` checks.
Use your application's existing JDBC/ORM tooling to verify bind values separately.
Inspecting SQL alone does not prove result correctness or schema validity.

The example tests and their expectation construction live in `io.assertsql.examples`;
SQL generators, entity mappings, and database setup live separately under `io.assertsql.examples.fixtures`. Helpers are
public only so the tests can access them across packages. They remain test sources
and are not published as a library API. Copy the testing approach into your application.
