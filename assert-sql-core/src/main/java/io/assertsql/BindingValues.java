package io.assertsql;

import java.lang.reflect.Array;
import java.util.*;

/**
 * Ownership and comparison rules shared by supplied and expected binding values.
 */
final class BindingValues {
    private BindingValues() {
    }

    static Object copy(Object value) {
        return copy(value, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    static boolean equal(Object expected, Object actual) {
        return Objects.deepEquals(expected, actual);
    }

    static String describe(Object value) {
        try {
            var text = java.util.Arrays.deepToString(new Object[]{value});
            text = text.substring(1, text.length() - 1);
            return text.length() <= 200 ? text : text.substring(0, 200) + "… (truncated)";
        }
        catch (RuntimeException ignored) {
            return "unavailable";
        }
    }

    static String name(String name) {
        Objects.requireNonNull(name, "name");
        if (
            name.isBlank()
                || name.chars().anyMatch(Character::isWhitespace)
                || ":@$?".indexOf(name.charAt(0)) >= 0
        ) {
            throw new IllegalArgumentException("Use a non-blank canonical parameter name without a prefix or whitespace.");
        }
        return name;
    }

    static int position(int position) {
        if (position < 1) {
            throw new IllegalArgumentException("Binding positions and ordinal indices are one-based.");
        }
        return position;
    }

    @SuppressWarnings("SuspiciousSystemArraycopy")
    private static Object copy(Object value, Set<Object> active) {
        if (value instanceof Date date) {
            return date.clone();
        }
        if (value == null || !value.getClass().isArray()) {
            return value;
        }
        if (!active.add(value)) {
            throw new IllegalArgumentException("Cyclic binding arrays are unsupported.");
        }
        try {
            int length = Array.getLength(value);
            var result = Array.newInstance(value.getClass().getComponentType(), length);
            if (value.getClass().getComponentType().isPrimitive()) {
                System.arraycopy(value, 0, result, 0, length);
            }
            else {
                for (int i = 0; i < length; i++) {
                    Array.set(result, i, copy(Array.get(value, i), active));
                }
            }
            return result;
        } finally {
            active.remove(value);
        }
    }
}
