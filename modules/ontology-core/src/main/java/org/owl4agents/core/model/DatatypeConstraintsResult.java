package org.owl4agents.core.model;

import java.util.List;

/**
 * Result of datatype constraints retrieval.
 * Returns all facet constraints defined for a given datatype URI.
 */
public record DatatypeConstraintsResult(
    String ontologyId,
    String datatypeIRI,
    String baseDatatypeIRI,
    List<DatatypeFacet> facets
) {}