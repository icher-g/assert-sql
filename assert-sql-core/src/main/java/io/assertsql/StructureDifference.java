package io.assertsql;

import io.sqm.core.Expression;
import io.sqm.core.Node;
import io.sqm.core.Predicate;
import io.sqm.render.defaults.DefaultSqlWriter;
import io.sqm.render.spi.ParameterizationMode;
import io.sqm.render.spi.RenderContext;
import io.sqm.render.spi.RenderOptions;
import io.sqm.render.spi.SqlDialect;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Locates a diagnostic after SQM model equality has established a mismatch.
 */
final class StructureDifference {
    private StructureDifference() {
    }

    static Mismatch between(Object expected, Object actual, SqlDialect dialect) {
        return locate(expected, actual, "statement", new ArrayList<>(), RenderContext.of(dialect));
    }

    private static Mismatch locate(Object expected, Object actual, String path, List<Fragment> fragments, RenderContext renderer) {
        // Keep enclosing SQL constructs while reflection descends into identifiers, literals, and other model values.
        if (isSqlContext(expected) || isSqlContext(actual)) {
            fragments.add(new Fragment(expected, actual));
        }
        if (expected instanceof List<?> left && actual instanceof List<?> right) {
            for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
                if (!Objects.equals(left.get(i), right.get(i))) {
                    return locate(left.get(i), right.get(i), path + "[" + i + "]", fragments, renderer);
                }
            }
            return mismatch(path + ".size", left.size(), right.size(), fragments, renderer);
        }
        if (expected != null && actual != null && expected.getClass() == actual.getClass() && expected.getClass().isRecord()) {
            for (var component : expected.getClass().getRecordComponents()) {
                var left = read(component, expected);
                var right = read(component, actual);
                if (!Objects.equals(left, right)) {
                    return locate(left, right, path + "." + component.getName(), fragments, renderer);
                }
            }
        }
        return mismatch(path, expected, actual, fragments, renderer);
    }

    private static boolean isSqlContext(Object value) {
        if (value instanceof Node) {
            // A predicate or clause gives literals/column names context; a bare scalar expression usually does not.
            return !(value instanceof Expression) || value instanceof Predicate;
        }
        return value instanceof List<?> list && list.stream().allMatch(Node.class::isInstance);
    }

    private static Object read(RecordComponent component, Object instance) {
        try {
            return component.getAccessor().invoke(instance);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot inspect SQM model component " + component.getName(), e);
        }
    }

    private static Mismatch mismatch(String path, Object expected, Object actual, List<Fragment> fragments, RenderContext renderer) {
        for (int i = fragments.size() - 1; i >= 0; i--) {
            var fragment = fragments.get(i);
            try {
                var left = render(fragment.expected(), renderer);
                var right = render(fragment.actual(), renderer);
                if (!left.equals(right)) {
                    return new Mismatch("STRUCTURE_MISMATCH", "", "Expected SQL <" + left + "> but was <" + right + ">.");
                }
            } catch (RuntimeException ignored) {
                // A renderer may not support this fragment. Try its enclosing SQL without masking the original mismatch.
            }
        }
        return new Mismatch("STRUCTURE_MISMATCH", path, "Expected <" + summarize(expected) + "> but was <" + summarize(actual) + ">.");
    }

    private static String render(Object value, RenderContext renderer) {
        if (value == null) {
            return "absent";
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? "empty" : list.stream().map(item -> render(item, renderer)).collect(Collectors.joining(", "));
        }
        if (!(value instanceof Node node)) {
            return String.valueOf(value);
        }
        var prepared = renderer.dialect().beforeRender(node, RenderOptions.of(ParameterizationMode.Inline));
        var writer = new DefaultSqlWriter(renderer);
        // Use the writer's formatting mode rather than replacing whitespace inside SQL strings or quoted identifiers.
        writer.singleLine();
        renderer.render(prepared.node(), writer);
        var sql = writer.toText(prepared.params()).sql().strip();
        return sql.isEmpty() ? "empty" : sql;
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

    private record Fragment(Object expected, Object actual) {
    }
}
