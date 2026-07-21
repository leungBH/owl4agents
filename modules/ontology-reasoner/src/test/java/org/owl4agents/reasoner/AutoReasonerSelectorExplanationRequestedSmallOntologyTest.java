package org.owl4agents.reasoner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.model.ReasonerSelectionResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.6 D2 task 2.9: size-aware AutoReasonerSelector — small ontology
 * explanation path keeps Openllet.
 *
 * <p>Scenario: 500-class ontology profile-detected as OWL 2 DL, explanation
 * requested, no explicit user override. The size-aware branch must NOT
 * trigger (classCount ≤ 20K); the selector returns {@code "Openllet"} for
 * explanation support. {@code errorCode} must be null.</p>
 */
@DisplayName("v0.8.6 D2 task 2.9: AutoReasonerSelector small ontology + explanation → Openllet")
class AutoReasonerSelectorExplanationRequestedSmallOntologyTest {

    @Test
    @DisplayName("select(\"OWL 2 DL\", true, 500, false) returns \"Openllet\" (explanation supported, size-aware branch not triggered)")
    void smallOntologyExplanationSelectsOpenllet() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 DL", true, 500, false);

        assertEquals("Openllet", result.reasonerName(),
            "Small ontology with explanation requested must select Openllet (unchanged behavior)");
        assertEquals("OWL 2 DL", result.detectedProfile());
        assertNull(result.errorCode(),
            "Success path — errorCode must be null");
        assertNotNull(result.selectionRationale());
    }

    @Test
    @DisplayName("Boundary: classCount=20_000 (exactly at threshold) with explanation → Openllet (no size-aware)")
    void boundaryAtThresholdWithExplanation() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 DL", true, 20_000, false);
        assertEquals("Openllet", result.reasonerName(),
            "classCount == 20_000 (not >) must not trigger size-aware branch — Openllet is selected");
        assertNull(result.errorCode());
    }
}
