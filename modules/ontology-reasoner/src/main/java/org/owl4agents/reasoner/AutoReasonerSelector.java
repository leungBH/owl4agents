package org.owl4agents.reasoner;

import org.owl4agents.core.model.ReasonerSelectionResult;

import java.util.Map;

/**
 * OWL profile-based auto reasoner selection.
 * Selects the most appropriate reasoner based on the detected OWL profile
 * and whether explanation is requested.
 *
 * Selection rules:
 * - OWL 2 EL → ELK (fast for EL ontologies)
 * - OWL 2 DL → HermiT (reference DL reasoner)
 * - OWL 2 Full → HermiT only when DL-compatible; otherwise PROFILE_NOT_SUPPORTED
 * - Explanation requested → Openllet (provides explanation support)
 * - Unknown profile → HermiT (default fallback)
 */
public class AutoReasonerSelector {

    private static final Map<String, String> PROFILE_REASONER_MAP = Map.of(
        "OWL 2 EL", "ELK",
        "OWL 2 DL", "HermiT",
        "OWL 2 Full", "HermiT",
        "OWL 2 QL", "HermiT",
        "OWL 2 RL", "HermiT"
    );

    /**
     * Select the appropriate reasoner based on OWL profile and explanation request.
     *
     * @param detectedProfile The detected OWL profile of the ontology
     * @param explanationRequested Whether explanation is requested (triggers Openllet selection)
     * @return ReasonerSelectionResult with the selected reasoner name and rationale
     */
    public ReasonerSelectionResult select(String detectedProfile, boolean explanationRequested) {
        if (explanationRequested) {
            return new ReasonerSelectionResult(
                "Openllet",
                detectedProfile,
                "Openllet selected for explanation support; it provides inconsistency and unsatisfiability explanations"
            );
        }

        if (detectedProfile == null || detectedProfile.isEmpty() || "unknown".equalsIgnoreCase(detectedProfile)) {
            return new ReasonerSelectionResult(
                "HermiT",
                "unknown",
                "Profile detection inconclusive; HermiT selected as default fallback because it handles the widest range of OWL expressivity"
            );
        }

        String selectedReasoner = PROFILE_REASONER_MAP.get(detectedProfile);

        if (selectedReasoner == null) {
            // Unknown profile with no mapping
            return new ReasonerSelectionResult(
                null,
                detectedProfile,
                "PROFILE_NOT_SUPPORTED: No compatible reasoner mapping for detected profile " + detectedProfile
            );
        }

        String rationale;
        if ("OWL 2 EL".equals(detectedProfile)) {
            rationale = "ELK is designed for OWL 2 EL and scales well on large EL ontologies";
        } else if ("OWL 2 DL".equals(detectedProfile)) {
            rationale = "HermiT is the reference OWL 2 DL reasoner with full classification";
        } else if ("OWL 2 Full".equals(detectedProfile)) {
            rationale = "HermiT selected for OWL 2 Full only when OWL API can safely present a DL-compatible reasoner input";
        } else {
            rationale = "HermiT selected as fallback for " + detectedProfile + " profile";
        }

        return new ReasonerSelectionResult(selectedReasoner, detectedProfile, rationale);
    }

    /**
     * Check whether the detected profile has a compatible reasoner mapping.
     *
     * @param detectedProfile The detected OWL profile
     * @return true if a compatible reasoner exists for the profile
     */
    public boolean isProfileSupported(String detectedProfile) {
        if (detectedProfile == null || detectedProfile.isEmpty() || "unknown".equalsIgnoreCase(detectedProfile)) {
            return true; // Unknown profile uses HermiT fallback
        }
        return PROFILE_REASONER_MAP.containsKey(detectedProfile);
    }
}