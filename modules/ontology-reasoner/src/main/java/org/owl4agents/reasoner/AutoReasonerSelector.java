package org.owl4agents.reasoner;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.model.ReasonerSelectionResult;

import java.util.Map;

/**
 * OWL profile-based auto reasoner selection.
 * Selects the most appropriate reasoner based on the detected OWL profile
 * and whether explanation is requested.
 *
 * <p>v0.8.6 D2: Size-aware selection. When {@code classCount > 20_000} and the
 * detected profile is not {@code "OWL 2 EL"}, the selector falls back to ELK
 * (for non-explanation paths) to avoid HermiT/Openllet OOM on large
 * ontologies (HPO 32K, Mondo 30K). When the user explicitly overrides the
 * reasoner choice ({@code explicitOverride=true}), the size-aware branch is
 * bypassed entirely — the caller ({@code ReasonerServiceImpl.resolveReasonerName})
 * logs a {@code WARN} with the explicit reasoner name, since this selector
 * does not know the user-specified name (only the profile and explanation
 * flag).</p>
 *
 * Selection rules (size-aware branch NOT triggered):
 * - OWL 2 EL → ELK (fast for EL ontologies)
 * - OWL 2 DL → HermiT (reference DL reasoner)
 * - OWL 2 Full → HermiT only when DL-compatible; otherwise PROFILE_NOT_SUPPORTED
 * - Explanation requested → Openllet (provides explanation support)
 * - Unknown profile → HermiT (default fallback)
 */
public class AutoReasonerSelector {

    /**
     * Threshold above which the size-aware branch activates. HPO (32K) and
     * Mondo (30K) are above; pizza (115), SOSA (84), ec-dw (~500) are below.
     */
    static final int LARGE_ONTOLOGY_CLASS_THRESHOLD = 20_000;

    private static final Map<String, String> PROFILE_REASONER_MAP = Map.of(
        "OWL 2 EL", "ELK",
        "OWL 2 DL", "HermiT",
        "OWL 2 Full", "HermiT",
        "OWL 2 QL", "HermiT",
        "OWL 2 RL", "HermiT"
    );

    /**
     * Select the appropriate reasoner based on OWL profile, explanation
     * request, ontology size, and whether the user explicitly overrode the
     * reasoner choice.
     *
     * <p>v0.8.6 D2 size-aware branch (only when {@code explicitOverride=false}):
     * if {@code classCount > 20_000} AND {@code detectedProfile != "OWL 2 EL"}:
     * <ul>
     *   <li>{@code explanationRequested=false}: return {@code "ELK"} (fast,
     *       low-memory). If ELK later rejects the ontology (non-EL axioms),
     *       the caller retries with HermiT via {@code ReasonerCallWrapper.call()}
     *       — see {@code ReasonerServiceImpl.checkSourceOntologyConsistency}
     *       task 3.11a.</li>
     *   <li>{@code explanationRequested=true}: return an error result with
     *       {@code errorCode = REASONER_EXPLANATION_UNSUPPORTED_FOR_LARGE_ONTOLOGY}.
     *       Openllet (the only explanation-capable reasoner) would OOM on
     *       20K+ class ontologies. The user must either disable explanation,
     *       use a smaller ontology, or explicitly set {@code reasoner=openllet}
     *       (with {@code explicitOverride=true}) accepting the OOM risk.</li>
     * </ul>
     *
     * <p>v0.8.6 D2 explicit override bypass: when {@code explicitOverride=true},
     * the size-aware branch is skipped entirely — applies to ANY non-auto
     * reasoner (hermit, openllet, elk). A {@code WARN} is logged when the
     * ontology is large; the user accepts the OOM risk.
     *
     * @param detectedProfile       The detected OWL profile of the ontology
     * @param explanationRequested  Whether explanation is requested (triggers Openllet selection)
     * @param classCount            Number of classes in the ontology signature
     *                              (0 if unknown — never triggers size-aware branch)
     * @param explicitOverride      {@code true} when the user explicitly specified
     *                              a non-{@code "auto"} reasoner — bypasses the
     *                              size-aware branch entirely
     * @return ReasonerSelectionResult with the selected reasoner name and rationale,
     *         or an error result with {@code errorCode} set when the request
     *         cannot be satisfied (e.g. explanation on a large non-EL ontology)
     */
    public ReasonerSelectionResult select(String detectedProfile, boolean explanationRequested,
                                           int classCount, boolean explicitOverride) {
        // v0.8.6 D2: Size-aware branch (only when user did NOT explicitly override).
        // Note: AutoReasonerSelector does not know the user-specified reasoner
        // name (only the profile and explanation flag), so the explicit-override
        // path delegates to the existing PROFILE_REASONER_MAP lookup. The
        // caller (ReasonerServiceImpl.resolveReasonerName) has already resolved
        // the user's explicit choice before invoking select() when override is set.
        if (!explicitOverride
                && classCount > LARGE_ONTOLOGY_CLASS_THRESHOLD
                && !"OWL 2 EL".equals(detectedProfile)) {
            if (!explanationRequested) {
                return new ReasonerSelectionResult(
                    "ELK",
                    detectedProfile,
                    "Large ontology (classCount=" + classCount + " > 20K); trying ELK first for speed");
            }
            String diagnostic = "Explanation requested on large ontology (classCount=" + classCount
                + " > 20K). Openllet would OOM. Either disable explanation, use a smaller ontology,"
                + " or explicitly specify reasoner=openllet (accepting OOM risk).";
            return ReasonerSelectionResult.error(
                ErrorCode.REASONER_EXPLANATION_UNSUPPORTED_FOR_LARGE_ONTOLOGY,
                detectedProfile,
                diagnostic);
        }

        // explicitOverride=true: bypass the size-aware branch and delegate to
        // the legacy PROFILE_REASONER_MAP lookup. The WARN log is emitted by
        // the caller (ReasonerServiceImpl.resolveReasonerName), which knows
        // the user-specified reasoner name; this selector only sees the profile
        // and explanation flag.
        return selectLegacy(detectedProfile, explanationRequested);
    }

    /**
     * Legacy selection logic (profile + explanation only, no size awareness).
     * Used by both the deprecated 2-arg {@link #select(String, boolean)} and
     * the new 4-arg {@link #select(String, boolean, int, boolean)} (after the
     * size-aware branch decides to delegate).
     */
    private ReasonerSelectionResult selectLegacy(String detectedProfile, boolean explanationRequested) {
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
     * Select the appropriate reasoner based on OWL profile and explanation request.
     *
     * <p>@deprecated since v0.8.6 D2 — use the 4-arg
     * {@link #select(String, boolean, int, boolean)} to enable size-aware
     * selection. This 2-arg overload delegates with {@code classCount=0} and
     * {@code explicitOverride=false} — never triggers the size-aware branch,
     * never bypasses. Removed in v1.0.0.</p>
     *
     * @param detectedProfile The detected OWL profile of the ontology
     * @param explanationRequested Whether explanation is requested (triggers Openllet selection)
     * @return ReasonerSelectionResult with the selected reasoner name and rationale
     */
    @Deprecated
    public ReasonerSelectionResult select(String detectedProfile, boolean explanationRequested) {
        return select(detectedProfile, explanationRequested, 0, false);
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
