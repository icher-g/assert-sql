package io.assertsql;

import java.util.Objects;

/**
 * A diagnostic describing an unmet expectation.
 *
 * @param code machine-readable diagnostic code
 * @param path location within the statement; empty when not applicable
 * @param message human-readable explanation
 */
public record Mismatch(String code, String path, String message) {

    /**
     * Creates a diagnostic.
     *
     * @param code non-blank diagnostic code
     * @param path non-null statement path
     * @param message non-blank explanation
     */
    public Mismatch {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
