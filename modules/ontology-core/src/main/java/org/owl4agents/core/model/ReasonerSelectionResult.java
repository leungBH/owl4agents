package org.owl4agents.core.model;

/**
 * Result of OWL profile-based auto reasoner selection.
 * Contains the selected reasoner name, the detected OWL profile, and selection rationale.
 */
public record ReasonerSelectionResult(
    String reasonerName,
    String detectedProfile,
    String selectionRationale
) {}