package org.owl4agents.core.model;

import java.util.List;

/**
 * Result of equivalent/disjoint property retrieval.
 * Returns all equivalent or disjoint property axioms for a given property.
 */
public record PropertyAxiomsResult(
    String ontologyId,
    String propertyIRI,
    String propertyType,
    List<String> relatedPropertyIRIs,
    String relationType,
    String source
) {}