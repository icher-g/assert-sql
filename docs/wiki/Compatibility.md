[Wiki home](https://github.com/icher-g/assert-sql/wiki) · [Repository](https://github.com/icher-g/assert-sql)

# SQM compatibility

Assert SQL uses SQM **0.5.1** from Maven Central. Parser and model capabilities depend on the selected dialect.

## Set-query ordering and pagination

SQM 0.5.1 attaches trailing ORDER BY/LIMIT/OFFSET to the composite set query.
Outer ordering/pagination assertions work with unparenthesized UNION queries,
including queries inside CTEs. Parenthesized term-local clauses remain local.

SQL Server's native `@id` expression syntax and some
keyword-based names such as `:limit` are also rejected by SQM 0.5.1. Parser-supported
canonical named syntax such as `:id` and `:pageSize` works. These inputs produce
parse diagnostics rather than successful negative assertions.

## Captured Hibernate equality

SQM 0.5.1 supports compact equality such as `tenant_id=?` emitted by Hibernate and parenthesized AND predicates emitted by jOOQ. The integration tests inspect unchanged generated SQL; no rewriting is required.

## Other feature boundaries

- [Reporting clauses and statement kinds](https://github.com/icher-g/assert-sql/wiki/Reporting-Queries-and-Statement-Kinds) covers dialect gates for DISTINCT ON and WITH followed by outer DML.
- [Parameter bindings](https://github.com/icher-g/assert-sql/wiki/Parameter-Bindings) explains why anonymous SQM traversal order can differ from driver binding order.
- [Catalog validation](https://github.com/icher-g/assert-sql/wiki/Catalog-Validation) documents validation coverage, including derived-table output columns.

Invalid actual SQL produces `PARSE_ERROR`; absence checks cannot turn it into a match.
