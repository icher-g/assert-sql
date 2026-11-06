/**
 * Optional schema assertions using caller-supplied SQM catalogs and SQM's dialect validators.
 * Checks integrate through {@link io.assertsql.Expect.Builder#satisfies(io.assertsql.Check)}.
 * Schema validation inspects the original parsed statement without JDBC or SQL execution.
 */
package io.assertsql.validation;
