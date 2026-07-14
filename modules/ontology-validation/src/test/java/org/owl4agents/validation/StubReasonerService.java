package org.owl4agents.validation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ResultMetadata;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.ClassificationResult;
import org.owl4agents.core.model.ConsistencyResult;
import org.owl4agents.core.model.EntailmentResult;
import org.owl4agents.core.model.InferredFact;
import org.owl4agents.core.model.InferredFactsResult;
import org.owl4agents.core.model.InconsistencyExplanation;
import org.owl4agents.core.model.ReasonerListResult;
import org.owl4agents.core.model.ReasonerSelectionResult;
import org.owl4agents.core.model.ReasoningReport;
import org.owl4agents.core.model.RealizationResult;
import org.owl4agents.core.model.UnsatClassExplanation;
import org.owl4agents.reasoner.ReasonerService;

/**
 * Hand-written stub for ReasonerService interface.
 * Returns configurable entailment results for testing claim verification.
 */
class StubReasonerService implements ReasonerService {

    private String entailmentResult = EntailmentResult.NOT_ENTAILED;
    private java.util.Map<String, String> entailmentResultsByType = new java.util.HashMap<>();
    private boolean consistent = true;
    private List<InferredFact> inferredFacts = List.of();
    private boolean hasReasoningReport = false;
    private List<String> unsatClasses = List.of();
    private java.util.List<String> callLog = new java.util.ArrayList<>();
    private String workspaceBasePath = null;
    // v0.8.5 exact consistency stub configuration
    private boolean sourceConsistent = true;
    private org.owl4agents.core.model.ConsistencyAfterAdditionStatus consistencyAfterAdditionStatus =
        org.owl4agents.core.model.ConsistencyAfterAdditionStatus.CONSISTENT;
    private java.util.List<String> consistencyExplanationAxioms = java.util.List.of();

    /** Set the entailment result to return from checkEntailment(). */
    StubReasonerService withEntailmentResult(String result) {
        this.entailmentResult = result;
        return this;
    }

    /**
     * Configure a per-axiom-type entailment result. Calls to {@code checkEntailment}
     * with the given {@code axiomType} will return the configured result, regardless
     * of the global {@link #withEntailmentResult default}.
     */
    StubReasonerService withEntailmentResult(String axiomType, String result) {
        this.entailmentResultsByType.put(axiomType, result);
        return this;
    }

    /** Returns the recorded call log (for spy tests). */
    java.util.List<String> getCallLog() {
        return java.util.Collections.unmodifiableList(callLog);
    }

    /** Set whether the ontology is consistent for checkConsistency(). */
    StubReasonerService withConsistent(boolean consistent) {
        this.consistent = consistent;
        return this;
    }

    /** Set inferred facts to return from getInferredFacts(). */
    StubReasonerService withInferredFacts(List<InferredFact> facts) {
        this.inferredFacts = facts;
        return this;
    }

    /** Enable reasoning report to return from getReasoningReport(). */
    StubReasonerService withReasoningReport(boolean available) {
        this.hasReasoningReport = available;
        return this;
    }

    /** Set unsatisfiable classes to return from getUnsatClasses(). */
    StubReasonerService withUnsatClasses(List<String> classes) {
        this.unsatClasses = classes;
        return this;
    }

    /**
     * Configure the stub to load the real ontology from the given workspace
     * base path. Used by tests that exercise the complex-class-expression
     * path (which calls {@code loadOntologyForClaim} → {@code ClassExpressionBuilder.build}).
     */
    StubReasonerService withRealOntology(String workspaceBasePath) {
        this.workspaceBasePath = workspaceBasePath;
        return this;
    }

    /** Set whether the source ontology is consistent for checkSourceOntologyConsistency(). */
    StubReasonerService withSourceConsistent(boolean sourceConsistent) {
        this.sourceConsistent = sourceConsistent;
        return this;
    }

    /** Set the ConsistencyAfterAdditionStatus to return from checkConsistencyAfterAdding(). */
    StubReasonerService withConsistencyAfterAdditionStatus(
            org.owl4agents.core.model.ConsistencyAfterAdditionStatus status) {
        this.consistencyAfterAdditionStatus = status;
        return this;
    }

    /** Set explanation axioms to return from checkConsistencyAfterAdding() when INCONSISTENT. */
    StubReasonerService withConsistencyExplanationAxioms(java.util.List<String> axioms) {
        this.consistencyExplanationAxioms = axioms;
        return this;
    }

