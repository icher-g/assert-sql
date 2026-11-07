[Wiki home](https://github.com/icher-g/assert-sql/wiki) · [Repository](https://github.com/icher-g/assert-sql)

# Diagnostics and redaction

Diagnostics retain `.as(...)` descriptions through OR alternatives, selected
scopes, and collection rules. Descriptions are structured metadata rather than
part of the machine-readable path. For example, failed alternatives can report:

```text
SQL did not match expectations:
- [OR_NOT_MATCHED] or: None of the alternatives matched.
- [TABLE_NOT_REFERENCED] or[0].statement: [users search] Expected table <users>. Actual SQL <SELECT id FROM audit>.
- [TABLE_NOT_REFERENCED] or[1].statement: [orders search] Expected table <orders>. Actual SQL <SELECT id FROM audit>.
```

OR still stops at its first successful alternative; only evaluated branches can
contribute diagnostics or invoke custom checks. Scopes retain labels such as
`CTE "page" (WITH item 2)` and their full nesting chain. SQL structure differences
render the nearest useful fragment; a model path is retained only as a fallback
when rendering is unavailable. Presence checks also show the relevant actual SQL.

Choose a disclosure policy on the root builder:

| Policy | SQL literal values | Expected and supplied binding values |
| --- | --- | --- |
| `Redaction.BINDINGS` (default) | Visible, preserving existing diagnostic behavior | Hidden |
| `Redaction.ALL` | Hidden | Hidden |
| `Redaction.NONE` | Visible | Visible when useful to explain a binding mismatch |

```java
var userLookup = Expect.sql(POSTGRESQL)
    .as("user lookup")
    .redaction(Redaction.ALL)
    .structurallyEquals("SELECT id FROM users WHERE email = 'expected@example.test'")
    .build();

userLookup.assertMatches("SELECT id FROM users WHERE email = 'actual@example.test'");
```

```text
[user lookup] SQL did not match expectations:
- [STRUCTURE_MISMATCH] Expected SQL <email = ?> but was <email = ?>. Literal values differ (redacted).
```

Redaction changes disclosure, not comparison: different literals still fail even
when both render as `?`. Full mode uses SQM's literal transformer before rendering
SQL, then SQM token metadata when sanitizing other library messages. It covers
quoted/escaped, dollar-quoted, typed, numeric, boolean, and null literals supported
by the configured parser. It omits unsafe scalar fallbacks instead of printing
model record contents. It does **not** promise perfect redaction of arbitrary
malformed SQL: parse failures retain `PARSE_ERROR` and their position, while raw
parser text and SQL are omitted. Library-generated configuration errors remain
`IllegalArgumentException`; full mode omits their raw text and does not retain an
unredacted cause.

Use `Expect.or(Redaction.ALL, left, right)` to enforce full redaction across every
evaluated alternative, including nested ORs. Without that overload, alternatives
retain their own policies. `Expect.statements(dialect).redaction(...)` overrides
its callback and existing-Expect matchers. A single-statement policy covers its
nested scopes; configure it on the root rather than inside a scope callback.

To opt into binding detail explicitly:

```java
Expect.sql(POSTGRESQL)
    .redaction(Redaction.NONE)
    .binding("tenantId", 42L)
    .build()
    .assertMatches(ParameterizedSql.named("SELECT :tenantId", Map.of("tenantId", 99L)));
// BINDING_MISMATCH: Expected binding <42> but was <99>.
```

Rendered comparison fragments are capped at 600 characters per side, diagnostic
text at 2000 characters, and binding summaries at 200 characters, with truncation
marked. Redaction happens before truncation. Messages, structured diagnostics,
and library-generated `AssertionError` text use the same policy. Statement indices,
counts, identifiers, codes, and paths are structural metadata and remain available.
An identifier can have the same spelling as a hidden literal; the identifier
remains visible because its role is metadata rather than a literal value.

For custom checks, free-form returned messages are omitted under `ALL`; under
`BINDINGS` they are omitted when expected or supplied binding information is
present. This includes checks supplied by the optional validation module: codes
and paths remain available, while message detail follows the enclosing policy.
Unsupported binding object types are not stringified during redaction; labels
may be omitted when their safety cannot be established. Keep descriptions, codes,
paths, and SQL identifiers as non-sensitive application labels, not data channels.
Redaction is not a general secret scanner for arbitrary application text.
Unexpected exceptions thrown by custom checks propagate unchanged and remain the
application's responsibility. Directly constructed `Match.of(...)`/`Mismatch`
objects do not acquire a policy automatically.

Structured consumers can use `mismatch.code()`, `path()`, `message()`, and
`descriptions()` without parsing the human report. The three-argument `Mismatch`
constructor is retained; descriptions default to an empty list. Enclosing
descriptions are ordered from outermost to innermost. Changing a label or policy
does not change the diagnostic code or path. These reports explain structural
checks; a predicate's syntactic presence does not prove that it is logically mandatory.

[DiagnosticsExampleTest](https://github.com/icher-g/assert-sql/blob/main/assert-sql-examples/src/test/java/io/assertsql/examples/DiagnosticsExampleTest.java)
demonstrates a named OR failure and a named CTE scope over actual jOOQ-generated
SQL, with literal detail hidden.
