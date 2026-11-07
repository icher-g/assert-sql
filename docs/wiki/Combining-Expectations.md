[Wiki home](https://github.com/icher-g/assert-sql/wiki) · [Repository](https://github.com/icher-g/assert-sql)

# Combining expectations

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