    @Override
    public ServiceResult<EntailmentResult> checkEntailment(OntologyId ontologyId, String axiomType,
                                                            Map<String, String> parameters,
                                                            Optional<String> reasonerName) {
        callLog.add("checkEntailment:" + axiomType);
        String result = entailmentResultsByType.getOrDefault(axiomType, entailmentResult);
        return ServiceResult.success(
            new EntailmentResult(ontologyId.id(), axiomType, result,
                "reasoner", "HermiT", null),
            ResultMetadata.empty()
        );
    }

    @Override
    public ServiceResult<EntailmentResult> checkEquivalentClassesEntailment(
            OntologyId ontologyId,
            org.semanticweb.owlapi.model.OWLClassExpression subject,
            org.semanticweb.owlapi.model.OWLClassExpression object,
            Optional<String> reasonerName) {
        callLog.add("checkEquivalentClassesEntailment");
        // Per-axiom-type for "EquivalentClasses" overrides the global default.
        String result = entailmentResultsByType.getOrDefault("EquivalentClasses", entailmentResult);
        return ServiceResult.success(
            new EntailmentResult(ontologyId.id(), "EquivalentClasses", result,
                "reasoner", "HermiT", null),
            ResultMetadata.empty()
        );
    }

    @Override
    public org.semanticweb.owlapi.model.OWLOntology loadOntologyForClaim(OntologyId ontologyId)
            throws org.semanticweb.owlapi.model.OWLOntologyCreationException {
        if (workspaceBasePath != null) {
            // Load the real ontology from the configured workspace path.
            java.nio.file.Path path = java.nio.file.Path.of(
                workspaceBasePath, "default", "ontologies",
                ontologyId.id(), "canonical", "ontology.owl");
            if (java.nio.file.Files.exists(path)) {
                return org.semanticweb.owlapi.apibinding.OWLManager
                    .createOWLOntologyManager()
                    .loadOntologyFromOntologyDocument(path.toFile());
            }
            // Fall through to empty ontology for test-only ontology IDs
            // that don't have real files on disk.
        }
        // Default: return a minimal empty ontology to keep the stub contract
        // valid for paths that don't need real IRIs.
        return org.semanticweb.owlapi.apibinding.OWLManager
            .createOWLOntologyManager()
            .createOntology(org.semanticweb.owlapi.model.IRI.create("urn:test-" + ontologyId.id()));
    }

    @Override
    public ServiceResult<ConsistencyResult> checkConsistency(OntologyId ontologyId,
                                                              Optional<String> reasonerName) {
        return ServiceResult.success(
            new ConsistencyResult(ontologyId.id(), "HermiT", consistent, List.of()),
            ResultMetadata.empty()
        );
    }

    @Override
    public ServiceResult<ReasonerListResult> listReasoners() {
        return ServiceResult.success(
            new ReasonerListResult(List.of()),
            ResultMetadata.empty()
        );
    }

    @Override
    public ServiceResult<ReasoningReport> runReasoner(OntologyId ontologyId, Optional<String> reasonerName) {
        return ServiceResult.success(
            new ReasoningReport(ontologyId.id(), "HermiT", "OWL2-DL",
                true, true, true,
                new ReasoningReport.TimingBreakdown(0, 0, 0, 0),
                0, Map.of(), null),
            ResultMetadata.empty()
        );
    }

    @Override
    public ServiceResult<ClassificationResult> classify(OntologyId ontologyId,
                                                         Optional<String> reasonerName) {
        return ServiceResult.success(
            new ClassificationResult(ontologyId.id(), "HermiT", List.of(), List.of()),
            ResultMetadata.empty()
        );
    }

    @Override
    public ServiceResult<RealizationResult> realize(OntologyId ontologyId,
                                                     Optional<String> reasonerName) {
        return ServiceResult.success(
            new RealizationResult(ontologyId.id(), "HermiT", List.of(), List.of()),
            ResultMetadata.empty()
        );
    }

    @Override
    public ServiceResult<List<String>> getUnsatClasses(OntologyId ontologyId) {
        return ServiceResult.success(unsatClasses, ResultMetadata.empty());
    }

    @Override
    public ServiceResult<InconsistencyExplanation> explainInconsistency(OntologyId ontologyId,
                                                                        Optional<String> reasonerName) {
        return ServiceResult.error(ServiceError.of(
            org.owl4agents.core.ErrorCode.REASONING_NOT_RUN, "No inconsistency to explain"));
    }

