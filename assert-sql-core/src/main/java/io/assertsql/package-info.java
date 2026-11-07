/**
 * Declarative SQL assertions powered by SQM.
 *
 * <p>Mutable builders collect declarations and build immutable expectations.
 * Only terminal operations parse SQL and evaluate checks. SQL execution is
 * outside the scope of this library.</p>
 *
 * <p>Outer-query, named-CTE, derived-table, and EXISTS blocks reuse the same checks on selected SQM nodes.
 * Scoped searches exclude nested queries; complete clause comparisons and custom
 * checks retain the original selected statement. Unscoped behavior is preserved.</p>
 *
 * <p>{@link io.assertsql.ParameterizedSql} carries optional caller-supplied binding values alongside SQL.
 * Binding checks use SQM parameter nodes; placeholder-presence and occurrence-count checks keep
 * their existing meanings. Values are never substituted into SQL or sent to a database.</p>
 *
 * <p>{@link io.assertsql.CollectionExpect} inspects caller-supplied statement lists with explicit
 * statement-kind counts and matching rules. All members are parsed before quantification, so invalid
 * SQL cannot establish absence. Capture and execution remain application responsibilities.</p>
 */
package io.assertsql;
