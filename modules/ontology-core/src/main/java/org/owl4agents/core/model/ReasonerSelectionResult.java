package org.owl4agents.core.model;

import org.owl4agents.core.ErrorCode;

/**
 * Result of OWL profile-based auto reasoner selection.
 * Contains the selected reasoner name, the detected OWL profile, and selection rationale.
 *
 * <p>v0.8.6 D2: an <em>error variant</em> is introduced for the size-aware
 * branch in {@code AutoReasonerSelector.select(profile, explanationRequested,
 * classCount, explicitOverride)}. When the selector cannot satisfy the
 * request (e.g. explanation requested on a &gt;20K-class non-EL ontology),
 * {@code reasonerName} is {@code null}, {@code errorCode} is non-null, and
 * {@code diagnosticMessage} carries guidance for the caller. Existing
 * success-path callers continue to use the 3-arg canonical constructor;
 * {@code errorCode} and {@code diagnosticMessage} default to {@code null}.</p>
 */
public record ReasonerSelectionResult(
    String reasonerName,
    String detectedProfile,
    String selectionRationale,
    ErrorCode errorCode,
    String diagnosticMessage
) {
    /**
     * Canonical success-path constructor (backward compatible).
     * {@code errorCode} and {@code diagnosticMessage} are {@code null}.
     */
    public ReasonerSelectionResult(String reasonerName, String detectedProfile, String selectionRationale) {
        this(reasonerName, detectedProfile, selectionRationale, null, null);
    }

    /**
     * Convenience factory for an error selection result. {@code reasonerName}
     * is {@code null} — the caller MUST inspect {@code errorCode()} and
     * {@code diagnosticMessage()}.
     */
    public static ReasonerSelectionResult error(ErrorCode errorCode, String detectedProfile, String diagnosticMessage) {
        return new ReasonerSelectionResult(null, detectedProfile, diagnosticMessage, errorCode, diagnosticMessage);
    }
}
