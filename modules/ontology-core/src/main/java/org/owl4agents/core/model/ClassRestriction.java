package org.owl4agents.core.model;

/**
 * A single class restriction (someValuesFrom, allValuesFrom, cardinality, hasValue, datatype).
 */
public record ClassRestriction(
    String restrictionType,
    String onProperty,
    String filler,
    Integer cardinality,
    String source
) {}