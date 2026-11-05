package io.assertsql;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatchTest {
    @Test
    void emptyDiagnosticsMeanSuccess() {
        var result = Match.of(List.of());

        assertTrue(result.matches());
        assertEquals("SQL matched all expectations.", result.describe());
        assertDoesNotThrow(result::assertMatches);
    }

    @Test
    void preservesAllDiagnosticsInOrderAndThrowsStandardAssertionError() {
        var result = Match.of(
            List.of(new Mismatch("WRONG_KIND", "", "Expected SELECT."), new Mismatch("STAR", "SELECT.items[0]", "Unexpected wildcard."))
        );

        assertFalse(result.matches());
        assertEquals("""
            SQL did not match expectations:
            - [WRONG_KIND] Expected SELECT.
            - [STAR] SELECT.items[0]: Unexpected wildcard.""", result.describe());
        var error = assertThrows(AssertionError.class, result::assertMatches);
        assertEquals(result.describe(), error.getMessage());
    }

    @Test
    void protectsDiagnosticsFromMutation() {
        var diagnostics = new ArrayList<Mismatch>();
        diagnostics.add(new Mismatch("KIND", "", "Expected SELECT."));
        var result = Match.of(diagnostics);
        diagnostics.clear();

        assertFalse(result.matches());
        assertThrows(UnsupportedOperationException.class, () -> result.mismatches().clear());
    }

    @Test
    void rejectsInvalidDiagnostics() {
        assertThrows(NullPointerException.class, () -> Match.of(null));
        assertThrows(NullPointerException.class, () -> Match.of(Collections.singletonList(null)));
        assertThrows(NullPointerException.class, () -> new Mismatch(null, "", "message"));
        assertThrows(NullPointerException.class, () -> new Mismatch("CODE", null, "message"));
        assertThrows(NullPointerException.class, () -> new Mismatch("CODE", "", null));
        assertThrows(IllegalArgumentException.class, () -> new Mismatch(" ", "", "message"));
        assertThrows(IllegalArgumentException.class, () -> new Mismatch("CODE", "", " "));
    }
}
