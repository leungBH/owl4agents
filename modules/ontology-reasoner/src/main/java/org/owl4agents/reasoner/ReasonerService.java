package org.owl4agents.reasoner;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.*;

import java.util.Optional;

/**
 * Service interface for reasoner operations.
 * Manages reasoner lifecycle, selection, and all reasoning operations.
 * The OntologyService delegates reasoner calls to this interface.
 */
public interface ReasonerService {

    /**
     * List all available reasoner adapters with their capabilities.
     */
    ServiceResult<ReasonerListResult> listReasoners();

    /**
     * Run the reasoner on the specified ontology with the given reasoner name or auto selection.
     * Performs initialization, classification, realization, and consistency check.
     * Stores inferred results and generates a reasoning report.
     */
    ServiceResult<ReasoningReport> runReasoner(OntologyId ontologyId, Optional<String> reasonerName);

    /**
     * Classify the ontology: compute the inferred class hierarchy.
     * Initializes the reasoner if not already active.
     */
    ServiceResult<ClassificationResult> classify(OntologyId ontologyId, Optional<String> reasonerName);

    /**
     * Realize the ontology: compute inferred individual types.
     * Initializes the reasoner if not already active.
     */
    ServiceResult<RealizationResult> realize(OntologyId ontologyId, Optional<String> reasonerName);

    /**
     * Check ontology consistency.
     * Initializes the reasoner if not already active.
     */
    ServiceResult<ConsistencyResult> checkConsistency(OntologyId ontologyId, Optional<String> reasonerName);

    /**
     * Get all unsatisfiable class IRIs for the ontology.
     * Requires classification to have been performed.
     */
    ServiceResult<java.util.List<String>> getUnsatClasses(OntologyId ontologyId);

    /**
     * Explain inconsistency in the ontology.
     * Uses Openllet adapter for explanation when available.
     */
    ServiceResult<InconsistencyExplanation> explainInconsistency(OntologyId ontologyId, Optional<String> reasonerName);

    /**
     * Explain why a specific class is unsatisfiable.
     */
    ServiceResult<UnsatClassExplanation> explainUnsatClass(OntologyId ontologyId, String classIRI, Optional<String> reasonerName);

    /**
     * Get the reasoning report for the specified ontology.
     * Returns the stored reasoning-report.json content.
     */
    ServiceResult<ReasoningReport> getReasoningReport(OntologyId ontologyId);

    /**
     * Get inferred facts for a specific entity or the entire ontology.
     */
    ServiceResult<InferredFactsResult> getInferredFacts(OntologyId ontologyId, Optional<String> entityIRI);

    /**
     * Check whether a structured axiom is entailed by the ontology.
     */
    ServiceResult<EntailmentResult> checkEntailment(OntologyId ontologyId, String axiomType,
                                                     java.util.Map<String, String> parameters,
                                                     Optional<String> reasonerName);

    /**
     * Shut down the reasoner for the specified ontology session.
     */
    ServiceResult<Void> shutdown(OntologyId ontologyId);

    /**
     * Select the appropriate reasoner based on the ontology's OWL profile.
     */
    ServiceResult<ReasonerSelectionResult> selectReasoner(OntologyId ontologyId, boolean explanationRequested);
}