package org.owl4agents.reasoner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.model.ReasonerSelectionResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.6 D2 task 2.11: size-aware AutoReasonerSelector — explicit override
 * bypasses the size-aware branch for ANY non-auto reasoner.
 *
 * <p>Scenario: 32K-class non-EL ontology, user explicitly specified a reasoner
 * (any non-{@code "auto"} name). The selector MUST honor the user's explicit
 * choice and bypass the size-aware branch entirely — applies to hermit,
 * openllet, elk (not just openllet). The user accepts the OOM risk.</p>
 *
 * <p>Note: {@code AutoReasonerSelector.select()} does not know the user-specified
 * reasoner name (only the profile and explanation flag). The bypass is verified
 * by observing that the legacy PROFILE_REASONER_MAP lookup runs uninterrupted:
 * <ul>
 *   <li>{@code select("OWL 2 DL", true, 32_000, true)} → legacy path with
 *       explanationRequested=true → "Openllet" (proves size-aware error branch
 *       was bypassed — without override, this would have returned
 *       REASONER_EXPLANATION_UNSUPPORTED_FOR_LARGE_ONTOLOGY).</li>
 *   <li>{@code select("OWL 2 DL", false, 32_000, true)} → legacy path with
 *       explanationRequested=false → PROFILE_REASONER_MAP["OWL 2 DL"] = "HermiT"
 *       (proves size-aware ELK fallback was bypassed — without override, this
 *       would have returned "ELK").</li>
 * </ul>
 * The {@code WARN} log mentioning the reasoner name is emitted by the caller
 * ({@code ReasonerServiceImpl.resolveReasonerName}), which knows the user's
 * explicit choice — see that method's tests.</p>
 */
@DisplayName("v0.8.6 D2 task 2.11: AutoReasonerSelector explicitOverride bypasses size-aware branch")
class AutoReasonerSelectorExplicitOverrideTest {

    @Test
    @DisplayName("select(\"OWL 2 DL\", true, 32_000, true) (explicit openllet) returns \"Openllet\" — bypasses size-aware error")
    void explicitOpenlletBypassesSizeAwareError() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 DL", true, 32_000, true);

        assertEquals("Openllet", result.reasonerName(),
            "Explicit override (explanation=true) must bypass the size-aware error branch"
                + " and return Openllet (legacy explanation path)");
        assertNull(result.errorCode(),
            "Success path — errorCode must be null (override bypassed the error)");
        assertEquals("OWL 2 DL", result.detectedProfile());
    }

    @Test
    @DisplayName("select(\"OWL 2 DL\", false, 32_000, true) (explicit hermit) returns \"HermiT\" — bypasses size-aware ELK fallback")
    void explicitHermitBypassesSizeAwareElkFallback() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 DL", false, 32_000, true);

        assertEquals("HermiT", result.reasonerName(),
            "Explicit override (explanation=false) must bypass the size-aware ELK fallback"
                + " and return HermiT (legacy PROFILE_REASONER_MAP for OWL 2 DL)");
        assertNull(result.errorCode(),
            "Success path — errorCode must be null (override bypassed the error)");
        assertEquals("OWL 2 DL", result.detectedProfile());
    }

    @Test
    @DisplayName("Explicit override on OWL 2 EL profile still returns ELK (legacy mapping)")
    void explicitOverrideOnOwl2ElReturnsElk() {
        // OWL 2 EL already maps to ELK in the legacy table — override just
        // confirms the bypass doesn't accidentally change EL ontologies.
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 EL", false, 32_000, true);
        assertEquals("ELK", result.reasonerName(),
            "OWL 2 EL profile maps to ELK in legacy table — override doesn't change EL ontologies");
    }

    @Test
    @DisplayName("Explicit override with classCount below threshold behaves like legacy (no special branch)")
    void explicitOverrideBelowThreshold() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 DL", false, 500, true);
        assertEquals("HermiT", result.reasonerName(),
            "Override + small ontology → legacy HermiT (size-aware branch not triggered either way)");
    }
}
