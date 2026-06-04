package org.owl4agents.core.model;

/**
 * Verification verdicts for v0.3 claim verification.
 * Verdicts are resolved in this order: out_of_scope → supported → contradicted → unknown.
 */
public enum Verdict {
    OUT_OF_SCOPE("out_of_scope"),
    SUPPORTED("supported"),
    CONTRADICTED("contradicted"),
    UNKNOWN("unknown");

    private final String jsonName;

    Verdict(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}