    @Override
    public ServiceResult<UnsatClassExplanation> explainUnsatClass(OntologyId ontologyId, String classIRI,
                                                                   Optional<String> reasonerName) {
        return ServiceResult.error(ServiceError.of(
            org.owl4agents.core.ErrorCode.REASONING_NOT_RUN, "No unsat class to explain"));
    }

    @Override
    public ServiceResult<ReasoningReport> getReasoningReport(OntologyId ontologyId) {
        if (hasReasoningReport) {
            return ServiceResult.success(
                new ReasoningReport(ontologyId.id(), "HermiT", "OWL2-DL",
                    true, true, true,
                    new ReasoningReport.TimingBreakdown(100, 200, 50, 350),
                    0, Map.of("SubClassOf", 5, "EquivalentClasses", 2), null),
                ResultMetadata.empty()
            );
        }
        return ServiceResult.error(ServiceError.of(
            org.owl4agents.core.ErrorCode.REASONING_NOT_RUN, "No reasoning report available"));
    }

    @Override
    public ServiceResult<InferredFactsResult> getInferredFacts(OntologyId ontologyId,
                                                                Optional<String> entityIRI) {
        return ServiceResult.success(
            new InferredFactsResult(ontologyId.id(),
                entityIRI.orElse(""),
                inferredFacts),
            ResultMetadata.empty()
        );
    }

    @Override
    public ServiceResult<Void> shutdown(OntologyId ontologyId) {
        return ServiceResult.success(null, ResultMetadata.empty());
    }

    @Override
    public ServiceResult<ReasonerSelectionResult> selectReasoner(OntologyId ontologyId,
                                                                   boolean explanationRequested) {
        return ServiceResult.success(
            new ReasonerSelectionResult("HermiT", "OWL2-DL", "Default selection"),
            ResultMetadata.empty()
        );
    }

    // ── v0.8.5 exact consistency verification stubs (task 6.9) ──

    @Override
    public ServiceResult<org.owl4agents.core.model.ConsistencyAfterAdditionResult> checkConsistencyAfterAdding(
            org.semanticweb.owlapi.model.OWLOntology sourceOntology,
            OntologyId ontologyId,
            String claimId,
            org.semanticweb.owlapi.model.OWLAxiom claimAxiom,
            java.util.Optional<String> reasonerName,
            java.time.Duration timeout) {
        callLog.add("checkConsistencyAfterAdding:" + claimId);
        org.owl4agents.core.model.ConsistencyAfterAdditionResult result =
            new org.owl4agents.core.model.ConsistencyAfterAdditionResult(
                ontologyId, claimId,
                reasonerName.orElse("HermiT"),
                consistencyAfterAdditionStatus,
                claimAxiom != null ? claimAxiom.toString() : "null",
                0L, sourceConsistent, true,
                java.util.Optional.empty(),
                consistencyExplanationAxioms,
                org.owl4agents.core.model.PerStageTiming.empty());
        return ServiceResult.success(result, ResultMetadata.empty());
    }

    @Override
    public ServiceResult<Boolean> checkSourceOntologyConsistency(
            OntologyId ontologyId, java.util.Optional<String> reasonerName) {
        callLog.add("checkSourceOntologyConsistency:" + ontologyId.id());
        return ServiceResult.success(sourceConsistent, ResultMetadata.empty());
    }

    @Override
    public ServiceResult<EntailmentResult> checkAxiomEntailment(
            org.semanticweb.owlapi.model.OWLOntology ontology,
            OntologyId ontologyId,
            org.semanticweb.owlapi.model.OWLAxiom axiom,
            java.util.Optional<String> reasonerName) {
        callLog.add("checkAxiomEntailment:" + (axiom != null ? axiom.getAxiomType().getName() : "null"));
        // Asserted fast-path: if the axiom is already in the ontology, return ENTAILED
        if (ontology != null && axiom != null && ontology.containsAxiom(axiom, true)) {
            return ServiceResult.success(
                new EntailmentResult(ontologyId.id(), axiom.getAxiomType().getName(),
                    EntailmentResult.ENTAILED, "asserted", "", ""),
                ResultMetadata.empty());
        }
        // Otherwise, respect the configured entailment result (for backward
        // compatibility with tests that use withEntailmentResult to simulate
        // reasoner entailment decisions)
        String axiomType = axiom != null ? axiom.getAxiomType().getName() : "unknown";
        String result = entailmentResultsByType.getOrDefault(axiomType, entailmentResult);
        return ServiceResult.success(
            new EntailmentResult(ontologyId.id(), axiomType, result,
                "stub", "HermiT", ""),
            ResultMetadata.empty());
    }
}