/**
 * Declarative SQL assertions powered by SQM.
 *
 * <p>Mutable builders collect declarations and build immutable expectations.
 * Only terminal operations parse SQL and evaluate checks. SQL execution is
 * outside the scope of this library.</p>
 *
 * <p>Outer-query and named-CTE blocks reuse the same checks on selected SQM nodes.
 * Scoped searches exclude nested queries; complete clause comparisons and custom
 * checks retain the original selected statement. Unscoped behavior is preserved.</p>
 */
package io.assertsql;
