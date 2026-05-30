package org.owl4agents.core.model;

/**
 * A warning in QA context output.
 */
public record QaWarning(
    String type,
    String message,
    String severity
) {
    public static final String TYPE_NO_MATCH = "no_match";
    public static final String SEVERITY_LOW_CONFIDENCE = "low_confidence";
}