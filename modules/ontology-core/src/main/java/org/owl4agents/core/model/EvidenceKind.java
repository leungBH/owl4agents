package org.owl4agents.core.model;

/**
 * Typed evidence kinds for v0.3 claim verification.
 * Each kind identifies the source and nature of the evidence item.
 *
 * <p>v0.8.5 adds three consistency-related kinds used by the exact
 * consistency verification pipeline:
 * <ul>
 *   <li>{@link #CONSISTENCY_REPORT} — stage-4 consistency outcome summary</li>
 *   <li>{@link #INCONSISTENCY_JUSTIFICATION} — Openllet conflict-axiom set</li>
 *   <li>{@link #STRUCTURAL_CONFLICT_HINT} — stage-1 proxy hint (NOT a formal
 *       contradiction verdict)</li>
 * </ul>
 */
public enum EvidenceKind {
    EXPLICIT_AXIOM("explicit_axiom"),
    INFERRED_AXIOM("inferred_axiom"),
    EXPLICIT_TRIPLE("explicit_triple"),
    INFERRED_TRIPLE("inferred_triple"),
    REASONING_REPORT("reasoning_report"),
    SCOPE_STATEMENT("scope_statement"),
    LITERAL_VALIDATION("literal_validation"),
    COUNTEREXAMPLE("counterexample"),
    // v0.8.5 exact consistency verification evidence kinds
    CONSISTENCY_REPORT("consistency_report"),
    INCONSISTENCY_JUSTIFICATION("inconsistency_justification"),
    STRUCTURAL_CONFLICT_HINT("structural_conflict_hint");

    private final String jsonName;

    EvidenceKind(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}