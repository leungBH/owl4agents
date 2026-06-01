package org.owl4agents.core.model;

/**
 * A single entry in the inferred individual types.
 * Represents a Type relationship (individual is of type class).
 */
public record InferredTypeEntry(
    String ontologyId,
    String subjectIRI,
    String predicateIRI,
    String objectIRI,
    String source,
    String reasoner
) {}