package org.owl4agents.toolcall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-007 unit tests for {@link RiskLevel} (4-value completeness, lowercase
 * JSON name, {@link RiskLevel#isHighRisk()} for design D14, defensive parser).
 */
@DisplayName("TC-007 RiskLevel enum")
class RiskLevelTest {

    @Test
    @DisplayName("Enum exposes exactly 4 ordered values")
    void exactlyFourValues() {
        assertEquals(4, RiskLevel.values().length);
        // Ordering per spec: LOW < MEDIUM < HIGH < CRITICAL
        assertSame(RiskLevel.LOW, RiskLevel.values()[0]);
        assertSame(RiskLevel.MEDIUM, RiskLevel.values()[1]);
        assertSame(RiskLevel.HIGH, RiskLevel.values()[2]);
        assertSame(RiskLevel.CRITICAL, RiskLevel.values()[3]);
    }

    @Test
    @DisplayName("jsonName() returns lowercase form for MCP/CLI/Java parity")
    void jsonNameIsLowercase() {
        assertEquals("low", RiskLevel.LOW.jsonName());
        assertEquals("medium", RiskLevel.MEDIUM.jsonName());
        assertEquals("high", RiskLevel.HIGH.jsonName());
        assertEquals("critical", RiskLevel.CRITICAL.jsonName());
    }

    @Test
    @DisplayName("fromString() parses jsonName case-insensitively")
    void fromStringParsesJsonNames() {
        assertEquals(RiskLevel.LOW, RiskLevel.fromString("low"));
        assertEquals(RiskLevel.MEDIUM, RiskLevel.fromString("MEDIUM"));
        assertEquals(RiskLevel.HIGH, RiskLevel.fromString("high"));
        assertEquals(RiskLevel.CRITICAL, RiskLevel.fromString("critical"));
    }

    @Test
    @DisplayName("fromString() defaults to MEDIUM for unrecognized (surfaces confirmation)")
    void unknownDefaultsToMedium() {
        // Defensive default per spec: unrecognized must NOT silently allow
        // (LOW) -> ?MEDIUM surfaces the high-risk confirmation gate.
        assertEquals(RiskLevel.MEDIUM, RiskLevel.fromString("unknown"));
        assertEquals(RiskLevel.MEDIUM, RiskLevel.fromString(""));
        assertEquals(RiskLevel.MEDIUM, RiskLevel.fromString(null));
    }

    @Test
    @DisplayName("isHighRisk() returns true only for HIGH and CRITICAL (design D14)")
    void isHighRiskOnlyForHighAndCritical() {
        assertFalse(RiskLevel.LOW.isHighRisk(),
            "LOW must not trigger high-risk confirmation gate");
        assertFalse(RiskLevel.MEDIUM.isHighRisk(),
            "MEDIUM must not trigger high-risk confirmation gate");
        assertTrue(RiskLevel.HIGH.isHighRisk(),
            "HIGH must trigger high-risk confirmation gate (D14)");
        assertTrue(RiskLevel.CRITICAL.isHighRisk(),
            "CRITICAL must trigger high-risk confirmation gate (D14)");
    }
}
