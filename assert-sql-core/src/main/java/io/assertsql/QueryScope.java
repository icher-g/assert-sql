package io.assertsql;

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

    private static QueryScope missing(String label) {
        return new QueryScope(null, label, new Mismatch("SCOPE_NOT_FOUND", label, "No matching CTE is defined by this statement's WITH clause."));
    }

    List<Mismatch> evaluate(List<Check> checks, String description) {
        var scope = description == null ? label : label + " [" + description + "]";
        if (failure != null) {
            return List.of(new Mismatch(failure.code(), scope, failure.message()));
        }
        var mismatches = new ArrayList<Mismatch>();
        for (var check : checks) {
            for (var mismatch : List.copyOf(check.evaluate(statement))) {
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
}
