[Wiki home](https://github.com/icher-g/assert-sql/wiki) · [Repository](https://github.com/icher-g/assert-sql)

# Parameter bindings

`ParameterizedSql` adds caller-supplied values to SQL inspection, using the same terminal
methods and assertion-framework-independent results. Binding checks are part of
core and do not execute SQL or require JDBC.

```java
import io.assertsql.Expect;
import io.assertsql.ParameterizedSql;
import java.util.Map;
import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;

var newUser = Expect.sql(POSTGRESQL)
    .as("new user arguments")
    .table("users")
    .parameterCount(2)
    .binding("tenantId", 42L)
    .binding("userName", "Ada")
    .matchAllParameters()
    .build();

var input = ParameterizedSql.named(
    "INSERT INTO users (tenant_id, name) VALUES (:tenantId, :userName)",
    Map.of("tenantId", 42L, "userName", "Ada")
);
newUser.assertMatches(input);
```

Individual value checks require that SQL references the slot and that its supplied
value matches; they permit unrelated extra or missing bindings. Add
`.matchAllParameters()` to require every actual SQL slot to be covered by a positive
`parameter(...)`, `binding(...)`, `positionalBinding(...)`, or `ordinalBinding(...)`
expectation. Uncovered SQL slots report `PARAMETER_UNEXPECTED`. Repeated names and
ordinals identify one slot; anonymous occurrences have separate positions.

The same method works with parameter-name expectations and String-only evaluation:

```java
Expect.sql(POSTGRESQL)
    .parameter("tenantId", "userName")
    .matchAllParameters()
    .build()
    .assertMatches("SELECT :tenantId, :userName");
```

An additional `:status` in this query would fail. Missing declared parameters still
fail their individual presence/value checks. Counts and negative checks do not
declare allowed slots. With no positive declarations, `.matchAllParameters()`
requires no parameters; `.parameterCount(0)` expresses that intent more clearly.
Its position in the fluent chain does not matter; build snapshots all declarations.

When explicit binding containers are supplied, an unscoped `.matchAllParameters()`
also requires bindings for every SQL slot and rejects unused bindings. Parameter
coverage itself needs no binding values. Value assertions and negative binding
checks still require them. Supplying `ParameterizedSql` alone does not activate checks;
existing occurrence-count and parameter-presence methods keep their meanings.

| Parameter kind | Input | Value assertion | Slot rule |
| --- | --- | --- | --- |
| Named, such as `:tenantId` | `ParameterizedSql.named(sql, map)` | `binding("tenantId", 42L)` | Canonical, case-sensitive name without prefix; repeated names share one slot |
| Anonymous `?` | `ParameterizedSql.positional(sql, list)` | `positionalBinding(1, 42L)` | One-based positions in SQM collector traversal; each anonymous occurrence has its own slot |
| Explicit ordinal, such as `$2` | `ParameterizedSql.ordinal(sql, map)` | `ordinalBinding(2, 42L)` | The index written in SQL; repeated indices share a slot and skipped indices need no binding |

For mixed SQL use `ParameterizedSql.of(sql, namedMap, positionalList, ordinalMap)`. The
three namespaces are independent: named and ordinal occurrences do not consume
anonymous positions. Repeated names/ordinals still count repeatedly in occurrence
checks; no expanded value list is inferred from those counts. Placeholder syntax
must be supported by the selected SQM parser.

Anonymous ordering follows SQM's model, **not a promise of driver bind order**.
For `SELECT ?, ?` or ordinary INSERT VALUES, the orders agree. For
`SELECT ? OFFSET ? ROWS FETCH NEXT ? ROWS ONLY`, SQM visits projection, limit,
then offset, so the input list is `[projectionValue, limitValue, offsetValue]`.
TOP with projection parameters and dialect pagination forms can also differ from
textual order. Use named/ordinal slots where available, or explicitly supply the
SQM order; Assert SQL does not reorder a driver/tool binding list automatically.
`BindingsTest` covers the OFFSET/FETCH boundary.

`noBinding(name)`, `noPositionalBinding(position)`, and `noOrdinalBinding(index)`
assert absence from the supplied containers, whether or not SQL references that
slot. A supplied null is present and therefore fails an absence check. Invalid
SQL fails with `PARSE_ERROR` before any binding check runs.

String-only evaluation and `ParameterizedSql.of(sql)` carry no binding information;
value checks and negative binding checks fail with `BINDINGS_UNAVAILABLE`.
Use an explicit empty container, such as `ParameterizedSql.named(sql, Map.of())`, when
you want to assert that nothing was supplied. Explicit nulls are supported in
maps/lists that allow them, for example `Collections.singletonMap("name", null)`;
they differ from absent keys or positions.

Values use Java `Objects.deepEquals`: arrays compare recursively by contents,
other objects use `equals`, and no numeric or JDBC conversion occurs. `42` differs
from `42L`; `BigDecimal("1.0")` differs from `BigDecimal("1.00")`. SQL types are
not inferred from supplied values. Input maps/lists are copied and exposed as
unmodifiable snapshots. Arrays and `Date` values are copied on input, expectation
declaration, and access; cyclic arrays are rejected. Other values are retained by
reference and must remain effectively immutable, including application objects
or collections used as individual binding values. This policy also applies to
expected values in reusable expectations.

By default, binding diagnostics identify the name, position, or ordinal and omit
both expected and supplied values. For example:

```text
- [BINDING_MISMATCH] bindings.named[userName]: Binding value differs from the expected value.
```

Scoped individual value checks use the selected query's parameters but retain
full-statement anonymous numbering; they never renumber a CTE or EXISTS body.
Negative binding checks inspect the full supplied container. In scope blocks,
`.matchAllParameters()` covers only parameters at the selected query level and
allows bindings belonging elsewhere in the input. Positive declarations in nested
blocks also contribute slot coverage to their ancestors. Root coverage includes
all query levels and validates supplied binding completeness for the entire input.
OR alternatives receive the same input and independently inspect their own parsed SQL.

[BindingsExampleTest](https://github.com/icher-g/assert-sql/blob/main/assert-sql-examples/src/test/java/io/assertsql/examples/BindingsExampleTest.java)
demonstrates named parameters from jOOQ's `getParams()` and anonymous values from
`getBindValues()`, with an unchanged SQL shape and incorrect values/order detected
without execution. SQL inspection cannot recover values from text; callers must
supply the values exposed by their query generator or capture tooling.
