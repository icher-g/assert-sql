[Wiki home](https://github.com/icher-g/assert-sql/wiki) · [Repository](https://github.com/icher-g/assert-sql)

# SQM compatibility

Assert SQL uses SQM **0.5.0** from Maven Central. Parser and model capabilities depend on the selected dialect.

## Set-query ordering and pagination

Assert SQL inspects the SQM model without reparsing or interpreting SQL itself.
The current SQM parser attaches trailing ORDER BY/LIMIT/OFFSET to the last term
of some unparenthesized set queries rather than to their composite node. Our outer
ordering/pagination checks return `SQM_SCOPE_UNSUPPORTED` when the final term
carries such clauses; both positive and negative assertions fail instead of
guessing the scope. This conservative restriction also applies to intentionally
term-local clauses on the last term, since that distinction is lost in the model.
For composite-level assertions with this version, parenthesize the terms and put
the ordering/pagination after the composite query:

```sql
(SELECT id FROM users) UNION (SELECT id FROM orders)
ORDER BY id DESC LIMIT 20 OFFSET 40
```

This parser boundary needs an SQM fix; Assert SQL does not silently reinterpret
the final term's clauses. SQL Server's native `@id` expression syntax and some
keyword-based names such as `:limit` are also rejected by SQM 0.5.0. Parser-supported
canonical named syntax such as `:id` and `:pageSize` works. These inputs produce
parse diagnostics rather than successful negative assertions.

## Captured Hibernate equality

SQM 0.5.0 rejects compact equality such as `tenant_id=?` emitted by Hibernate. Captured SQL is not rewritten. See the [Hibernate guide](https://github.com/icher-g/assert-sql/wiki/Hibernate) and [real parser-boundary test](https://github.com/icher-g/assert-sql/blob/main/assert-sql-examples/src/test/java/io/assertsql/examples/HibernateExampleTest.java).

## Other feature boundaries

- [Reporting clauses and statement kinds](https://github.com/icher-g/assert-sql/wiki/Reporting-Queries-and-Statement-Kinds) covers dialect gates for DISTINCT ON and WITH followed by outer DML.
- [Parameter bindings](https://github.com/icher-g/assert-sql/wiki/Parameter-Bindings) explains why anonymous SQM traversal order can differ from driver binding order.
- [Catalog validation](https://github.com/icher-g/assert-sql/wiki/Catalog-Validation) documents validation coverage, including derived-table output columns.

Invalid actual SQL produces `PARSE_ERROR`; absence checks cannot turn it into a match.
