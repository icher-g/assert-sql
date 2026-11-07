package io.assertsql;

/**
 * Controls value disclosure in library-generated diagnostics, independently of assertion matching.
 */
public enum Redaction {
    /**
     * Default: retain SQL literal detail and hide supplied and expected binding values.
     */
    BINDINGS,
    /**
     * Hide SQL literal and binding values. Unrenderable, malformed, or custom detail is omitted when necessary.
     */
    ALL,
    /**
     * Explicitly allow SQL literals and expected/actual binding values in diagnostic messages.
     */
    NONE
}
