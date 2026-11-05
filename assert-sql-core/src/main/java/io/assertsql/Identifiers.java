package io.assertsql;

import io.sqm.core.Identifier;

final class Identifiers {
    private Identifiers() {
    }

    static boolean matches(Identifier actual, Identifier expected) {
        if (expected == null) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        if (actual.quoted() || expected.quoted()) {
            return actual.value().equals(expected.value());
        }
        return actual.value().equalsIgnoreCase(expected.value());
    }
}
