package org.owl4agents.core.model;

/**
 * Result of class compatibility checking.
 * Indicates whether two classes can overlap, are disjoint, or are unsatisfiable together.
 */
public record ClassCompatibilityResult(
    String ontologyId,
    String class1IRI,
    String class2IRI,
    String compatibility,
    String reasonerName
) {
    public static final String COMPATIBLE = "compatible";
    public static final String DISJOINT = "disjoint";
    public static final String UNSATISFIABLE_TOGETHER = "unsatisfiable_together";
    public static final String UNKNOWN = "unknown";
}