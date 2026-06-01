package org.owl4agents.core.model;

import java.util.List;

/**
 * Result of unsatisfiable class explanation.
 * Contains conflicting axiom sets that explain why a specific class is unsatisfiable.
 */
public record UnsatClassExplanation(
    String ontologyId,
    String classIRI,
    List<ConflictingAxiomSet> conflictingAxiomSets,
    int explanationCount,
    ReasonerMetadata reasonerMetadata
) {}