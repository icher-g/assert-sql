package io.assertsql;

import io.sqm.core.Statement;
import io.sqm.core.dialect.SqlDialectId;
import io.sqm.parser.postgresql.spi.PostgresSpecs;
import io.sqm.parser.spi.ParseContext;
import io.sqm.render.ansi.spi.AnsiDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static io.sqm.core.dialect.SqlDialectId.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

class StructuralEqualityTest {
    @Test
    void ignoresFormattingCommentsKeywordCaseAndOptionalSemicolon() {
        Expect.sql(POSTGRESQL)
            .structurallyEquals("SELECT id, name FROM users WHERE active = true")
            .build()
            .assertMatches("select id, /* projection */ name\nfrom users -- source\nwhere active = TRUE;");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT name, id FROM users WHERE active = true",
        "SELECT id, name FROM users WHERE active = false",
        "SELECT id, name FROM users WHERE active <> true",
        "SELECT id, name FROM users u WHERE active = true",
        "SELECT id, name FROM \"users\" WHERE active = true",
        "SELECT id, name FROM USERS WHERE active = true",
        "SELECT id FROM users WHERE active = true",
        "SELECT id, name FROM users WHERE active = true ORDER BY id"
    })
    void preservesMeaningfulStructureAndIdentifierSpelling(String actual) {
        var result = Expect.sql(POSTGRESQL).structurallyEquals("SELECT id, name FROM users WHERE active = true").build().evaluate(actual);
        assertFalse(result.matches());
        assertEquals("STRUCTURE_MISMATCH", result.mismatches().getFirst().code());
        assertEquals("", result.mismatches().getFirst().path());
    }

    @Test
    void locatesTheDifferingLiteral() {
        var result = Expect.sql(POSTGRESQL)
            .structurallyEquals("SELECT id FROM users WHERE id = 1")
            .build()
            .evaluate("SELECT id FROM users WHERE id = 2");
        assertEquals("", result.mismatches().getFirst().path());
        assertTrue(result.describe().contains("Expected SQL <id = 1> but was <id = 2>"), result::describe);
    }

    @Test
    void rendersPaginationAsAClauseRatherThanAnIsolatedValue() {
        var result = Expect.sql(POSTGRESQL)
            .structurallyEquals("SELECT id FROM users LIMIT 20 OFFSET 40")
            .build()
            .evaluate("SELECT id FROM users LIMIT 10 OFFSET 40");
        assertEquals("", result.mismatches().getFirst().path());
        assertEquals("""
            SQL did not match expectations:
            - [STRUCTURE_MISMATCH] Expected SQL <LIMIT 20 OFFSET 40> but was <LIMIT 10 OFFSET 40>.""", result.describe());
    }

    @Test
    void rendersAbsentClausesAndUnequalProjectionLengths() {
        var missingWhere = Expect.sql(POSTGRESQL).structurallyEquals("SELECT id FROM users WHERE id = 1").build().evaluate("SELECT id FROM users");
        assertTrue(missingWhere.describe().contains("Expected SQL <id = 1> but was <absent>"), missingWhere::describe);
        var projection = Expect.sql(POSTGRESQL).exactProjection("id", "name").build().evaluate("SELECT id FROM users");
        assertEquals("PROJECTION_MISMATCH", projection.mismatches().getFirst().code());
        assertTrue(projection.describe().contains("Expected SQL <id, name> but was <id>"), projection::describe);
        assertEquals("", projection.mismatches().getFirst().path());
        var ordering = Expect.sql(POSTGRESQL).orderBy("id DESC").build().evaluate("SELECT id FROM users ORDER BY id ASC");
        assertTrue(ordering.describe().contains("Expected SQL <id DESC> but was <id ASC>"), ordering::describe);
        assertEquals("", ordering.mismatches().getFirst().path());
    }

    @Test
    void preservesWhitespaceAndEscapingInsideSqlValues() {
        var result = Expect.sql(POSTGRESQL)
            .structurallyEquals("SELECT id FROM users WHERE name = 'O''Brien  Jr'")
            .build()
            .evaluate("SELECT id FROM users WHERE name = 'O''Brien Jr'");
        assertTrue(result.describe().contains("name = 'O''Brien  Jr'"), result::describe);
        assertTrue(result.describe().contains("name = 'O''Brien Jr'"), result::describe);
    }

    @ParameterizedTest
    @CsvSource({
        "ansi, SELECT id FROM users WHERE id = 1, SELECT id FROM users WHERE id = 2, id = 1, id = 2",
        "postgresql, SELECT id FROM users WHERE name ILIKE 'a', SELECT id FROM users WHERE name ILIKE 'b', name ILIKE 'a', name ILIKE 'b'",
        "mysql, SELECT `id` FROM users, SELECT `name` FROM users, `id`, `name`",
        "sqlserver, SELECT TOP (20) [id] FROM users, SELECT TOP (10) [id] FROM users, TOP (20), TOP (10)",
        "oracle, SELECT id FROM users FETCH FIRST 20 ROWS ONLY, SELECT id FROM users FETCH FIRST 10 ROWS ONLY, 20, 10"
    })
    void rendersUsingTheSelectedDialect(String dialect, String expected, String actual, String expectedFragment, String actualFragment) {
        var result = Expect.sql(SqlDialectId.of(dialect)).structurallyEquals(expected).build().evaluate(actual);
        assertEquals("STRUCTURE_MISMATCH", result.mismatches().getFirst().code());
        assertTrue(result.describe().contains("Expected SQL <"), result::describe);
        assertEquals("", result.mismatches().getFirst().path());
        assertTrue(result.describe().contains(expectedFragment), result::describe);
        assertTrue(result.describe().contains(actualFragment), result::describe);
    }

    @Test
    void fallsBackToValuesWhenTheRendererCannotRenderTheSql() {
        var parser = ParseContext.of(new PostgresSpecs());
        var expected = parser.parse(Statement.class, "SELECT id FROM users WHERE name ILIKE 'a'").value();
        var actual = parser.parse(Statement.class, "SELECT id FROM users WHERE name ILIKE 'b'").value();
        // ANSI cannot render ILIKE. Failure to format the diagnostic must preserve the original mismatch.
        var difference = StructureDifference.between(expected, actual, new AnsiDialect());
        assertEquals("STRUCTURE_MISMATCH", difference.code());
        assertTrue(difference.path().contains("where"));
        assertEquals("Expected <a> but was <b>.", difference.message());
        var result = Match.of(List.of(difference));
        assertTrue(result.describe().contains("[STRUCTURE_MISMATCH] " + difference.path() + ": Expected <a> but was <b>."), result::describe);
    }

    @Test
    void reportsDifferentStatementKinds() {
        var result = Expect.sql(POSTGRESQL).structurallyEquals("SELECT id FROM users").build().evaluate("DELETE FROM users");
        assertFalse(result.matches());
        assertEquals("", result.mismatches().getFirst().path());
        assertTrue(result.describe().contains("Expected SQL <SELECT id FROM users> but was <DELETE FROM users>"), result::describe);
    }

    @Test
    void comparesDmlAndParameterNames() {
        Expect.sql(POSTGRESQL)
            .structurallyEquals("UPDATE users SET name = :name WHERE id = :id")
            .build()
            .assertMatches("update users set name = :name where id = :id");
        assertFalse(
            Expect.sql(POSTGRESQL).structurallyEquals("SELECT id FROM users WHERE id = :id").build().matches("SELECT id FROM users WHERE id = :other")
        );
    }
}
