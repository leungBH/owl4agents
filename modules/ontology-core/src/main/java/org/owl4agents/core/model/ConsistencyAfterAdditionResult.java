package org.owl4agents.core.model;

import java.util.List;
import java.util.Optional;

import org.owl4agents.core.OntologyId;

/**
 * Result of checking consistency after adding a claim axiom to a temporary ontology.
 */
public record ConsistencyAfterAdditionResult(
    OntologyId ontologyId,
    String claimId,
    String reasonerName,
    ConsistencyAfterAdditionStatus status,
    String addedAxiom,
    long elapsedMillis,
    boolean sourceOntologyConsistent,
    boolean temporaryOntologyIsolated,
    Optional<String> diagnosticMessage,
    List<String> explanationAxioms,
    PerStageTiming perStageTiming
) {}
