package org.owl4agents.toolcall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-007 unit tests for {@link ToolCallExecutionStatus}.
 *
 * <p>Per spec.md (authoritative): three values {@code ok} / {@code timeout}
 * / {@code error}. The task prompt's 5-value variant (pending/running/success
 * /error/timeout) is NOT used; spec.md wins per OpenSpec workflow.</p>
 */
@DisplayName("TC-007 ToolCallExecutionStatus enum")
class ToolCallExecutionStatusTest {

    @Test
    @DisplayName("Enum exposes exactly 3 values per spec.md")
    void exactlyThreeValues() {
        assertEquals(3, ToolCallExecutionStatus.values().length,
            "Spec.md 'ToolCallValidationReport' requires executionStatus enum "
                + "(ok / timeout / error) — not the 5-value task prompt variant");
    }

    @Test
    @DisplayName("All three spec values are present")
    void allSpecValuesPresent() {
        assertNotNull(ToolCallExecutionStatus.valueOf("OK"));
        assertNotNull(ToolCallExecutionStatus.valueOf("TIMEOUT"));
        assertNotNull(ToolCallExecutionStatus.valueOf("ERROR"));
    }

    @Test
    @DisplayName("jsonName() returns lowercase form for parity")
    void jsonNameIsLowercase() {
        assertEquals("ok", ToolCallExecutionStatus.OK.jsonName());
        assertEquals("timeout", ToolCallExecutionStatus.TIMEOUT.jsonName());
        assertEquals("error", ToolCallExecutionStatus.ERROR.jsonName());
    }

    @Test
    @DisplayName("fromString() parses jsonName case-insensitively")
    void fromStringParsesJsonNames() {
        assertEquals(ToolCallExecutionStatus.OK,
            ToolCallExecutionStatus.fromString("ok"));
        assertEquals(ToolCallExecutionStatus.TIMEOUT,
            ToolCallExecutionStatus.fromString("TIMEOUT"));
        assertEquals(ToolCallExecutionStatus.ERROR,
            ToolCallExecutionStatus.fromString("error"));
    }

    @Test
    @DisplayName("fromString() defaults to ERROR (never OK) for unrecognized")
    void unknownDefaultsToError() {
        // Defensive: unrecognized status must never silently default to OK.
        assertEquals(ToolCallExecutionStatus.ERROR,
            ToolCallExecutionStatus.fromString("unknown"));
        assertEquals(ToolCallExecutionStatus.ERROR,
            ToolCallExecutionStatus.fromString(""));
        assertEquals(ToolCallExecutionStatus.ERROR,
            ToolCallExecutionStatus.fromString(null));
        // The 5-value task-prompt variants are NOT recognized (spec.md wins).
        assertEquals(ToolCallExecutionStatus.ERROR,
            ToolCallExecutionStatus.fromString("pending"));
        assertEquals(ToolCallExecutionStatus.ERROR,
            ToolCallExecutionStatus.fromString("running"));
        assertEquals(ToolCallExecutionStatus.ERROR,
            ToolCallExecutionStatus.fromString("success"));
    }
}
