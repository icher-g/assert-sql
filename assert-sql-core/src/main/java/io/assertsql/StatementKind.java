package io.assertsql;

import io.sqm.core.*;

/**
 * Shared outer-statement classification for single-statement and collection expectations.
 */
enum StatementKind {
    SELECT, INSERT, UPDATE, DELETE, MERGE;

    static StatementKind of(Statement statement) {
        return switch (ModelInspector.outerBody(statement)) {
            case Query ignored -> SELECT;
            case InsertStatement ignored -> INSERT;
            case UpdateStatement ignored -> UPDATE;
            case DeleteStatement ignored -> DELETE;
            case MergeStatement ignored -> MERGE;
        };
    }

    boolean matches(Statement statement) {
        return this == of(statement);
    }
}
