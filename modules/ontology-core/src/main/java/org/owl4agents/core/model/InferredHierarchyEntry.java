package org.owl4agents.core.model;

/**
 * A single entry in the inferred class hierarchy.
 * Represents a SubClassOf relationship (subject is subclass of object).
 */
public record InferredHierarchyEntry(
    String ontologyId,
    String subjectIRI,
    String predicateIRI,
    String objectIRI,
    String source,
    String reasoner
) {}