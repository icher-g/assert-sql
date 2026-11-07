[Wiki home](https://github.com/icher-g/assert-sql/wiki) · [Repository](https://github.com/icher-g/assert-sql)

# Joins, projection, and pagination

```java
var page = Expect.sql(POSTGRESQL)
    .select()
    .table("users u")
    .leftJoinTable("orders o")
    .exactProjection("u.id", "u.name AS display_name")
    .orderBy("u.created_at DESC", "u.id ASC")
    .limit(20)
    .offset(40)
    .parameter("tenantId")
    .noParameter("password")
    .parameterCount(1)
    .build();

page.assertMatches("""
    SELECT u.id, u.name AS display_name
    FROM users u LEFT JOIN orders o ON o.user_id = u.id
    WHERE u.tenant_id = :tenantId
    ORDER BY u.created_at DESC, u.id ASC
    LIMIT 20 OFFSET 40
    """);
```

Join-source checks describe presence/type rather than full join conditions. For
example, changing the join above to INNER fails `leftJoinTable("orders o")`, while
changing the ON predicate alone does not. Use `join(...)` when the condition is
part of the contract. Likewise, use `column(...)` for references anywhere and
`projection(...)` for the output list specifically.


See [SQM compatibility](https://github.com/icher-g/assert-sql/wiki/Compatibility) for set-query pagination and parser boundaries.
