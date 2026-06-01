package org.owl4agents.core.model;

/**
 * Reasoning evidence metadata for QA context.
 * Contains reasoner name, consistency status, inferred facts count,
 * and supporting axiom references when reasoning has been executed.
 */
public record ReasoningEvidence(
    String reasonerName,
    boolean consistent,
    int inferredFactCount,
    String consistencyStatus,
    String reportSummary
) {}