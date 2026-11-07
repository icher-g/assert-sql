package io.assertsql;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** An immutable evaluation result with diagnostics in evaluation order. */
public interface Match {

    /**
     * Creates a result, defensively copying its diagnostics.
     *
     * @param mismatches non-null diagnostics with no null elements
     * @return an immutable result; an empty list represents success
     */
    static Match of(List<Mismatch> mismatches) {
        return new ImmutableMatch(List.copyOf(mismatches));
    }

    /**
     * Returns this evaluation's diagnostics.
     * @return immutable diagnostics in evaluation order
     */
    List<Mismatch> mismatches();

    /**
     * Reports whether the evaluation succeeded.
     * @return whether all expectations matched
     */
    default boolean matches() {
        return mismatches().isEmpty();
    }

    /**
     * Describes the evaluation outcome.
     * @return a readable success message or all mismatch diagnostics
     */
    default String describe() {
        if (matches()) {
            return "SQL matched all expectations.";
        }
        return "SQL did not match expectations:\n" + mismatches()
            .stream()
            .map(Match::format)
            .collect(Collectors.joining("\n"));
    }

    /**
     * Throws a standard assertion failure when the evaluation did not match.
     * @throws AssertionError if any expectation did not match
     */
    default void assertMatches() {
        if (!matches()) {
            throw new AssertionError(describe());
        }
    }

    private static String format(Mismatch mismatch) {
        var labels = mismatch.descriptions().stream()
            .map(label -> "[" + label + "] ")
            .collect(Collectors.joining());
        var path = mismatch.path().isEmpty() ? "" : mismatch.path() + ": ";
        return "- [" + mismatch.code() + "] " + path + labels + mismatch.message();
    }
}

record ImmutableMatch(List<Mismatch> mismatches) implements Match {
    ImmutableMatch {
        Objects.requireNonNull(mismatches, "mismatches");
    }
}
