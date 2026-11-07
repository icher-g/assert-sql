package io.assertsql;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A diagnostic describing an unmet expectation.
 *
 * @param code         machine-readable diagnostic code
 * @param path         statement or readable scope location; empty when not applicable or the SQL fragment provides sufficient context
 * @param message      human-readable explanation
 * @param descriptions immutable enclosing expectation descriptions, independent of code and path
 */
public record Mismatch(String code, String path, String message, List<String> descriptions) {

    /**
     * Creates a diagnostic without enclosing expectation descriptions.
     *
     * @param code    non-blank diagnostic code
     * @param path    non-null location
     * @param message non-blank explanation
     */
    public Mismatch(String code, String path, String message) {
        this(code, path, message, List.of());
    }

    /**
     * Creates a diagnostic.
     *
     * @param code         non-blank diagnostic code
     * @param path         non-null statement path
     * @param message      non-blank explanation
     * @param descriptions non-null descriptions with no null elements; defensively copied
     */
    public Mismatch {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
        descriptions = List.copyOf(descriptions);
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    Mismatch withDescription(String description) {
        if (description == null) {
            return this;
        }
        var labels = new ArrayList<String>();
        labels.add(description);
        labels.addAll(descriptions);
        return new Mismatch(code, path, message, labels);
    }
}
