package org.owl4agents.core.model;

/**
 * Typed evidence kinds for v0.3 claim verification.
 * Each kind identifies the source and nature of the evidence item.
 */
public enum EvidenceKind {
    EXPLICIT_AXIOM("explicit_axiom"),
    INFERRED_AXIOM("inferred_axiom"),
    EXPLICIT_TRIPLE("explicit_triple"),
    INFERRED_TRIPLE("inferred_triple"),
    REASONING_REPORT("reasoning_report"),
    SCOPE_STATEMENT("scope_statement"),
    LITERAL_VALIDATION("literal_validation"),
    COUNTEREXAMPLE("counterexample");

    private final String jsonName;

    EvidenceKind(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}