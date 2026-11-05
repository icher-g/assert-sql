package io.assertsql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        assertTrue(result.mismatches().getFirst().path().startsWith("statement."));
    }

    @Test
    void locatesTheDifferingLiteral() {
        var result = Expect.sql(POSTGRESQL)
            .structurallyEquals("SELECT id FROM users WHERE id = 1")
            .build()
            .evaluate("SELECT id FROM users WHERE id = 2");
        assertTrue(result.mismatches().getFirst().path().contains("where"));
        assertTrue(result.describe().contains("Expected <1> but was <2>"));
    }

    @Test
    void reportsDifferentStatementKinds() {
        var result = Expect.sql(POSTGRESQL).structurallyEquals("SELECT id FROM users").build().evaluate("DELETE FROM users");
        assertFalse(result.matches());
        assertEquals("statement", result.mismatches().getFirst().path());
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
