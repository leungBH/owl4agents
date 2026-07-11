package org.owl4agents.reasoner;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.*;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;

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
     *
     * @deprecated Use {@link #checkEntailment(OWLOntology, OntologyId, String, Map, Optional)}
     *             to avoid redundant ontology loading.
     */
    @Deprecated
    ServiceResult<EntailmentResult> checkEntailment(OntologyId ontologyId, String axiomType,
                                                     java.util.Map<String, String> parameters,
                                                     Optional<String> reasonerName);

    /**
     * v0.8.4: Overload that accepts a pre-loaded {@link OWLOntology}, avoiding
     * redundant ontology loading in the claim verification hot path.
     * Default implementation delegates to the 4-arg overload (loads internally).
     */
    @SuppressWarnings("deprecation")
    default ServiceResult<EntailmentResult> checkEntailment(OWLOntology ontology, OntologyId ontologyId,
                                                             String axiomType,
                                                             java.util.Map<String, String> parameters,
                                                             Optional<String> reasonerName) {
        return checkEntailment(ontologyId, axiomType, parameters, reasonerName);
    }

    /**
     * v0.8.1 ISSUE-03: Check equivalent-classes entailment using OWL API
     * {@link OWLClassExpression} operands (subject + object). The {@code Map<String, String>}
     * overload above can only handle named classes; this overload supports complex
     * expressions (intersection, union, complement, etc.) by delegating to
     * {@code reasoner.isEntailed(OWLEquivalentClassesAxiom)} on the constructed axiom.
     *
     * @deprecated Use {@link #checkEquivalentClassesEntailment(OWLOntology, OntologyId, OWLClassExpression, OWLClassExpression, Optional)}
     *             to avoid redundant ontology loading.
     */
    @Deprecated
    ServiceResult<EntailmentResult> checkEquivalentClassesEntailment(
            OntologyId ontologyId,
            OWLClassExpression subject,
            OWLClassExpression object,
            Optional<String> reasonerName);

    /**
     * v0.8.4: Overload that accepts a pre-loaded {@link OWLOntology}.
     * Default implementation delegates to the 4-arg overload (loads internally).
     */
    @SuppressWarnings("deprecation")
    default ServiceResult<EntailmentResult> checkEquivalentClassesEntailment(
            OWLOntology ontology,
            OntologyId ontologyId,
            OWLClassExpression subject,
            OWLClassExpression object,
            Optional<String> reasonerName) {
        return checkEquivalentClassesEntailment(ontologyId, subject, object, reasonerName);
    }

    /**
     * v0.8.1 ISSUE-03: Load the canonical OWL ontology for the given ID.
     * Used by {@code ClaimVerificationService} to resolve IRIs inside complex
     * class expressions. Throws {@link OWLOntologyCreationException} on failure.
     */
    OWLOntology loadOntologyForClaim(OntologyId ontologyId) throws OWLOntologyCreationException;

    /**
     * Shut down the reasoner for the specified ontology session.
     */
    ServiceResult<Void> shutdown(OntologyId ontologyId);

    /**
     * Select the appropriate reasoner based on the ontology's OWL profile.
     */
    ServiceResult<ReasonerSelectionResult> selectReasoner(OntologyId ontologyId, boolean explanationRequested);
}