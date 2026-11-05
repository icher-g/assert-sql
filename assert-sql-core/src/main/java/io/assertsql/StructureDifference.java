package io.assertsql;

import io.sqm.core.Node;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Objects;

/** Locates a diagnostic after SQM model equality has established a mismatch. */
final class StructureDifference {
    private StructureDifference() {
    }

    static Mismatch between(Object expected, Object actual) {
        return locate(expected, actual, "statement");
    }

    private static Mismatch locate(Object expected, Object actual, String path) {
        if (expected instanceof List<?> left && actual instanceof List<?> right) {
            for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
                if (!Objects.equals(left.get(i), right.get(i))) {
                    return locate(left.get(i), right.get(i), path + "[" + i + "]");
                }
            }
            return mismatch(path + ".size", left.size(), right.size());
        }
        if (expected != null && actual != null && expected.getClass() == actual.getClass() && expected.getClass().isRecord()) {
            for (var component : expected.getClass().getRecordComponents()) {
                var left = read(component, expected);
                var right = read(component, actual);
                if (!Objects.equals(left, right)) {
                    return locate(left, right, path + "." + component.getName());
                }
            }
        }
        return mismatch(path, expected, actual);
    }

    private static Object read(RecordComponent component, Object instance) {
        try {
            return component.getAccessor().invoke(instance);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot inspect SQM model component " + component.getName(), e);
        }
    }

    private static Mismatch mismatch(String path, Object expected, Object actual) {
        return new Mismatch("STRUCTURE_MISMATCH", path, "Expected <" + summarize(expected) + "> but was <" + summarize(actual) + ">.");
    }

    private static String summarize(Object value) {
        if (value instanceof Node) {
            for (var type : value.getClass().getInterfaces()) {
                if (Node.class.isAssignableFrom(type)) {
                    return type.getSimpleName();
                }
            }
        }
        var text = String.valueOf(value);
        return text.length() <= 200 ? text : text.substring(0, 197) + "...";
    }
}
