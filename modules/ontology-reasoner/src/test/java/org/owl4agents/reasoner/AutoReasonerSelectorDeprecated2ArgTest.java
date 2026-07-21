package org.owl4agents.reasoner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.model.ReasonerSelectionResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.6 D2 task 2.12: deprecated 2-arg {@code select(profile, explanationRequested)}
 * delegates to {@code select(profile, explanationRequested, 0, false)}.
 *
 * <p>Scenario: caller uses the deprecated 2-arg overload. The selector MUST
 * delegate with {@code classCount=0} and {@code explicitOverride=false} —
 * never triggers the size-aware branch, never bypasses. Legacy behavior is
 * preserved for existing callers (e.g. {@code ReasonerServiceImpl.selectReasoner}).</p>
 */
@DisplayName("v0.8.6 D2 task 2.12: AutoReasonerSelector deprecated 2-arg select() delegates to 4-arg")
class AutoReasonerSelectorDeprecated2ArgTest {

    @Test
    @DisplayName("select(\"OWL 2 DL\", false) returns \"HermiT\" (delegates with classCount=0, explicitOverride=false)")
    void deprecated2ArgReturnsHermiT() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 DL", false);

        assertEquals("HermiT", result.reasonerName(),
            "Deprecated 2-arg select must delegate to 4-arg with classCount=0, explicitOverride=false"
                + " — never triggers size-aware branch — returns legacy HermiT for OWL 2 DL");
        assertNull(result.errorCode(),
            "Success path — errorCode must be null");
        assertEquals("OWL 2 DL", result.detectedProfile());
    }

    @Test
    @DisplayName("Deprecated 2-arg with explanation=true returns Openllet (legacy path, no size-aware)")
    void deprecated2ArgWithExplanationReturnsOpenllet() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 DL", true);
        assertEquals("Openllet", result.reasonerName(),
            "Deprecated 2-arg + explanation → legacy Openllet (no size-aware branch triggered)");
        assertNull(result.errorCode());
    }

    @Test
    @DisplayName("Deprecated 2-arg on OWL 2 EL returns ELK (legacy mapping, no size-aware)")
    void deprecated2ArgOwl2ElReturnsElk() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 EL", false);
        assertEquals("ELK", result.reasonerName(),
            "Deprecated 2-arg on OWL 2 EL → legacy ELK (PROFILE_REASONER_MAP)");
    }

    @Test
    @DisplayName("Deprecated 2-arg on unknown profile returns null reasoner (PROFILE_NOT_SUPPORTED)")
    void deprecated2ArgUnknownProfile() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 99 QX", false);
        assertNull(result.reasonerName(),
            "Deprecated 2-arg on truly unknown profile → null reasoner (PROFILE_NOT_SUPPORTED)");
        assertTrue(result.selectionRationale().contains("PROFILE_NOT_SUPPORTED"),
            "Rationale must mention PROFILE_NOT_SUPPORTED for unknown profiles");
    }
}
