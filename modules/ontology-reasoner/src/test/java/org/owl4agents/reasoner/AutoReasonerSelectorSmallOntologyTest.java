package org.owl4agents.reasoner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.model.ReasonerSelectionResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.6 D2 task 2.8: size-aware AutoReasonerSelector — small ontology keeps HermiT.
 *
 * <p>Scenario: 500-class ontology (e.g. ec-dw) profile-detected as OWL 2 DL,
 * no explanation requested, no explicit user override. The size-aware branch
 * must NOT trigger (classCount ≤ 20K); the selector returns the legacy
 * {@code "HermiT"} mapping for OWL 2 DL. {@code errorCode} must be null.</p>
 */
@DisplayName("v0.8.6 D2 task 2.8: AutoReasonerSelector small ontology keeps HermiT (unchanged)")
class AutoReasonerSelectorSmallOntologyTest {

    @Test
    @DisplayName("select(\"OWL 2 DL\", false, 500, false) returns \"HermiT\" (unchanged behavior)")
    void smallOntologySelectsHermiT() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 DL", false, 500, false);

        assertEquals("HermiT", result.reasonerName(),
            "Small OWL 2 DL ontology must auto-select HermiT (unchanged behavior)");
        assertEquals("OWL 2 DL", result.detectedProfile());
        assertNull(result.errorCode(),
            "Success path — errorCode must be null");
        assertNotNull(result.selectionRationale());
    }

    @Test
    @DisplayName("Boundary: classCount=20_000 (exactly at threshold) does NOT trigger size-aware branch")
    void boundaryExactlyAtThreshold() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 DL", false, 20_000, false);
        assertEquals("HermiT", result.reasonerName(),
            "classCount == 20_000 (exactly at threshold, not >) must NOT trigger ELK fallback");
    }

    @Test
    @DisplayName("Boundary: classCount=0 (unknown size) does NOT trigger size-aware branch")
    void zeroClassCountDoesNotTrigger() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 DL", false, 0, false);
        assertEquals("HermiT", result.reasonerName(),
            "classCount=0 (unknown) must not trigger size-aware branch — preserves legacy behavior");
    }
}
