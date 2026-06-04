package org.owl4agents.core.model;

/**
 * Reason categories for unknown verdicts in v0.3 claim verification.
 * Each category explains why a claim could not be verified as supported or contradicted.
 */
public enum UnknownReason {
    INSUFFICIENT_AXIOMS("insufficient_axioms"),
    MISSING_REASONING("missing_reasoning"),
    UNSUPPORTED_PROFILE("unsupported_profile"),
    UNSUPPORTED_CLAIM_TYPE("unsupported_claim_type"),
    AMBIGUOUS_ENTITY("ambiguous_entity"),
    MISSING_ENTITY("missing_entity"),
    SCOPE_UNAVAILABLE("scope_unavailable"),
    EVIDENCE_UNAVAILABLE("evidence_unavailable");

    private final String jsonName;

    UnknownReason(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}