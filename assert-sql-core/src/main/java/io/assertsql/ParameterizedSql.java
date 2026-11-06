package io.assertsql;

import java.util.*;

/**
 * Immutable SQL text and caller-supplied binding containers. No SQL is parsed or executed here.
 * Named keys are canonical, case-sensitive names without prefixes. Anonymous positions are one-based
 * in SQM ParametersCollector traversal order; explicit ordinals use their own one-based SQL indices.
 * These are independent namespaces, including in mixed SQL. Repeated names/ordinals share one slot.
 *
 * <p>Containers, arrays (recursively), and Date values are copied on input and access. Cyclic arrays
 * are rejected. Other values are retained by reference and must remain effectively immutable;
 * this includes collections or application objects used as individual binding values. Equality uses
 * Objects.deepEquals: arrays compare by contents, other values use equals, and numeric types are not
 * converted. Explicit null is a supplied value, distinct from a missing map key/list position.</p>
 */
public final class ParameterizedSql {
    private final String sql;
    private final boolean hasBindings;
    private final Map<String, Object> named;
    private final List<Object> positional;
    private final Map<Integer, Object> ordinal;

    private ParameterizedSql(String sql, Map<String, ?> named, List<?> positional, Map<Integer, ?> ordinal) {
        this.sql = Objects.requireNonNull(sql, "sql");
        this.hasBindings = named != null || positional != null || ordinal != null;
        this.named = named == null ? Map.of() : copyMap(named);
        this.positional = positional == null ? List.of() : copyList(positional);
        this.ordinal = ordinal == null ? Map.of() : copyMap(ordinal);
        this.named.keySet().forEach(BindingValues::name);
        this.ordinal.keySet().forEach(BindingValues::position);
    }

    /**
     * Supplies SQL without binding information, just like String-only evaluation.
     *
     * @param sql non-null SQL
     * @return SQL-only input; binding checks fail with BINDINGS_UNAVAILABLE
     */
    public static ParameterizedSql of(String sql) {
        return new ParameterizedSql(sql, null, null, null);
    }

    /**
     * Supplies all three binding namespaces explicitly. Empty containers mean supplied but empty.
     *
     * @param sql        non-null SQL
     * @param named      canonical named slots, including null values
     * @param positional anonymous values in SQM traversal order, including null values
     * @param ordinal    explicit positive SQL indices, including sparse indices and null values
     * @return a snapshot of the input
     */
    public static ParameterizedSql of(String sql, Map<String, ?> named, List<?> positional, Map<Integer, ?> ordinal) {
        return new ParameterizedSql(sql, Objects.requireNonNull(named), Objects.requireNonNull(positional), Objects.requireNonNull(ordinal));
    }

    /**
     * Supplies named bindings; anonymous and ordinal binding namespaces are empty.
     *
     * @param sql    non-null SQL
     * @param values canonical named slots, including null values
     * @return a snapshot of the input
     */
    public static ParameterizedSql named(String sql, Map<String, ?> values) {
        return of(sql, values, List.of(), Map.of());
    }

    /**
     * Supplies anonymous bindings; named and ordinal binding namespaces are empty.
     *
     * @param sql    non-null SQL
     * @param values values in SQM traversal order; first list element is position 1
     * @return a snapshot of the input
     */
    public static ParameterizedSql positional(String sql, List<?> values) {
        return of(sql, Map.of(), values, Map.of());
    }

    /**
     * Supplies explicit ordinal bindings; named and anonymous binding namespaces are empty.
     *
     * @param sql    non-null SQL
     * @param values slots keyed by the positive index written in SQL, with no gap filling
     * @return a snapshot of the input
     */
    public static ParameterizedSql ordinal(String sql, Map<Integer, ?> values) {
        return of(sql, Map.of(), List.of(), values);
    }

    private static <K> Map<K, Object> copyMap(Map<K, ?> source) {
        var copy = new LinkedHashMap<K, Object>();
        source.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "binding key"), BindingValues.copy(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static List<Object> copyList(List<?> source) {
        var copy = new ArrayList<>(source.size());
        source.forEach(value -> copy.add(BindingValues.copy(value)));
        return Collections.unmodifiableList(copy);
    }

    /**
     * Returns the original SQL text.
     *
     * @return non-null unmodified SQL
     */
    public String sql() {
        return sql;
    }

    /**
     * Reports whether binding containers were explicitly supplied.
     *
     * @return true even for explicitly empty containers; false for SQL-only input
     */
    public boolean hasBindings() {
        return hasBindings;
    }

    /**
     * Returns the supplied named slots.
     *
     * @return an immutable map with defensive copies of supported mutable values
     */
    public Map<String, Object> namedBindings() {
        return copyMap(named);
    }

    /**
     * Returns the supplied anonymous slots in SQM traversal order.
     *
     * @return an immutable list with defensive copies of supported mutable values
     */
    public List<Object> positionalBindings() {
        return copyList(positional);
    }

    /**
     * Returns the supplied explicit ordinal slots.
     *
     * @return an immutable map with defensive copies of supported mutable values
     */
    public Map<Integer, Object> ordinalBindings() {
        return copyMap(ordinal);
    }

    /**
     * @return a summary without SQL text or binding values
     */
    @Override
    public String toString() {
        return "ParameterizedSql[hasBindings=" + hasBindings + ", named=" + named.size()
            + ", positional=" + positional.size() + ", ordinal=" + ordinal.size() + "]";
    }
}
