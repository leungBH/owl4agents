package org.owl4agents.core.model;

/**
 * Aggregate answer-level verification status for v0.5 claim workflows.
 * Priority order (highest wins): invalid_input > contradicted > insufficient_evidence
 * > out_of_scope > partially_verified > supported.
 *
 * <p><b>v0.9.0 D4 (BREAKING):</b> The {@link #VERIFIED} enum constant's JSON
 * name changed from {@code "verified"} (v0.8.x) to {@code "supported"} to
 * maintain consistency with {@code Verdict.SUPPORTED}. Downstream consumers
 * checking {@code aggregateStatus == "verified"} MUST update to
 * {@code aggregateStatus == "supported"}.</p>
 */
public enum AggregateAnswerStatus {
    INVALID_INPUT("invalid_input"),
    CONTRADICTED("contradicted"),
    INSUFFICIENT_EVIDENCE("insufficient_evidence"),
    OUT_OF_SCOPE("out_of_scope"),
    PARTIALLY_VERIFIED("partially_verified"),
    VERIFIED("supported");

    private final String jsonName;

    AggregateAnswerStatus(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}