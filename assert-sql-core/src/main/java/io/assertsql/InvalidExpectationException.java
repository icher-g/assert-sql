package io.assertsql;

/**
 * Marks only library configuration errors, so unexpected custom-check exceptions are never intercepted.
 */
final class InvalidExpectationException extends IllegalArgumentException {
    InvalidExpectationException(String message) {
        super(message);
    }
}
