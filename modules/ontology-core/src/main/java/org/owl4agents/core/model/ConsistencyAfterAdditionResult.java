package org.owl4agents.core.model;

import java.util.List;
import java.util.Optional;

import org.owl4agents.core.OntologyId;

/**
 * Result of checking consistency after adding a claim axiom to a temporary ontology.
 *
 * <p>v0.8.6: New nullable {@code metadata} field of type
 * {@link ReasonerCallMetadata}, populated from the
 * {@code ServiceResult.reasonerMetadata} returned by
 * {@code ReasonerCallWrapper.callWithElkFallback} in
 * {@code checkConsistencyAfterAdding}.</p>
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
    PerStageTiming perStageTiming,
    ReasonerCallMetadata metadata
) {
    /**
     * Backward-compatible factory matching the v0.8.5 signature (metadata = null).
     */
    public static ConsistencyAfterAdditionResult create(
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
    ) {
        return new ConsistencyAfterAdditionResult(
            ontologyId, claimId, reasonerName, status, addedAxiom, elapsedMillis,
            sourceOntologyConsistent, temporaryOntologyIsolated, diagnosticMessage,
            explanationAxioms, perStageTiming, null);
    }

    /**
     * v0.8.6: Factory with reasoner call metadata.
     */
    public static ConsistencyAfterAdditionResult create(
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
        PerStageTiming perStageTiming,
        ReasonerCallMetadata metadata
    ) {
        return new ConsistencyAfterAdditionResult(
            ontologyId, claimId, reasonerName, status, addedAxiom, elapsedMillis,
            sourceOntologyConsistent, temporaryOntologyIsolated, diagnosticMessage,
            explanationAxioms, perStageTiming, metadata);
    }
}
