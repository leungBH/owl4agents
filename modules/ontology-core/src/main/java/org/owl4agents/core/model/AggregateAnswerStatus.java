package org.owl4agents.core.model;

/**
 * Aggregate answer-level verification status for v0.5 claim workflows.
 * Priority order (highest wins): invalid_input > contradicted > insufficient_evidence
 * > out_of_scope > partially_verified > verified.
 */
public enum AggregateAnswerStatus {
    INVALID_INPUT("invalid_input"),
    CONTRADICTED("contradicted"),
    INSUFFICIENT_EVIDENCE("insufficient_evidence"),
    OUT_OF_SCOPE("out_of_scope"),
    PARTIALLY_VERIFIED("partially_verified"),
    VERIFIED("verified");

    private final String jsonName;

    AggregateAnswerStatus(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}