package org.owl4agents.reasoner;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;
import org.owl4agents.owlapi.OntologyImporter;
import org.owl4agents.storage.CatalogStore;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.Node;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Default implementation of ReasonerService.
 * Manages reasoner lifecycle, executes reasoning operations, and stores inferred results.
 */
public class ReasonerServiceImpl implements ReasonerService {

    private final ReasonerLifecycleManager lifecycleManager;
    private final CatalogStore catalogStore;
    private final String workspaceBasePath;

    /**
     * Get the lifecycle manager for sharing with other services (e.g. consistency analysis).
     */
    public ReasonerLifecycleManager getLifecycleManager() {
        return lifecycleManager;
    }

    public ReasonerServiceImpl(CatalogStore catalogStore, String workspaceBasePath) {
        this.lifecycleManager = new ReasonerLifecycleManager();
        this.catalogStore = catalogStore;
        this.workspaceBasePath = workspaceBasePath;
    }

    @Override
    public ServiceResult<ReasonerListResult> listReasoners() {
        return ServiceResult.success(lifecycleManager.listReasoners(), ResultMetadata.empty());
    }

    @Override
    public ServiceResult<ReasoningReport> runReasoner(OntologyId ontologyId, Optional<String> reasonerName) {
        long totalStart = System.currentTimeMillis();
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            String detectedProfile = detectProfile(ontology);
            boolean explanationRequested = reasonerName.map("openllet"::equalsIgnoreCase).orElse(false);
            String effectiveReasonerName = resolveReasonerName(reasonerName, detectedProfile, explanationRequested);

            long initStart = System.currentTimeMillis();
            OWLReasonerAdapter adapter = lifecycleManager.getOrCreateReasoner(
                ontologyId, effectiveReasonerName, ontology, detectedProfile, explanationRequested);
            long initTime = System.currentTimeMillis() - initStart;

            long classStart = System.currentTimeMillis();
            ClassificationResult classificationResult = adapter.classify(ontologyId.id());
            long classTime = System.currentTimeMillis() - classStart;

            long realStart = System.currentTimeMillis();
            RealizationResult realizationResult = adapter.realize(ontologyId.id());
            long realTime = System.currentTimeMillis() - realStart;

            ConsistencyResult consistencyResult = adapter.checkConsistency(ontologyId.id());

            long totalTime = System.currentTimeMillis() - totalStart;

            // Build reasoning report
            Map<String, Integer> axiomCounts = new LinkedHashMap<>();
            axiomCounts.put("SubClassOf", classificationResult.delta().size());
            axiomCounts.put("InferredIndividualType", realizationResult.delta().size());

            ReasoningReport report = new ReasoningReport(
                ontologyId.id(),
                effectiveReasonerName,
                detectedProfile,
                true,
                true,
                consistencyResult.consistent(),
                new ReasoningReport.TimingBreakdown(initTime, classTime, realTime, totalTime),
                0,
                axiomCounts,
                null
            );

            // Store inferred data and report
            storeInferredData(ontologyId, classificationResult, realizationResult);
            lifecycleManager.storeReasoningReport(ontologyId, report);
            storeReasoningReportFile(ontologyId, report);

            return ServiceResult.success(report, ResultMetadata.empty());

        } catch (OWLOntologyCreationException e) {
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        } catch (IllegalArgumentException e) {
            if (e.getMessage().contains("Unknown reasoner") || e.getMessage().contains("PROFILE_NOT_SUPPORTED")) {
                return ServiceResult.error(ErrorCode.PROFILE_NOT_SUPPORTED, e.getMessage());
            }
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        } catch (Exception e) {
            ReasoningReport.TimingBreakdown timing = new ReasoningReport.TimingBreakdown(
                0, 0, 0, System.currentTimeMillis() - totalStart);
            ReasoningReport report = new ReasoningReport(
                ontologyId.id(), reasonerName.orElse("unknown"), "unknown",
                false, null, false, timing, 0, Map.of(),
                new ReasoningReport.ErrorDetails("reasoner-initialization-failed", e.getMessage(), null));
            lifecycleManager.storeReasoningReport(ontologyId, report);
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        }
    }

    @Override
    public ServiceResult<ClassificationResult> classify(OntologyId ontologyId, Optional<String> reasonerName) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            String detectedProfile = detectProfile(ontology);
            boolean explanationRequested = false;
            String effectiveReasonerName = resolveReasonerName(reasonerName, detectedProfile, explanationRequested);

            OWLReasonerAdapter adapter = lifecycleManager.getOrCreateReasoner(
                ontologyId, effectiveReasonerName, ontology, detectedProfile, explanationRequested);

            ClassificationResult result = adapter.classify(ontologyId.id());
            return ServiceResult.success(result, ResultMetadata.empty());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        }
    }

    @Override
    public ServiceResult<RealizationResult> realize(OntologyId ontologyId, Optional<String> reasonerName) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            String detectedProfile = detectProfile(ontology);
            String effectiveReasonerName = resolveReasonerName(reasonerName, detectedProfile, false);

            OWLReasonerAdapter adapter = lifecycleManager.getOrCreateReasoner(
                ontologyId, effectiveReasonerName, ontology, detectedProfile, false);

            RealizationResult result = adapter.realize(ontologyId.id());
            return ServiceResult.success(result, ResultMetadata.empty());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        }
    }

    @Override
    public ServiceResult<ConsistencyResult> checkConsistency(OntologyId ontologyId, Optional<String> reasonerName) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            String detectedProfile = detectProfile(ontology);
            String effectiveReasonerName = resolveReasonerName(reasonerName, detectedProfile, false);

            OWLReasonerAdapter adapter = lifecycleManager.getOrCreateReasoner(
                ontologyId, effectiveReasonerName, ontology, detectedProfile, false);

            ConsistencyResult result = adapter.checkConsistency(ontologyId.id());
            return ServiceResult.success(result, ResultMetadata.empty());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        }
    }

    @Override
    public ServiceResult<List<String>> getUnsatClasses(OntologyId ontologyId) {
        Optional<OWLReasonerAdapter> adapter = lifecycleManager.getActiveReasoner(ontologyId);
        if (adapter.isEmpty()) {
            return ServiceResult.error(ErrorCode.REASONING_NOT_RUN,
                "Reasoning has not been executed. Run reasoning first before accessing inferred results.");
        }
        try {
            Set<String> unsatClasses = adapter.get().getUnsatClasses();
            return ServiceResult.success(new ArrayList<>(unsatClasses), ResultMetadata.empty());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        }
    }

    @Override
    public ServiceResult<InconsistencyExplanation> explainInconsistency(OntologyId ontologyId, Optional<String> reasonerName) {
        try {
            // For explanation, prefer Openllet
            boolean explanationRequested = true;
            OWLOntology ontology = loadOntology(ontologyId);
            String detectedProfile = detectProfile(ontology);
            String effectiveReasonerName = reasonerName.orElse("openllet");

            OWLReasonerAdapter adapter = lifecycleManager.getOrCreateReasoner(
                ontologyId, effectiveReasonerName, ontology, detectedProfile, explanationRequested);

            InconsistencyExplanation explanation = adapter.explainInconsistency(ontologyId.id());

            if (explanation == null) {
                return ServiceResult.error(ErrorCode.ONTOLOGY_CONSISTENT,
                "The ontology is consistent; no inconsistency explanation is needed.");
            }

            return ServiceResult.success(explanation, ResultMetadata.empty());
        } catch (UnsupportedOperationException e) {
            return ServiceResult.error(ErrorCode.EXPLANATION_NOT_SUPPORTED, e.getMessage());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.EXPLANATION_FAILED, e.getMessage());
        }
    }

    @Override
    public ServiceResult<UnsatClassExplanation> explainUnsatClass(OntologyId ontologyId, String classIRI, Optional<String> reasonerName) {
        try {
            boolean explanationRequested = true;
            OWLOntology ontology = loadOntology(ontologyId);
            String detectedProfile = detectProfile(ontology);
            String effectiveReasonerName = reasonerName.orElse("openllet");

            OWLReasonerAdapter adapter = lifecycleManager.getOrCreateReasoner(
                ontologyId, effectiveReasonerName, ontology, detectedProfile, explanationRequested);

            UnsatClassExplanation explanation = adapter.explainUnsatClass(ontologyId.id(), classIRI);

            if (explanation == null) {
                return ServiceResult.error(ErrorCode.ONTOLOGY_CONSISTENT,
                "The class is satisfiable; no unsatisfiability explanation is needed.");
            }

            return ServiceResult.success(explanation, ResultMetadata.empty());
        } catch (UnsupportedOperationException e) {
            return ServiceResult.error(ErrorCode.EXPLANATION_NOT_SUPPORTED, e.getMessage());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.EXPLANATION_FAILED, e.getMessage());
        }
    }

    @Override
    public ServiceResult<ReasoningReport> getReasoningReport(OntologyId ontologyId) {
        Optional<ReasoningReport> report = lifecycleManager.getReasoningReport(ontologyId);
        if (report.isEmpty()) {
            return ServiceResult.error(ErrorCode.REASONING_NOT_RUN,
                "Reasoning has not been executed. No reasoning report available.");
        }
        return ServiceResult.success(report.get(), ResultMetadata.empty());
    }

    @Override
    public ServiceResult<InferredFactsResult> getInferredFacts(OntologyId ontologyId, Optional<String> entityIRI) {
        if (!lifecycleManager.hasReasoningRun(ontologyId)) {
            return ServiceResult.error(ErrorCode.REASONING_NOT_RUN,
                "Reasoning has not been executed. Run reasoning first before accessing inferred facts.");
        }
        try {
            Path inferredDir = getInferredDir(ontologyId);
            Path factsFile = inferredDir.resolve("inferred-facts.jsonl");
            if (!Files.exists(factsFile)) {
                return ServiceResult.success(
                    new InferredFactsResult(ontologyId.id(), entityIRI.orElse(null), List.of()),
                    ResultMetadata.empty());
            }

            List<InferredFact> facts = readInferredFactsFile(factsFile);

            if (entityIRI.isPresent()) {
                String entity = entityIRI.get();
                facts = facts.stream()
                    .filter(f -> f.subjectIRI().equals(entity) ||
                                 (f.objectIRI() != null && f.objectIRI().equals(entity)))
                    .collect(Collectors.toList());
            }

            return ServiceResult.success(
                new InferredFactsResult(ontologyId.id(), entityIRI.orElse(null), facts),
                ResultMetadata.empty());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.REASONING_NOT_RUN, e.getMessage());
        }
    }

    @Override
    public ServiceResult<EntailmentResult> checkEntailment(OntologyId ontologyId, String axiomType,
                                                             Map<String, String> parameters, Optional<String> reasonerName) {
        // Validate axiom type support
        Set<String> supportedTypes = Set.of(
            "SubClassOf", "EquivalentClasses", "DisjointClasses", "ClassAssertion",
            "ObjectPropertyAssertion", "DataPropertyAssertion", "ObjectPropertyDomain", "ObjectPropertyRange");

        if (!supportedTypes.contains(axiomType)) {
            return ServiceResult.success(
                new EntailmentResult(ontologyId.id(), axiomType, EntailmentResult.UNSUPPORTED_AXIOM_TYPE, null, null, null),
                ResultMetadata.empty());
        }

        // Validate required parameters
        if (parameters == null || parameters.isEmpty()) {
            return ServiceResult.error(ErrorCode.INVALID_AXIOM_PARAMETERS,
                "Required axiom fields are missing or malformed for axiom type: " + axiomType);
        }

        try {
            Optional<OWLReasonerAdapter> adapter = lifecycleManager.getActiveReasoner(ontologyId);
            if (adapter.isEmpty()) {
                // Initialize reasoner if needed
                OWLOntology ontology = loadOntology(ontologyId);
                String detectedProfile = detectProfile(ontology);
                String effectiveReasonerName = resolveReasonerName(reasonerName, detectedProfile, false);
                OWLReasonerAdapter newAdapter = lifecycleManager.getOrCreateReasoner(
                    ontologyId, effectiveReasonerName, ontology, detectedProfile, false);

                boolean entailed = checkAxiomEntailment(newAdapter, ontology, axiomType, parameters);
                String source = determineSource(ontology, axiomType, parameters);

                return ServiceResult.success(
                    new EntailmentResult(ontologyId.id(), axiomType,
                        entailed ? EntailmentResult.ENTAILED : EntailmentResult.NOT_ENTAILED,
                        source, effectiveReasonerName, null),
                    ResultMetadata.empty());
            }

            OWLOntology ontology = loadOntology(ontologyId);
            boolean entailed = checkAxiomEntailment(adapter.get(), ontology, axiomType, parameters);
            String source = determineSource(ontology, axiomType, parameters);

            return ServiceResult.success(
                new EntailmentResult(ontologyId.id(), axiomType,
                    entailed ? EntailmentResult.ENTAILED : EntailmentResult.NOT_ENTAILED,
                    source, adapter.get().getName(), null),
                ResultMetadata.empty());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        }
    }

    @Override
    public ServiceResult<Void> shutdown(OntologyId ontologyId) {
        lifecycleManager.shutdownReasoner(ontologyId);
        return ServiceResult.success(null, ResultMetadata.empty());
    }

    @Override
    public ServiceResult<ReasonerSelectionResult> selectReasoner(OntologyId ontologyId, boolean explanationRequested) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            String detectedProfile = detectProfile(ontology);
            AutoReasonerSelector selector = new AutoReasonerSelector();
            ReasonerSelectionResult result = selector.select(detectedProfile, explanationRequested);
            return ServiceResult.success(result, ResultMetadata.empty());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
        }
    }

    // ── Private helpers ──

    private OWLOntology loadOntology(OntologyId ontologyId) throws OWLOntologyCreationException {
        Path ontologyPath = resolveOntologyPath(ontologyId);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        return manager.loadOntologyFromOntologyDocument(ontologyPath.toFile());
    }

    private Path resolveOntologyPath(OntologyId ontologyId) {
        return Path.of(workspaceBasePath, "default", "ontologies", ontologyId.id(), "canonical", "ontology.owl");
    }

    private String detectProfile(OWLOntology ontology) {
        // Basic profile detection based on ontology expressivity
        // The OWL API provides profile violation checking
        try {
            org.semanticweb.owlapi.profiles.OWL2DLProfile dlProfile = new org.semanticweb.owlapi.profiles.OWL2DLProfile();
            org.semanticweb.owlapi.profiles.OWL2ELProfile elProfile = new org.semanticweb.owlapi.profiles.OWL2ELProfile();

            if (elProfile.checkOntology(ontology).getViolations().isEmpty()) {
                return "OWL 2 EL";
            } else if (dlProfile.checkOntology(ontology).getViolations().isEmpty()) {
                return "OWL 2 DL";
            } else {
                return "OWL 2 Full";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String resolveReasonerName(Optional<String> reasonerName, String detectedProfile, boolean explanationRequested) {
        if (reasonerName.isPresent() && !"auto".equalsIgnoreCase(reasonerName.get())) {
            return reasonerName.get();
        }
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult selection = selector.select(detectedProfile, explanationRequested);
        return selection.reasonerName();
    }

    private Path getInferredDir(OntologyId ontologyId) {
        return Path.of(workspaceBasePath, "default", "ontologies", ontologyId.id(), "inferred");
    }

    private void storeInferredData(OntologyId ontologyId,
                                    ClassificationResult classificationResult,
                                    RealizationResult realizationResult) throws IOException {
        Path inferredDir = getInferredDir(ontologyId);
        Files.createDirectories(inferredDir);

        // Write inferred-class-hierarchy.jsonl
        writeHierarchyJsonl(inferredDir.resolve("inferred-class-hierarchy.jsonl"), classificationResult.completeHierarchy());

        // Write inferred-types.jsonl
        writeTypesJsonl(inferredDir.resolve("inferred-types.jsonl"), realizationResult.completeTypes());

        // Write inferred-facts.jsonl (combining all inferred data)
        writeFactsJsonl(inferredDir.resolve("inferred-facts.jsonl"), classificationResult, realizationResult);
    }

    private void storeReasoningReportFile(OntologyId ontologyId, ReasoningReport report) throws IOException {
        Path inferredDir = getInferredDir(ontologyId);
        Files.createDirectories(inferredDir);

        // Write reasoning-report.json
        String json = serializeReportToJson(report);
        Files.writeString(inferredDir.resolve("reasoning-report.json"), json);
    }

    private void writeHierarchyJsonl(Path filePath, List<InferredHierarchyEntry> entries) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
            for (InferredHierarchyEntry entry : entries) {
                writer.write(String.format(
                    "{\"ontologyId\":\"%s\",\"subjectIRI\":\"%s\",\"predicateIRI\":\"%s\",\"objectIRI\":\"%s\",\"source\":\"%s\",\"reasoner\":\"%s\"}",
                    entry.ontologyId(), entry.subjectIRI(), entry.predicateIRI(), entry.objectIRI(), entry.source(), entry.reasoner()));
                writer.newLine();
            }
        }
    }

    private void writeTypesJsonl(Path filePath, List<InferredTypeEntry> entries) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
            for (InferredTypeEntry entry : entries) {
                writer.write(String.format(
                    "{\"ontologyId\":\"%s\",\"subjectIRI\":\"%s\",\"predicateIRI\":\"%s\",\"objectIRI\":\"%s\",\"source\":\"%s\",\"reasoner\":\"%s\"}",
                    entry.ontologyId(), entry.subjectIRI(), entry.predicateIRI(), entry.objectIRI(), entry.source(), entry.reasoner()));
                writer.newLine();
            }
        }
    }

    private void writeFactsJsonl(Path filePath, ClassificationResult classificationResult,
                                  RealizationResult realizationResult) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
            for (InferredHierarchyEntry entry : classificationResult.delta()) {
                writer.write(String.format(
                    "{\"ontologyId\":\"%s\",\"subjectIRI\":\"%s\",\"predicateIRI\":\"%s\",\"objectIRI\":\"%s\",\"literalValue\":null,\"axiomType\":\"SubClassOf\",\"source\":\"inferred\",\"reasoner\":\"%s\"}",
                    entry.ontologyId(), entry.subjectIRI(), entry.predicateIRI(), entry.objectIRI(), entry.reasoner()));
                writer.newLine();
            }
            for (InferredTypeEntry entry : realizationResult.delta()) {
                writer.write(String.format(
                    "{\"ontologyId\":\"%s\",\"subjectIRI\":\"%s\",\"predicateIRI\":\"%s\",\"objectIRI\":\"%s\",\"literalValue\":null,\"axiomType\":\"Type\",\"source\":\"inferred\",\"reasoner\":\"%s\"}",
                    entry.ontologyId(), entry.subjectIRI(), entry.predicateIRI(), entry.objectIRI(), entry.reasoner()));
                writer.newLine();
            }
        }
    }

    private String serializeReportToJson(ReasoningReport report) {
        // Simple JSON serialization (no external JSON library)
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"ontologyId\": \"").append(escapeJson(report.ontologyId())).append("\",\n");
        sb.append("  \"reasonerName\": \"").append(escapeJson(report.reasonerName())).append("\",\n");
        sb.append("  \"owlProfile\": \"").append(escapeJson(report.owlProfile())).append("\",\n");
        sb.append("  \"classificationStatus\": ").append(report.classificationStatus()).append(",\n");
        sb.append("  \"realizationStatus\": ").append(report.realizationStatus() != null ? report.realizationStatus() : "null").append(",\n");
        sb.append("  \"consistencyStatus\": ").append(report.consistencyStatus()).append(",\n");

        ReasoningReport.TimingBreakdown timing = report.timingBreakdown();
        sb.append("  \"timingBreakdown\": {\n");
        sb.append("    \"initializationTimeMs\": ").append(timing.initializationTimeMs()).append(",\n");
        sb.append("    \"classificationTimeMs\": ").append(timing.classificationTimeMs()).append(",\n");
        sb.append("    \"realizationTimeMs\": ").append(timing.realizationTimeMs()).append(",\n");
        sb.append("    \"totalTimeMs\": ").append(timing.totalTimeMs()).append("\n");
        sb.append("  },\n");

        sb.append("  \"warningCount\": ").append(report.warningCount()).append(",\n");

        sb.append("  \"inferredAxiomCountsByType\": {\n");
        boolean first = true;
        for (Map.Entry<String, Integer> e : report.inferredAxiomCountsByType().entrySet()) {
            if (!first) sb.append(",\n");
            sb.append("    \"").append(escapeJson(e.getKey())).append("\": ").append(e.getValue());
            first = false;
        }
        sb.append("\n  },\n");

        ReasoningReport.ErrorDetails error = report.errorDetails();
        if (error != null) {
            sb.append("  \"errorDetails\": {\n");
            sb.append("    \"errorCode\": \"").append(escapeJson(error.errorCode())).append("\",\n");
            sb.append("    \"message\": \"").append(escapeJson(error.message())).append("\"");
            if (error.stackTrace() != null) {
                sb.append(",\n    \"stackTrace\": \"").append(escapeJson(error.stackTrace())).append("\"");
            }
            sb.append("\n  }\n");
        } else {
            sb.append("  \"errorDetails\": null\n");
        }

        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t");
    }

    private List<InferredFact> readInferredFactsFile(Path factsFile) throws IOException {
        List<InferredFact> facts = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(factsFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Parse simple JSON manually
                // For production, use a proper JSON parser
                InferredFact fact = parseInferredFact(line);
                if (fact != null) {
                    facts.add(fact);
                }
            }
        }
        return facts;
    }

    private InferredFact parseInferredFact(String jsonLine) {
        // Minimal JSON parsing for the JSONL format
        try {
            String ontologyId = extractJsonValue(jsonLine, "ontologyId");
            String subjectIRI = extractJsonValue(jsonLine, "subjectIRI");
            String predicateIRI = extractJsonValue(jsonLine, "predicateIRI");
            String objectIRI = extractJsonValue(jsonLine, "objectIRI");
            String literalValue = extractJsonValue(jsonLine, "literalValue");
            String axiomType = extractJsonValue(jsonLine, "axiomType");
            String source = extractJsonValue(jsonLine, "source");
            String reasoner = extractJsonValue(jsonLine, "reasoner");

            return new InferredFact(ontologyId, subjectIRI, predicateIRI, objectIRI, literalValue, axiomType, source, reasoner);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start == -1) return null;
        start += pattern.length();

        // Skip whitespace
        while (start < json.length() && json.charAt(start) == ' ') start++;

        if (start >= json.length()) return null;

        if (json.charAt(start) == '"') {
            // String value
            int end = json.indexOf('"', start + 1);
            if (end == -1) return null;
            return json.substring(start + 1, end);
        } else if (json.charAt(start) == 'n') {
            // null value
            return null;
        } else {
            // Numeric or boolean value
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != '\n') {
                end++;
            }
            return json.substring(start, end).trim();
        }
    }

    private boolean checkAxiomEntailment(OWLReasonerAdapter adapter, OWLOntology ontology,
                                           String axiomType, Map<String, String> parameters) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();

        switch (axiomType) {
            case "SubClassOf": {
                String subclassIRI = parameters.get("subclass");
                String superclassIRI = parameters.get("superclass");
                if (subclassIRI == null || superclassIRI == null) return false;
                OWLClass subClass = df.getOWLClass(IRI.create(subclassIRI));
                OWLClass superClass = df.getOWLClass(IRI.create(superclassIRI));

                // Check if explicitly asserted first
                boolean explicit = ontology.getAxioms(AxiomType.SUBCLASS_OF, Imports.INCLUDED).stream()
                    .anyMatch(ax -> ax.getSubClass().equals(subClass) && ax.getSuperClass().equals(superClass));
                if (explicit) return true;

                // Check inferred via reasoner
                try {
                    NodeSet<OWLClass> inferredSupers = adapter.isActive() ?
                        ((org.semanticweb.owlapi.reasoner.OWLReasoner) getOWLReasonerFromAdapter(adapter)).getSuperClasses(subClass, false) :
                        null;
                    if (inferredSupers != null) {
                        return inferredSupers.getFlattened().contains(superClass);
                    }
                } catch (Exception e) {
                    // Fall back to checking stored inferred data
                }

                // Check in stored inferred hierarchy
                return checkStoredEntailment(ontology, "SubClassOf", subclassIRI, superclassIRI);
            }

            case "ClassAssertion": {
                String individualIRI = parameters.get("individual");
                String classIRI = parameters.get("class");
                if (individualIRI == null || classIRI == null) return false;

                boolean explicit = ontology.getAxioms(AxiomType.CLASS_ASSERTION, Imports.INCLUDED).stream()
                    .anyMatch(ax -> ax.getIndividual().isNamed() &&
                        ax.getIndividual().asOWLNamedIndividual().getIRI().toString().equals(individualIRI) &&
                        ax.getClassExpression().isNamed() &&
                        ax.getClassExpression().asOWLClass().getIRI().toString().equals(classIRI));
                if (explicit) return true;

                return checkStoredEntailment(ontology, "Type", individualIRI, classIRI);
            }

            default:
                // For other axiom types, check stored inferred data
                return false;
        }
    }

    private boolean checkStoredEntailment(OWLOntology ontology, String type, String subject, String object) {
        // This would check the stored inferred-facts.jsonl
        // Simplified for now — rely on reasoner results
        return false;
    }

    private String determineSource(OWLOntology ontology, String axiomType, Map<String, String> parameters) {
        // Check if the axiom is explicitly in the ontology
        // Simplified — return "inferred" if not explicitly found
        return "explicit"; // Will be refined in full implementation
    }

    private Object getOWLReasonerFromAdapter(OWLReasonerAdapter adapter) {
        // Access to internal OWLReasoner for advanced operations
        // This is a temporary bridge; the full implementation will use adapter methods
        return null;
    }
}