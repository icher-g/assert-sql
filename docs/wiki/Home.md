# Assert SQL guides

Declarative SQL assertions powered by [SQM](https://github.com/icher-g/sqm). Assert SQL inspects SQL structure without executing queries and works with any Java assertion framework.

Start with [getting started](https://github.com/icher-g/assert-sql/wiki/Getting-Started) or the [repository quick examples](https://github.com/icher-g/assert-sql#quick-start).

| Guide | Covers |
| --- | --- |
| [Getting started](https://github.com/icher-g/assert-sql/wiki/Getting-Started) | Installation, declarations, terminal methods, and build |
| [Built-in checks](https://github.com/icher-g/assert-sql/wiki/Built-in-Checks) | Tables, columns, joins, predicates, projection, pagination, and parameters |
| [Structural equality](https://github.com/icher-g/assert-sql/wiki/Structural-Equality) | Complete-query contracts and rendered SQL differences |
| [Query scopes](https://github.com/icher-g/assert-sql/wiki/Query-Scopes) | Outer queries, CTEs, derived tables, and EXISTS |
| [Reporting and statement kinds](https://github.com/icher-g/assert-sql/wiki/Reporting-Queries-and-Statement-Kinds) | GROUP BY, HAVING, DISTINCT variants, and DML |
| [Joins, projection, and pagination](https://github.com/icher-g/assert-sql/wiki/Joins-Projection-and-Pagination) | A focused query-shape contract |
| [Combining expectations](https://github.com/icher-g/assert-sql/wiki/Combining-Expectations) | AND requirements, varargs, and OR alternatives |
| [Parameter bindings](https://github.com/icher-g/assert-sql/wiki/Parameter-Bindings) | Supplied values, slot coverage, ordering, and ownership |
| [Statement collections](https://github.com/icher-g/assert-sql/wiki/Statement-Collections) | Statement budgets, kind counts, and any/all/none |
| [Catalog validation](https://github.com/icher-g/assert-sql/wiki/Catalog-Validation) | Optional validation against a supplied SQM catalog |
| [Diagnostics and redaction](https://github.com/icher-g/assert-sql/wiki/Diagnostics-and-Redaction) | Descriptions, structured results, and disclosure policies |
| [Custom checks](https://github.com/icher-g/assert-sql/wiki/Custom-Checks) | Inspect the public SQM model directly |
| [Hibernate SQL capture](https://github.com/icher-g/assert-sql/wiki/Hibernate) | Real ORM integration, capture lifecycle, and parser boundaries |
| [SQM compatibility](https://github.com/icher-g/assert-sql/wiki/Compatibility) | Parser/model limitations and supported workarounds |

## Executable examples

The [examples module](https://github.com/icher-g/assert-sql/blob/main/assert-sql-examples/README.md) contains real jOOQ-generated SQL, an application-owned builder, and Hibernate/H2 capture tests. Expectations are constructed inline and SQL is shown in test comments.

## Maintaining these guides

Page sources are versioned in [docs/wiki](https://github.com/icher-g/assert-sql/tree/main/docs/wiki). Update them with the code and publish the matching wiki pages; see [wiki maintenance](https://github.com/icher-g/assert-sql/blob/main/docs/WIKI.md).
