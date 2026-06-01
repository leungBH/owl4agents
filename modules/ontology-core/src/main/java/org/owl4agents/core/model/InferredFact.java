package org.owl4agents.core.model;

/**
 * A single inferred fact (axiom or triple).
 */
public record InferredFact(
    String ontologyId,
    String subjectIRI,
    String predicateIRI,
    String objectIRI,
    String literalValue,
    String axiomType,
    String source,
    String reasoner
) {}