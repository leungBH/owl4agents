package org.owl4agents.toolcall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-007 unit tests for {@link ValidationDecision} (7-value completeness,
 * no boolean {@code valid} field, lowercase JSON name, defensive parser).
 */
@DisplayName("TC-007 ValidationDecision enum")
class ValidationDecisionTest {

    @Test
    @DisplayName("Enum exposes exactly 7 values")
    void exactlySevenValues() {
        assertEquals(7, ValidationDecision.values().length,
            "Decision enum must have exactly 7 values per spec TC-003");
    }

    @Test
    @DisplayName("All seven spec-mandated values are present")
    void allRequiredValuesPresent() {
        // Per spec "Validation Decision Enum"
        assertNotNull(ValidationDecision.valueOf("EXECUTE"));
        assertNotNull(ValidationDecision.valueOf("AUTO_REPAIR"));
        assertNotNull(ValidationDecision.valueOf("CLARIFY"));
        assertNotNull(ValidationDecision.valueOf("REQUEST_CONFIRMATION"));
        assertNotNull(ValidationDecision.valueOf("REJECT"));
        assertNotNull(ValidationDecision.valueOf("RETRY_VALIDATION"));
        assertNotNull(ValidationDecision.valueOf("SYSTEM_ERROR"));
    }

    @Test
    @DisplayName("jsonName() returns lowercase form for MCP/CLI/Java parity")
    void jsonNameIsLowercase() {
        assertEquals("execute", ValidationDecision.EXECUTE.jsonName());
        assertEquals("auto_repair", ValidationDecision.AUTO_REPAIR.jsonName());
        assertEquals("clarify", ValidationDecision.CLARIFY.jsonName());
        assertEquals("request_confirmation",
            ValidationDecision.REQUEST_CONFIRMATION.jsonName());
        assertEquals("reject", ValidationDecision.REJECT.jsonName());
        assertEquals("retry_validation",
            ValidationDecision.RETRY_VALIDATION.jsonName());
        assertEquals("system_error", ValidationDecision.SYSTEM_ERROR.jsonName());
    }

    @Test
    @DisplayName("fromString() parses jsonName values case-insensitively")
    void fromStringParsesJsonNames() {
        assertEquals(ValidationDecision.EXECUTE,
            ValidationDecision.fromString("execute"));
        assertEquals(ValidationDecision.AUTO_REPAIR,
            ValidationDecision.fromString("AUTO_REPAIR"));
        assertEquals(ValidationDecision.REQUEST_CONFIRMATION,
            ValidationDecision.fromString("request_confirmation"));
        assertEquals(ValidationDecision.SYSTEM_ERROR,
            ValidationDecision.fromString("system_error"));
    }

    @Test
    @DisplayName("fromString() defaults to SYSTEM_ERROR (never EXECUTE) for unknown")
    void unknownDefaultsToSystemError() {
        // Critical defensive guarantee: an unrecognized decision must never
        // silently default to EXECUTE (which would permit execution).
        assertEquals(ValidationDecision.SYSTEM_ERROR,
            ValidationDecision.fromString("unknown"));
        assertEquals(ValidationDecision.SYSTEM_ERROR,
            ValidationDecision.fromString(""));
        assertEquals(ValidationDecision.SYSTEM_ERROR,
            ValidationDecision.fromString(null));
        assertEquals(ValidationDecision.SYSTEM_ERROR,
            ValidationDecision.fromString("   "));
        // Spec explicitly forbids valid=true/false
        assertEquals(ValidationDecision.SYSTEM_ERROR,
            ValidationDecision.fromString("valid"));
        assertEquals(ValidationDecision.SYSTEM_ERROR,
            ValidationDecision.fromString("true"));
        assertEquals(ValidationDecision.SYSTEM_ERROR,
            ValidationDecision.fromString("false"));
    }

    @Test
    @DisplayName("Decision enum carries no boolean valid field")
    void noBooleanValidField() {
        // Spec TC-003: "The pipeline SHALL NOT use a boolean valid field"
        // Verify the enum itself does not expose any 'valid' accessor.
        for (ValidationDecision d : ValidationDecision.values()) {
            try {
                ValidationDecision.class.getMethod("valid");
                fail("ValidationDecision must not expose a 'valid()' method; "
                    + "spec TC-003 forbids boolean valid field");
            } catch (NoSuchMethodException expected) {
                // Expected: no valid() method exists.
            }
        }
    }
}
