package org.owl4agents.reasoner;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.*;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;

import java.time.Duration;
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

    /**
     * v0.8.5: Perform an exact consistency check on an isolated temporary
     * ontology consisting of the source ontology's imports closure plus the
     * given claim axiom. The source ontology SHALL NOT be modified.
     *
     * <p>The method SHALL: (1) create a temporary ontology via
     * {@code TemporaryOntologyFactory}; (2) initialize a
     * {@code TransientReasonerSession} on the temporary ontology;
     * (3) call {@code checkConsistency()} with the configured timeout,
     * wrapped in {@code Future.get(timeout)}; (4) dispose the session in a
     * {@code finally} block; (5) return {@link ConsistencyAfterAdditionResult}
     * with status, timing, and optional explanation.
     *
     * @param sourceOntology the source ontology (not modified)
     * @param ontologyId     the source ontology ID (for caching/diagnostics)
     * @param claimId        the claim ID (for diagnostics)
     * @param claimAxiom     the axiom to add to the temporary ontology
     * @param reasonerName   the reasoner to use (HermiT/ELK/Openllet); if empty,
     *                       auto-select based on the ontology's OWL profile
     * @param timeout        the timeout for the consistency check; if null,
     *                       a default of 60 seconds is used
     * @return a {@link ServiceResult} with the
     *         {@link ConsistencyAfterAdditionResult} on success, or an error
     *         with code {@link org.owl4agents.core.ErrorCode#CLAIM_CONSISTENCY_CHECK_FAILED}
     *         on failure
     */
    ServiceResult<ConsistencyAfterAdditionResult> checkConsistencyAfterAdding(
            OWLOntology sourceOntology,
            OntologyId ontologyId,
            String claimId,
            OWLAxiom claimAxiom,
            Optional<String> reasonerName,
            Duration timeout);

    /**
     * v0.8.5: Check whether the source ontology (without any added axiom)
     * is consistent. The result SHALL be cached keyed by
     * {@code (ontologyId, fingerprint, reasonerName, importsState)} to avoid
     * redundant checks during batch processing. The cache SHALL be
     * invalidated on ontology reload, checksum change, import change,
     * reasoner change, or workspace change.
     *
     * @param ontologyId   the source ontology ID
     * @param reasonerName the reasoner to use; if empty, auto-select
     * @return a {@link ServiceResult} with {@link Boolean#TRUE} when the
     *         source is consistent, {@link Boolean#FALSE} when inconsistent,
     *         or an error with code
     *         {@link org.owl4agents.core.ErrorCode#SOURCE_ONTOLOGY_INCONSISTENT}
     *         is NOT returned here (that code is for claim-verification stage 2
     *         precondition failure; this method returns a plain
     *         {@code false} boolean result when the source is inconsistent)
     */
    ServiceResult<Boolean> checkSourceOntologyConsistency(
            OntologyId ontologyId,
            Optional<String> reasonerName);

    /**
     * v0.8.5 (task 6.8): Check whether the given pre-built axiom is entailed
     * by the ontology. This method accepts a pre-built {@link OWLAxiom}
     * (unlike the existing {@link #checkEntailment} which takes
     * {@code String axiomType + Map params}) to guarantee reference equality
     * between the entailment axiom and the exact-consistency axiom (D2).
     *
     * <p>The method SHALL: (1) check the asserted fast-path
     * ({@code ontology.containsAxiom(axiom, Imports.INCLUDED)}); if asserted,
     * return SUPPORTED immediately without invoking the reasoner; (2) otherwise,
     * call {@code reasoner.isEntailed(axiom)} with ELK exception isolation
     * (ELK throws on unsupported axiom types — return
     * {@link EntailmentResult#UNSUPPORTED_AXIOM_TYPE}).
     *
     * @param ontology     the source ontology
     * @param ontologyId   the source ontology ID
     * @param axiom        the pre-built axiom to check
     * @param reasonerName the reasoner to use; if empty, auto-select
     * @return a {@link ServiceResult} with {@link EntailmentResult}
     *         ({@code ENTAILED}, {@code NOT_ENTAILED}, or
     *         {@code UNSUPPORTED_AXIOM_TYPE})
     */
    ServiceResult<EntailmentResult> checkAxiomEntailment(
            OWLOntology ontology,
            OntologyId ontologyId,
            OWLAxiom axiom,
            Optional<String> reasonerName);
}