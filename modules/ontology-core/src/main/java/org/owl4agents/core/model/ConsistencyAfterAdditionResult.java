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
 *
 * <p>v0.8.8: New {@code unsatisfiableClasses} field containing IRIs of named
 * classes that BECAME unsatisfiable after adding the claim axiom (were
 * satisfiable in O, unsatisfiable in O∪{α}). Non-empty only when
 * {@code status == CONSISTENT} and the satisfiability check detected a
 * contradiction. Used by {@code ClaimVerificationService} to upgrade the
 * verdict from UNKNOWN to CONTRADICTED (D2/D3).</p>
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
    ReasonerCallMetadata metadata,
    List<String> unsatisfiableClasses
) {
    /**
     * Backward-compatible factory matching the v0.8.5 signature (metadata = null, unsatisfiableClasses = empty).
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
            explanationAxioms, perStageTiming, null, List.of());
    }

    /**
     * v0.8.6: Factory with reasoner call metadata (unsatisfiableClasses = empty).
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
            explanationAxioms, perStageTiming, metadata, List.of());
    }

    /**
     * v0.8.8: Factory with reasoner call metadata and unsatisfiable classes.
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
        ReasonerCallMetadata metadata,
        List<String> unsatisfiableClasses
    ) {
        return new ConsistencyAfterAdditionResult(
            ontologyId, claimId, reasonerName, status, addedAxiom, elapsedMillis,
            sourceOntologyConsistent, temporaryOntologyIsolated, diagnosticMessage,
            explanationAxioms, perStageTiming, metadata,
            unsatisfiableClasses != null ? unsatisfiableClasses : List.of());
    }
}
