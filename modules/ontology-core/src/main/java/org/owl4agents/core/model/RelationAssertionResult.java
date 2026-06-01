package org.owl4agents.core.model;

/**
 * Result of relation assertion check.
 * Indicates whether an object property relation is asserted or entailed between two individuals.
 */
public record RelationAssertionResult(
    String ontologyId,
    String sourceIndividualIRI,
    String propertyIRI,
    String targetIndividualIRI,
    boolean isAsserted,
    String assertionType,
    String reasonerName
) {
    public static final String EXPLICIT = "explicit";
    public static final String INFERRED = "inferred";
    public static final String BOTH = "both";
}