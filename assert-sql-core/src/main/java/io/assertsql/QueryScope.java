package io.assertsql;

import io.sqm.core.ExistsPredicate;
import io.sqm.core.Identifier;
import io.sqm.core.Statement;
import io.sqm.core.WithQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * Selects existing SQM nodes and gives diagnostics a readable scope location.
 */
record QueryScope(Statement statement, String label, Mismatch failure) {
    static QueryScope outer(Statement statement) {
        return new QueryScope(ModelInspector.outerBody(statement), "outer query", null);
    }

    static QueryScope cte(Statement statement, Identifier name) {
        var label = "CTE \"" + name.value() + "\"";
        if (!(statement instanceof WithQuery with)) {
            return missing(label);
        }
        var indices = new ArrayList<Integer>();
        for (int i = 0; i < with.ctes().size(); i++) {
            if (Identifiers.matches(with.ctes().get(i).name(), name)) {
                indices.add(i);
            }
        }
        if (indices.isEmpty()) {
            return missing(label);
        }
        if (indices.size() > 1) {
            return new QueryScope(null, label, new Mismatch("SCOPE_AMBIGUOUS", label, "More than one WITH definition matches this CTE name."));
        }
        int index = indices.getFirst();
        return new QueryScope(with.ctes().get(index).body(), label + " (WITH item " + (index + 1) + ")", null);
    }

    static QueryScope derived(Statement statement, Identifier alias) {
        var label = "derived table \"" + alias.value() + "\"";
        var matches = ModelInspector.derivedTables(statement).stream().filter(table -> Identifiers.matches(table.alias(), alias)).toList();
        if (matches.isEmpty()) {
            return failure("SCOPE_NOT_FOUND", label, "No matching derived-table alias exists at the current query level.");
        }
        if (matches.size() > 1) {
            return failure("SCOPE_AMBIGUOUS", label, "Found " + matches.size() + " matching derived-table aliases at the current query level.");
        }
        return new QueryScope(matches.getFirst().query(), label, null);
    }

    static QueryScope exists(Statement statement) {
        var matches = ModelInspector.existsPredicates(statement);
        if (matches.isEmpty()) {
            return failure("SCOPE_NOT_FOUND", "EXISTS subquery", "No EXISTS/NOT EXISTS predicate exists at the current query level.");
        }
        if (matches.size() > 1) {
            return failure(
                "SCOPE_AMBIGUOUS",
                "EXISTS subquery",
                "Found " + matches.size() + " direct EXISTS/NOT EXISTS predicates; select an index."
            );
        }
        return selectedExists(matches.getFirst(), 0);
    }

    static QueryScope exists(Statement statement, int index) {
        var matches = ModelInspector.existsPredicates(statement);
        if (index >= matches.size()) {
            return failure(
                "SCOPE_NOT_FOUND",
                "EXISTS subquery[" + index + "]",
                "The index is outside the " + matches.size() + " direct EXISTS/NOT EXISTS predicates at the current query level."
            );
        }
        return selectedExists(matches.get(index), index);
    }

    List<Mismatch> evaluate(List<ParameterizedCheck> checks, String description, BindingContext bindings) {
        var scope = description == null ? label : label + " [" + description + "]";
        if (failure != null) {
            return List.of(new Mismatch(failure.code(), scope, failure.message()));
        }
        var mismatches = new ArrayList<Mismatch>();
        for (var check : checks) {
            for (var mismatch : List.copyOf(check.evaluate(statement, bindings))) {
                var path = mismatch.path();
                if (path.equals("statement")) {
                    path = "";
                }
                else
                    if (path.startsWith("statement.")) {
                        path = path.substring("statement.".length());
                    }
                var location = path.isEmpty() ? scope : scope + " / " + path;
                mismatches.add(new Mismatch(mismatch.code(), location, mismatch.message()));
            }
        }
        return List.copyOf(mismatches);
    }

    private static QueryScope selectedExists(ExistsPredicate predicate, int index) {
        var label = (predicate.negated() ? "NOT EXISTS" : "EXISTS") + " subquery[" + index + "]";
        return new QueryScope(predicate.subquery(), label, null);
    }

    private static QueryScope missing(String label) {
        return failure("SCOPE_NOT_FOUND", label, "No matching CTE is defined by this statement's WITH clause.");
    }

    private static QueryScope failure(String code, String label, String message) {
        return new QueryScope(null, label, new Mismatch(code, label, message));
    }
}
