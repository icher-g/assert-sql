package io.assertsql;

import io.sqm.core.AnonymousParamExpr;
import io.sqm.core.NamedParamExpr;
import io.sqm.core.OrdinalParamExpr;
import io.sqm.core.Statement;

import java.util.*;

/**
 * Per-evaluation binding snapshot and global anonymous positions, selected using SQM parameter nodes.
 */
final class BindingContext {
    private final boolean supplied;
    private final Map<String, Object> named;
    private final List<Object> positional;
    private final Map<Integer, Object> ordinal;
    private final DiagnosticContext diagnostics;
    private final IdentityHashMap<AnonymousParamExpr, Integer> positions = new IdentityHashMap<>();

    BindingContext(ParameterizedSql input, Statement statement, DiagnosticContext diagnostics) {
        this.diagnostics = diagnostics;
        supplied = input.hasBindings();
        named = input.namedBindings();
        positional = input.positionalBindings();
        ordinal = input.ordinalBindings();
        int position = 1;
        for (var parameter : ModelInspector.parameters(statement, false).positional()) {
            if (parameter instanceof AnonymousParamExpr anonymous) {
                positions.put(anonymous, position++);
            }
        }
    }

    List<Mismatch> value(Statement statement, boolean local, Slot slot, Object expected, boolean absent) {
        if (!supplied) {
            return unavailable();
        }
        if (absent) {
            return contains(slot) ? failure("BINDING_UNEXPECTED", slot, "Expected no supplied binding for this slot.") : List.of();
        }
        if (!slots(statement, local).contains(slot)) {
            return failure("PARAMETER_NOT_REFERENCED", slot, "No matching parameter slot exists in the inspected SQL.");
        }
        if (!contains(slot)) {
            return failure("BINDING_MISSING", slot, "No binding was supplied for this parameter slot.");
        }
        if (BindingValues.equal(expected, get(slot))) {
            return List.of();
        }
        var message = diagnostics.redaction() == Redaction.NONE
            ? "Expected binding <" + BindingValues.describe(expected) + "> but was <" + BindingValues.describe(get(slot)) + ">."
            : "Binding value differs from the expected value.";
        return failure("BINDING_MISMATCH", slot, message);
    }

    DiagnosticContext diagnostics() {
        return diagnostics;
    }

    List<Mismatch> matchAll(Statement statement, boolean local, Set<Slot> expected) {
        var required = slots(statement, local);
        var mismatches = new ArrayList<Mismatch>();
        for (var slot : required) {
            if (!expected.contains(slot)) {
                mismatches.add(
                    new Mismatch(
                        "PARAMETER_UNEXPECTED",
                        slot.parameterPath(),
                        "Parameter slot is not covered by a positive parameter or binding expectation."
                    )
                );
            }
        }
        if (supplied && !local) {
            mismatches.addAll(complete(required));
        }
        return List.copyOf(mismatches);
    }

    private static List<Mismatch> unavailable() {
        return List.of(
            new Mismatch("BINDINGS_UNAVAILABLE", "bindings", "Supply ParameterizedSql with explicit binding containers to evaluate binding checks.")
        );
    }

    private static List<Mismatch> failure(String code, Slot slot, String message) {
        return List.of(new Mismatch(code, slot.path(), message));
    }

    private List<Mismatch> complete(Set<Slot> required) {
        var mismatches = new ArrayList<Mismatch>();
        for (var slot : required) {
            if (!contains(slot)) {
                mismatches.addAll(failure("BINDING_MISSING", slot, "No binding was supplied for this parameter slot."));
            }
        }
        var actual = new LinkedHashSet<Slot>();
        named.keySet().forEach(name -> actual.add(new Slot(Kind.NAMED, name)));
        for (int i = 1; i <= positional.size(); i++) {
            actual.add(new Slot(Kind.POSITIONAL, i));
        }
        ordinal.keySet().forEach(index -> actual.add(new Slot(Kind.ORDINAL, index)));
        actual.removeAll(required);
        for (var slot : actual) {
            mismatches.addAll(failure("BINDING_UNEXPECTED", slot, "A binding was supplied for a slot not referenced in SQL."));
        }
        return List.copyOf(mismatches);
    }

    private Set<Slot> slots(Statement statement, boolean local) {
        var collector = ModelInspector.parameters(statement, local);
        var slots = new LinkedHashSet<Slot>();
        for (var parameter : collector.named()) {
            slots.add(new Slot(Kind.NAMED, ((NamedParamExpr) parameter).name()));
        }
        for (var parameter : collector.positional()) {
            if (parameter instanceof OrdinalParamExpr ordinalParameter) {
                slots.add(new Slot(Kind.ORDINAL, ordinalParameter.index()));
            }
            else
                if (parameter instanceof AnonymousParamExpr anonymous) {
                    slots.add(new Slot(Kind.POSITIONAL, positions.get(anonymous)));
                }
        }
        return slots;
    }

    private boolean contains(Slot slot) {
        return switch (slot.kind()) {
            case NAMED -> named.containsKey((String) slot.key());
            case ORDINAL -> ordinal.containsKey((int) slot.key());
            case POSITIONAL -> (int) slot.key() >= 1 && (int) slot.key() <= positional.size();
        };
    }

    private Object get(Slot slot) {
        return switch (slot.kind()) {
            case NAMED -> named.get((String) slot.key());
            case ORDINAL -> ordinal.get((int) slot.key());
            case POSITIONAL -> positional.get((int) slot.key() - 1);
        };
    }

    enum Kind {
        NAMED, POSITIONAL, ORDINAL
    }

    record Slot(Kind kind, Object key) {
        String path() {
            return path("bindings");
        }

        String parameterPath() {
            return path("parameters");
        }

        private String path(String root) {
            return root + "." + kind.name().toLowerCase(java.util.Locale.ROOT) + "[" + key + "]";
        }
    }
}
