package org.owl4agents.reasoner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.model.ReasonerSelectionResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.6 D2 task 2.7: size-aware AutoReasonerSelector — large ontology fallback to ELK.
 *
 * <p>Scenario: 32K-class non-EL ontology (e.g. HPO/Mondo profile-detected as
 * OWL 2 DL), no explanation requested, no explicit user override. The selector
 * MUST return {@code "ELK"} to avoid HermiT OOM. Rationale must mention the
 * class count and the ELK-first strategy. {@code errorCode} must be null
 * (success path).</p>
 */
@DisplayName("v0.8.6 D2 task 2.7: AutoReasonerSelector large ontology fallback to ELK")
class AutoReasonerSelectorLargeOntologyFallbackTest {

    @Test
    @DisplayName("select(\"OWL 2 DL\", false, 32_000, false) returns \"ELK\" (size-aware fallback)")
    void largeOntologyFallbackToElk() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 DL", false, 32_000, false);

        assertEquals("ELK", result.reasonerName(),
            "Large non-EL ontology (>20K classes) must auto-select ELK to avoid HermiT OOM");
        assertEquals("OWL 2 DL", result.detectedProfile(),
            "detectedProfile must be preserved in the result");
        assertNull(result.errorCode(),
            "Size-aware ELK fallback is a success path — errorCode must be null");
        assertNotNull(result.selectionRationale(),
            "selectionRationale must be populated");
        assertTrue(result.selectionRationale().contains("32_000") || result.selectionRationale().contains("32000")
                || result.selectionRationale().contains("classCount=32000"),
            "Rationale must mention the class count; was: " + result.selectionRationale());
        assertTrue(result.selectionRationale().contains("ELK"),
            "Rationale must mention ELK as the selected reasoner");
    }

    @Test
    @DisplayName("Boundary: classCount=20_001 (just over threshold) triggers ELK fallback")
    void boundaryJustOverThreshold() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 DL", false, 20_001, false);
        assertEquals("ELK", result.reasonerName(),
            "classCount > 20_000 (boundary just over) must trigger ELK fallback");
    }

    @Test
    @DisplayName("OWL 2 Full profile with 32K classes also triggers ELK fallback (not OWL 2 EL)")
    void largeOwl2FullFallbackToElk() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 Full", false, 32_000, false);
        assertEquals("ELK", result.reasonerName(),
            "Large non-EL ontology (OWL 2 Full) must also fall back to ELK");
    }
}
