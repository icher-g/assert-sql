package io.assertsql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

class StatementKindsTest {
    private static final List<String> KINDS = List.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE");
    private static final List<String> SQL = List.of(
        "WITH ids AS (SELECT id FROM users) SELECT id FROM ids UNION ALL SELECT id FROM archived",
        "INSERT INTO users (id) SELECT id FROM archived",
        "UPDATE users SET name = 'Ada' WHERE id IN (SELECT id FROM archived)",
        "DELETE FROM users WHERE id IN (SELECT id FROM archived)",
        "MERGE INTO users u USING incoming i ON u.id = i.id WHEN MATCHED THEN UPDATE SET name = i.name"
    );

    @ParameterizedTest
    @ValueSource(strings = {"SELECT", "INSERT", "UPDATE", "DELETE", "MERGE"})
    void kindChecksAreExclusiveAndIgnoreSelectsInsideDml(String expectedKind) {
        var builder = Expect.sql(POSTGRESQL);
        kind(builder, expectedKind);
        var expectation = builder.build();
        for (int index = 0; index < SQL.size(); index++) {
            var result = expectation.evaluate(SQL.get(index));
            if (KINDS.get(index).equals(expectedKind)) {
                assertTrue(result.matches(), result::describe);
            }
            else {
                assertFalse(result.matches());
                var mismatch = result.mismatches().getFirst();
                assertEquals("NOT_" + expectedKind, mismatch.code());
                assertTrue(mismatch.message().contains("but was " + KINDS.get(index)));
            }
        }
    }

    @Test
    void dmlCteBodiesHaveTheirOwnKindWhileTheOuterStatementRemainsSelect() {
        for (var sql : List.of(
            "INSERT INTO users (id) VALUES (1) RETURNING id",
            "UPDATE users SET name = 'Ada' RETURNING id",
            "DELETE FROM users WHERE id = 1 RETURNING id"
        )) {
            var expected = sql.substring(0, sql.indexOf(' '));
            Expect.sql(POSTGRESQL)
                .select()
                .cte("changed", query -> kind(query, expected))
                .build()
                .assertMatches("WITH changed AS (" + sql + ") SELECT id FROM changed");
        }
    }

    @Test
    void derivedAndExistsScopesCheckTheSelectedQueryRatherThanItsParentKind() {
        Expect.sql(POSTGRESQL)
            .update()
            .exists(query -> query
                .select()
                .table("archived")
            )
            .build()
            .assertMatches("UPDATE users SET name = 'Ada' WHERE EXISTS (SELECT id FROM archived)");
        Expect.sql(POSTGRESQL)
            .select()
            .derived("page", Expect.Builder::select)
            .build()
            .assertMatches("SELECT id FROM (SELECT id FROM users) page");
    }

    @Test
    void kindsComposeWithOrAndCollectionCountsUseTheSameClassification() {
        var writes = Expect.or(
            Expect.sql(POSTGRESQL)
                .insert()
                .build(),
            Expect.sql(POSTGRESQL)
                .update()
                .build()
        );
        Expect.statements(POSTGRESQL)
            .count(5)
            .matchingCount(writes, 2)
            .selectCount(1)
            .insertCount(1)
            .updateCount(1)
            .deleteCount(1)
            .mergeCount(1)
            .build()
            .assertMatches(SQL);
    }

    @Test
    void parseFailuresAreNotStatementKindMismatchesAndMissingScopesStillFail() {
        var malformed = Expect.sql(POSTGRESQL)
            .update()
            .build()
            .evaluate("UPDATE SET");
        assertEquals("PARSE_ERROR", malformed.mismatches().getFirst().code());
        var missing = Expect.sql(POSTGRESQL)
            .cte("missing", Expect.Builder::delete)
            .build()
            .evaluate("SELECT 1");
        assertEquals("SCOPE_NOT_FOUND", missing.mismatches().getFirst().code());
    }

    private static void kind(Expect.Builder builder, String kind) {
        switch (kind) {
            case "SELECT" -> builder.select();
            case "INSERT" -> builder.insert();
            case "UPDATE" -> builder.update();
            case "DELETE" -> builder.delete();
            case "MERGE" -> builder.merge();
            default -> throw new IllegalArgumentException(kind);
        }
    }
}
