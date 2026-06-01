package org.owl4agents.core.model;

/**
 * A single datatype facet constraint.
 */
public record DatatypeFacet(
    String facetType,
    String facetValue
) {}