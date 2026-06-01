package org.owl4agents.core.model;

import java.util.List;

/**
 * Result of inferred facts retrieval: all inferred axioms/triples
 * for a given entity or the entire ontology scope.
 */
public record InferredFactsResult(
    String ontologyId,
    String entityIRI,
    List<InferredFact> facts
) {}