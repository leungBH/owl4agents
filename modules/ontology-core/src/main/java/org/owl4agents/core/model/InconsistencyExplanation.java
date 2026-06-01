package org.owl4agents.core.model;

import java.util.List;

/**
 * Result of inconsistency explanation.
 * Contains conflicting axiom sets that explain why the ontology is inconsistent.
 */
public record InconsistencyExplanation(
    String ontologyId,
    List<ConflictingAxiomSet> conflictingAxiomSets,
    int explanationCount,
    ReasonerMetadata reasonerMetadata
) {}