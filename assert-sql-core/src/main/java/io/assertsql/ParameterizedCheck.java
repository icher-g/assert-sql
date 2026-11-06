package io.assertsql;

import io.sqm.core.Statement;

import java.util.List;

/**
 * Internal checks retain evaluation input across query scopes without changing the public Check contract.
 */
@FunctionalInterface
interface ParameterizedCheck {
    List<Mismatch> evaluate(Statement statement, BindingContext bindings);
}
