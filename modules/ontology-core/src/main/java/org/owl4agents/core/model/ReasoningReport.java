package org.owl4agents.core.model;

import java.util.Map;

/**
 * Reasoning report generated after each reasoning operation.
 * Stored as reasoning-report.json in the ontology workspace inferred directory.
 */
public record ReasoningReport(
    String ontologyId,
    String reasonerName,
    String owlProfile,
    boolean classificationStatus,
    Boolean realizationStatus,
    boolean consistencyStatus,
    TimingBreakdown timingBreakdown,
    int warningCount,
    Map<String, Integer> inferredAxiomCountsByType,
    ErrorDetails errorDetails
) {
    /**
     * Timing breakdown for reasoning operations in milliseconds.
     * Nested record — only used within ReasoningReport context.
     */
    public record TimingBreakdown(
        long initializationTimeMs,
        long classificationTimeMs,
        long realizationTimeMs,
        long totalTimeMs
    ) {}

    /**
     * Error details when reasoning fails. Null on success.
     * Nested record — only used within ReasoningReport context.
     */
    public record ErrorDetails(
        String errorCode,
        String message,
        String stackTrace
    ) {}
}