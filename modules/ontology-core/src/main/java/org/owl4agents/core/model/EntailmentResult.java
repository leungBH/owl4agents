package org.owl4agents.core.model;

/**
 * Result of entailment checking.
 * Indicates whether a structured axiom is entailed by the ontology.
 */
public record EntailmentResult(
    String ontologyId,
    String axiomType,
    String result,
    String source,
    String reasonerName,
    String evidence
) {
    public static final String ENTAILED = "entailed";
    public static final String NOT_ENTAILED = "not_entailed";
    public static final String UNSUPPORTED_AXIOM_TYPE = "unsupported_axiom_type";
}