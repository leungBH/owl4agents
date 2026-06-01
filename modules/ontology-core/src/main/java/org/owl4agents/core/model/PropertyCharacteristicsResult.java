package org.owl4agents.core.model;

/**
 * Result of property characteristics retrieval.
 * Returns the characteristic flags for a given object or data property.
 */
public record PropertyCharacteristicsResult(
    String ontologyId,
    String propertyIRI,
    String propertyType,
    boolean functional,
    boolean inverseFunctional,
    boolean transitive,
    boolean symmetric,
    boolean asymmetric,
    boolean reflexive,
    boolean irreflexive,
    String source
) {}