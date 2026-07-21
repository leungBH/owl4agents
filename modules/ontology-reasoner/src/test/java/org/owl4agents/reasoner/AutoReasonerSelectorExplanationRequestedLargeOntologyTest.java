package org.owl4agents.reasoner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.model.ReasonerSelectionResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.6 D2 task 2.10: size-aware AutoReasonerSelector — large ontology with
 * explanation returns REASONER_EXPLANATION_UNSUPPORTED_FOR_LARGE_ONTOLOGY.
 *
 * <p>Scenario: 32K-class non-EL ontology (e.g. HPO), explanation requested,
 * no explicit user override. Openllet (the only explanation-capable reasoner)
 * would OOM at this scale. The selector MUST return an error result with
 * {@code errorCode = REASONER_EXPLANATION_UNSUPPORTED_FOR_LARGE_ONTOLOGY}
 * and a diagnostic message guiding the user to either disable explanation,
 * use a smaller ontology, or explicitly set {@code reasoner=openllet}
 * (accepting OOM risk via {@code explicitOverride=true}).</p>
 */
@DisplayName("v0.8.6 D2 task 2.10: AutoReasonerSelector large ontology + explanation → error")
class AutoReasonerSelectorExplanationRequestedLargeOntologyTest {

    @Test
    @DisplayName("select(\"OWL 2 DL\", true, 32_000, false) returns error REASONER_EXPLANATION_UNSUPPORTED_FOR_LARGE_ONTOLOGY")
    void largeOntologyExplanationReturnsError() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 DL", true, 32_000, false);

        assertNull(result.reasonerName(),
            "Error result must have null reasonerName (no reasoner can satisfy the request)");
        assertEquals(ErrorCode.REASONER_EXPLANATION_UNSUPPORTED_FOR_LARGE_ONTOLOGY,
            result.errorCode(),
            "Error code must be REASONER_EXPLANATION_UNSUPPORTED_FOR_LARGE_ONTOLOGY");
        assertNotNull(result.diagnosticMessage(),
            "diagnosticMessage must be populated to guide the user");
        assertEquals("OWL 2 DL", result.detectedProfile(),
            "detectedProfile must still be preserved in the error result");
    }

    @Test
    @DisplayName("Diagnostic message mentions classCount, OOM risk, and the explicit-override escape hatch")
    void diagnosticMessageGuidesUser() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 DL", true, 32_000, false);

        String diag = result.diagnosticMessage();
        assertNotNull(diag);
        assertTrue(diag.contains("32_000") || diag.contains("32000") || diag.contains("classCount=32000"),
            "Diagnostic must mention the class count; was: " + diag);
        assertTrue(diag.contains("Openllet"),
            "Diagnostic must mention Openllet (the only explanation-capable reasoner); was: " + diag);
        assertTrue(diag.contains("OOM"),
            "Diagnostic must mention OOM risk; was: " + diag);
        assertTrue(diag.contains("reasoner=openllet"),
            "Diagnostic must mention the explicit-override escape hatch (reasoner=openllet); was: " + diag);
    }

    @Test
    @DisplayName("OWL 2 Full profile + 30K classes + explanation also returns the error (not OWL 2 EL)")
    void largeOwl2FullWithExplanationReturnsError() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 Full", true, 30_000, false);
        assertEquals(ErrorCode.REASONER_EXPLANATION_UNSUPPORTED_FOR_LARGE_ONTOLOGY,
            result.errorCode(),
            "Large non-EL ontology (OWL 2 Full) with explanation must also return the error");
        assertNull(result.reasonerName());
    }
}
