package org.owl4agents.mcp;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;
import org.owl4agents.owlapi.OntologySummaryExtractor;
import org.owl4agents.query.*;
import org.owl4agents.retrieval.*;
import org.owl4agents.storage.*;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP server adapter over the shared OntologyService.
 * v0.1 only exposes readonly tools.
 * Write-style tool calls are rejected with readonly-policy errors.
 */
public class McpServerAdapter {

    private final Map<String, Object> serviceContext;
    private final McpToolRegistry toolRegistry;
    private final McpToolCallLogger callLogger;
    private final HomeDirectoryResolver homeResolver;
    private final CatalogStore catalogStore;
    private final OntologySummaryExtractor summaryExtractor;
    private final SparqlValidator sparqlValidator;
    private final SparqlExecutor sparqlExecutor;
    private final SparqlSafetyGuard sparqlSafetyGuard;

    // Cached service instances — share lifecycle manager across MCP tool calls
    private org.owl4agents.reasoner.ReasonerServiceImpl reasonerService;
    private org.owl4agents.validation.ConsistencyAnalysisService consistencyAnalysisService;
    private org.owl4agents.owlapi.SemanticDeepeningService semanticDeepeningService;

    /**
     * Get the cached reasoner service instance.
     * Uses homeDir/workspaces as workspaceBasePath (matching CliServiceFactory).
     */
    private org.owl4agents.reasoner.ReasonerServiceImpl getReasonerService() {
        if (reasonerService == null) {
            String workspaceBasePath = homeResolver.resolveHomeDirectory()
                .resolve("workspaces").toString();
            reasonerService = new org.owl4agents.reasoner.ReasonerServiceImpl(catalogStore, workspaceBasePath);
        }
        return reasonerService;
    }

    /**
     * Get the cached consistency analysis service instance.
     * Shares lifecycle manager with the cached reasoner service.
     */
    private org.owl4agents.validation.ConsistencyAnalysisService getConsistencyAnalysisService() {
        if (consistencyAnalysisService == null) {
            String workspaceBasePath = homeResolver.resolveHomeDirectory()
                .resolve("workspaces").toString();
            consistencyAnalysisService = new org.owl4agents.validation.ConsistencyAnalysisService(
                getReasonerService().getLifecycleManager(), workspaceBasePath);
        }
        return consistencyAnalysisService;
    }

    /**
     * Get the cached semantic deepening service instance.
     * Uses homeDir/workspaces as workspaceBasePath (matching CliServiceFactory).
     */
    private org.owl4agents.owlapi.SemanticDeepeningService getSemanticDeepeningService() {
        if (semanticDeepeningService == null) {
            String workspaceBasePath = homeResolver.resolveHomeDirectory()
                .resolve("workspaces").toString();
            semanticDeepeningService = new org.owl4agents.owlapi.SemanticDeepeningService(workspaceBasePath);
        }
        return semanticDeepeningService;
    }

    public McpServerAdapter(Map<String, Object> serviceContext, String logFilePath) {
        this.serviceContext = serviceContext;
        this.toolRegistry = new McpToolRegistry();
        this.callLogger = new McpToolCallLogger(logFilePath);

        // Initialize service layer
        String homeDir = (String) serviceContext.get("homeDir");
        if (homeDir != null) {
            this.homeResolver = new HomeDirectoryResolver(Path.of(homeDir));
        } else {
            this.homeResolver = new HomeDirectoryResolver();
        }
        this.catalogStore = new CatalogStore(homeResolver);
        this.summaryExtractor = new OntologySummaryExtractor();
        this.sparqlValidator = new SparqlValidator();
        this.sparqlExecutor = new SparqlExecutor();
        this.sparqlSafetyGuard = new SparqlSafetyGuard();
    }

    /**
     * Handle an MCP tool call.
     * Routes through the shared service layer and logs every call.
     */
    public Map<String, Object> handleToolCall(String toolName, Map<String, Object> arguments) {
        Instant timestamp = Instant.now();
        String ontologyId = (String) arguments.getOrDefault("ontology_id", null);

        // Check if the tool is in the v0.1 readonly tool set
        if (!toolRegistry.isReadonlyTool(toolName)) {
            ServiceError error = ServiceError.readonlyViolation(toolName);
            callLogger.logCall(timestamp, toolName, ontologyId, "rejected", error.code().code());
            return errorResponse(error);
        }

        // Execute the tool call through the shared service
        Map<String, Object> result = executeReadonlyTool(toolName, arguments);
        String resultStatus = result.containsKey("error") ? "error" : "success";
        String errorCode = result.containsKey("error") ?
            ((Map<String, Object>) result.get("error")).get("code").toString() : null;

        callLogger.logCall(timestamp, toolName, ontologyId, resultStatus, errorCode);
        return result;
    }

    /**
     * List available MCP tools (v0.1 readonly only).
     */
    public List<Map<String, Object>> listTools() {
        return toolRegistry.listToolSchemas();
    }

    private Map<String, Object> executeReadonlyTool(String toolName, Map<String, Object> arguments) {
        switch (toolName) {
            // v0.1 tools
            case "ontology_list" -> { return executeOntologyList(arguments); }
            case "ontology_summary" -> { return executeOntologySummary(arguments); }
            case "ontology_get_metadata" -> { return executeGetMetadata(arguments); }
            case "ontology_get_profile" -> { return executeGetProfile(arguments); }
            case "ontology_list_graphs" -> { return executeListGraphs(arguments); }
            case "ontology_search_entities" -> { return executeSearchEntities(arguments); }
            case "ontology_get_entity_context" -> { return executeGetEntityContext(arguments); }
            case "ontology_get_class_context" -> { return executeGetClassContext(arguments); }
            case "ontology_get_object_property_context" -> { return executeGetObjectPropertyContext(arguments); }
            case "ontology_get_data_property_context" -> { return executeGetDataPropertyContext(arguments); }
            case "ontology_get_individual_context" -> { return executeGetIndividualContext(arguments); }
            case "ontology_get_graph_neighborhood" -> { return executeGetGraphNeighborhood(arguments); }
            case "ontology_validate_sparql" -> { return executeValidateSparql(arguments); }
            case "ontology_sparql_select" -> { return executeSparqlSelect(arguments); }
            case "ontology_sparql_ask" -> { return executeSparqlAsk(arguments); }
            case "ontology_sparql_construct" -> { return executeSparqlConstruct(arguments); }
            case "ontology_sparql_describe" -> { return executeSparqlDescribe(arguments); }
            case "ontology_get_qa_context" -> { return executeGetQaContext(arguments); }
            // v0.2 reasoner tools
            case "ontology_list_reasoners" -> { return executeListReasoners(arguments); }
            case "ontology_run_reasoner" -> { return executeRunReasoner(arguments); }
            case "ontology_classify" -> { return executeClassify(arguments); }
            case "ontology_realize_instances" -> { return executeRealize(arguments); }
            case "ontology_check_consistency" -> { return executeCheckConsistency(arguments); }
            case "ontology_explain_inconsistency" -> { return executeExplainInconsistency(arguments); }
            case "ontology_explain_unsat_class" -> { return executeExplainUnsatClass(arguments); }
            case "ontology_get_unsat_classes" -> { return executeGetUnsatClasses(arguments); }
            case "ontology_get_reasoning_report" -> { return executeGetReasoningReport(arguments); }
            case "ontology_get_inferred_facts" -> { return executeGetInferredFacts(arguments); }
            case "ontology_check_entailment" -> { return executeCheckEntailment(arguments); }
            // v0.2 consistency-analysis tools
            case "ontology_check_class_compatibility" -> { return executeCheckClassCompatibility(arguments); }
            case "ontology_check_individual_membership" -> { return executeCheckIndividualMembership(arguments); }
            case "ontology_check_relation_assertion" -> { return executeCheckRelationAssertion(arguments); }
            case "ontology_get_scope" -> { return executeGetScope(arguments); }
            // v0.2 semantic-deepening tools
            case "ontology_get_imports" -> { return executeGetImports(arguments); }
            case "ontology_get_class_restrictions" -> { return executeGetClassRestrictions(arguments); }
            case "ontology_get_property_characteristics" -> { return executeGetPropertyCharacteristics(arguments); }
            case "ontology_get_equivalent_properties" -> { return executeGetEquivalentProperties(arguments); }
            case "ontology_get_disjoint_properties" -> { return executeGetDisjointProperties(arguments); }
            case "ontology_get_datatype_constraints" -> { return executeGetDatatypeConstraints(arguments); }
            case "ontology_validate_literal" -> { return executeValidateLiteral(arguments); }
            case "ontology_find_relations_between_entities" -> { return executeFindRelations(arguments); }
            case "ontology_get_object_property_assertions" -> { return executeGetObjectPropertyAssertions(arguments); }
            case "ontology_get_data_property_assertions" -> { return executeGetDataPropertyAssertions(arguments); }
            case "ontology_get_same_individuals" -> { return executeGetSameIndividuals(arguments); }
            case "ontology_get_different_individuals" -> { return executeGetDifferentIndividuals(arguments); }
            default -> { return errorResponse(ServiceError.readonlyViolation(toolName)); }
        }
    }

    // ── Real implementations ──

    private Map<String, Object> executeOntologyList(Map<String, Object> args) {
        String workspaceName = (String) args.getOrDefault("workspace", "default");
        WorkspaceId workspaceId = new WorkspaceId(workspaceName);

        ServiceResult<List<CatalogEntry>> result = catalogStore.readCatalog(workspaceId);
        if (!result.isSuccess()) {
            return errorResponse(((ServiceResult.Error<List<CatalogEntry>>) result).error());
        }

        List<CatalogEntry> entries = ((ServiceResult.Success<List<CatalogEntry>>) result).data();
        List<Map<String, Object>> ontologies = entries.stream()
            .map(e -> Map.<String, Object>of(
                "ontologyId", e.ontologyId().id(),
                "displayName", e.displayName(),
                "importTimestamp", e.importTimestamp().toString()
            ))
            .collect(Collectors.toList());

        return Map.of("status", "success", "data", Map.of("ontologies", ontologies));
    }

    private Map<String, Object> executeOntologySummary(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        if (ontologyIdStr == null) {
            return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, "ontology_id is required"));
        }

        OntologyId ontologyId = new OntologyId(ontologyIdStr);
        ServiceResult<OntologySummary> result = summaryExtractor.extractSummary(ontologyId,
            findCanonicalPath(ontologyId));

        if (!result.isSuccess()) {
            return errorResponse(((ServiceResult.Error<OntologySummary>) result).error());
        }

        OntologySummary summary = ((ServiceResult.Success<OntologySummary>) result).data();
        return Map.of("status", "success", "data", Map.of(
            "ontologyIri", summary.ontologyIri() != null ? summary.ontologyIri() : "",
            "versionIri", summary.versionIri() != null ? summary.versionIri() : "",
            "imports", summary.imports(),
            "profile", summary.profile() != null ? summary.profile().profiles() : List.of(),
            "entityCounts", Map.of(
                "classes", summary.entityCounts().classes(),
                "objectProperties", summary.entityCounts().objectProperties(),
                "dataProperties", summary.entityCounts().dataProperties(),
                "individuals", summary.entityCounts().individuals()
            )
        ));
    }

    private Map<String, Object> executeGetMetadata(Map<String, Object> args) {
        // Similar to summary but with more metadata
        return executeOntologySummary(args);
    }

    private Map<String, Object> executeGetProfile(Map<String, Object> args) {
        return executeOntologySummary(args);
    }

    private Map<String, Object> executeListGraphs(Map<String, Object> args) {
        // v0.2: include inferred and union scopes alongside explicit
        return Map.of("status", "success", "data", Map.of("scopes", List.of("explicit", "inferred", "union")));
    }

    private Map<String, Object> executeSearchEntities(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String query = (String) args.get("query");
        if (ontologyIdStr == null || query == null) {
            return errorResponse(ServiceError.of(ErrorCode.INVALID_SPARQL, "ontology_id and query are required"));
        }

        try {
            OntologyId ontologyId = new OntologyId(ontologyIdStr);
            EntityIndex index = loadEntityIndex(ontologyId);
            EntitySearchService searchService = new EntitySearchService(index, ontologyId);

            ServiceResult<SearchResult> result = searchService.search(query);
            if (!result.isSuccess()) {
                return errorResponse(((ServiceResult.Error<SearchResult>) result).error());
            }

            SearchResult searchResult = ((ServiceResult.Success<SearchResult>) result).data();
            List<Map<String, Object>> matches = searchResult.results().stream()
                .map(m -> Map.<String, Object>of(
                    "iri", m.iri(),
                    "label", m.label() != null ? m.label() : "",
                    "type", m.type().jsonName(),
                    "score", m.score()
                ))
                .collect(Collectors.toList());

            return Map.of("status", "success", "data", Map.of(
                "results", matches,
                "totalResults", searchResult.totalResults()
            ));
        } catch (Exception e) {
            return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage()));
        }
    }

    private Map<String, Object> executeGetEntityContext(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String entityIri = (String) args.get("entity_iri");
        if (ontologyIdStr == null || entityIri == null) {
            return errorResponse(ServiceError.of(ErrorCode.ENTITY_NOT_FOUND, "ontology_id and entity_iri are required"));
        }

        try {
            OntologyId ontologyId = new OntologyId(ontologyIdStr);
            EntityIndex index = loadEntityIndex(ontologyId);
            EntitySearchService searchService = new EntitySearchService(index, ontologyId);

            ServiceResult<SearchResult> searchResult = searchService.search(entityIri);
            if (!searchResult.isSuccess() || ((ServiceResult.Success<SearchResult>) searchResult).data().results().isEmpty()) {
                return errorResponse(ServiceError.of(ErrorCode.ENTITY_NOT_FOUND, "Entity not found: " + entityIri));
            }

            SearchMatch match = ((ServiceResult.Success<SearchResult>) searchResult).data().results().get(0);
            EntityId entityId = new EntityId(match.iri());
            OWLOntology ontology = loadOntology(ontologyId);

            Map<String, Object> context = new HashMap<>();
            context.put("iri", match.iri());
            context.put("label", match.label() != null ? match.label() : "");
            context.put("type", match.type().jsonName());

            switch (match.type()) {
                case CLASS -> {
                    ClassContextService ctxService = new ClassContextService(index, ontologyId, ontology);
                    var ctx = ctxService.getClassContext(entityId);
                    if (ctx.isSuccess()) {
                        ClassContext cc = ((ServiceResult.Success<ClassContext>) ctx).data();
                        context.put("superclasses", cc.directSuperclasses());
                        context.put("subclasses", cc.directSubclasses());
                        context.put("equivalentClasses", cc.equivalentClasses());
                        context.put("disjointClasses", cc.disjointClasses());
                    }
                }
                case OBJECT_PROPERTY -> {
                    ObjectPropertyContextService ctxService = new ObjectPropertyContextService(index, ontologyId, ontology);
                    var ctx = ctxService.getObjectPropertyContext(entityId);
                    if (ctx.isSuccess()) {
                        ObjectPropertyContext pc = ((ServiceResult.Success<ObjectPropertyContext>) ctx).data();
                        context.put("domain", pc.domain());
                        context.put("range", pc.range());
                        context.put("inverseProperties", pc.inverseProperties());
                    }
                }
                case DATA_PROPERTY -> {
                    DataPropertyContextService ctxService = new DataPropertyContextService(index, ontologyId, ontology);
                    var ctx = ctxService.getDataPropertyContext(entityId);
                    if (ctx.isSuccess()) {
                        DataPropertyContext dc = ((ServiceResult.Success<DataPropertyContext>) ctx).data();
                        context.put("domain", dc.domain());
                        context.put("range", dc.range());
                        context.put("datatype", dc.datatype());
                    }
                }
                case INDIVIDUAL -> {
                    IndividualContextService ctxService = new IndividualContextService(index, ontologyId, ontology);
                    var ctx = ctxService.getIndividualContext(entityId);
                    if (ctx.isSuccess()) {
                        IndividualContext ic = ((ServiceResult.Success<IndividualContext>) ctx).data();
                        context.put("types", ic.explicitTypes());
                    }
                }
                default -> {
                    // ANNOTATION_PROPERTY, DATATYPE - no detailed context for v0.1
                }
            }

            return Map.of("status", "success", "data", context);
        } catch (Exception e) {
            return errorResponse(ServiceError.of(ErrorCode.ENTITY_NOT_FOUND, e.getMessage()));
        }
    }

    private Map<String, Object> executeGetClassContext(Map<String, Object> args) {
        return executeGetEntityContext(args);
    }

    private Map<String, Object> executeGetObjectPropertyContext(Map<String, Object> args) {
        return executeGetEntityContext(args);
    }

    private Map<String, Object> executeGetDataPropertyContext(Map<String, Object> args) {
        return executeGetEntityContext(args);
    }

    private Map<String, Object> executeGetIndividualContext(Map<String, Object> args) {
        return executeGetEntityContext(args);
    }

    private Map<String, Object> executeGetGraphNeighborhood(Map<String, Object> args) {
        return executeGetEntityContext(args);
    }

    private Map<String, Object> executeValidateSparql(Map<String, Object> args) {
        String query = (String) args.get("query");
        if (query == null) {
            return errorResponse(ServiceError.of(ErrorCode.INVALID_SPARQL, "query is required"));
        }

        // First check for readonly violations (SPARQL update operations)
        if (sparqlSafetyGuard.checkReadonly(query).isSuccess() == false) {
            var result = sparqlSafetyGuard.checkReadonly(query);
            return errorResponse(((ServiceResult.Error<Void>) result).error());
        }

        var result = sparqlValidator.validate(query);
        if (result.isSuccess()) {
            var vr = ((ServiceResult.Success<ValidationResult>) result).data();
            return Map.of("status", "success", "data", Map.of("valid", vr.isValid(), "queryForm", vr.queryForm()));
        }
        var error = ((ServiceResult.Error<ValidationResult>) result).error();
        return errorResponse(error);
    }

    private Map<String, Object> executeSparqlSelect(Map<String, Object> args) {
        return executeSparqlQuery(args, "SELECT");
    }

    private Map<String, Object> executeSparqlAsk(Map<String, Object> args) {
        return executeSparqlQuery(args, "ASK");
    }

    private Map<String, Object> executeSparqlConstruct(Map<String, Object> args) {
        return executeSparqlQuery(args, "CONSTRUCT");
    }

    private Map<String, Object> executeSparqlDescribe(Map<String, Object> args) {
        return executeSparqlQuery(args, "DESCRIBE");
    }

    private Map<String, Object> executeSparqlQuery(Map<String, Object> args, String expectedForm) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String query = (String) args.get("query");
        if (ontologyIdStr == null || query == null) {
            return errorResponse(ServiceError.of(ErrorCode.INVALID_SPARQL, "ontology_id and query are required"));
        }

        try {
            OntologyId ontologyId = new OntologyId(ontologyIdStr);

            // Check readonly
            var safetyResult = sparqlSafetyGuard.checkReadonly(query);
            if (!safetyResult.isSuccess()) {
                return errorResponse(((ServiceResult.Error<Void>) safetyResult).error());
            }

            // Validate
            var validationResult = sparqlValidator.validate(query);
            if (!validationResult.isSuccess()) {
                return errorResponse(((ServiceResult.Error<ValidationResult>) validationResult).error());
            }

            // Load Jena model
            org.apache.jena.rdf.model.Model jenaModel = createJenaModel(ontologyId);

            // Execute based on form
            switch (expectedForm) {
                case "SELECT" -> {
                    var result = sparqlExecutor.executeSelect(ontologyId, query, jenaModel, GraphScope.EXPLICIT);
                    if (!result.isSuccess()) {
                        return errorResponse(((ServiceResult.Error<SelectResult>) result).error());
                    }
                    SelectResult sr = ((ServiceResult.Success<SelectResult>) result).data();
                    return Map.of("status", "success", "data", Map.of(
                        "variables", sr.variables(),
                        "bindings", sr.bindings(),
                        "totalBindings", sr.totalBindings(),
                        "truncated", sr.truncated()
                    ));
                }
                case "ASK" -> {
                    var result = sparqlExecutor.executeAsk(ontologyId, query, jenaModel, GraphScope.EXPLICIT);
                    if (!result.isSuccess()) {
                        return errorResponse(((ServiceResult.Error<AskResult>) result).error());
                    }
                    AskResult ar = ((ServiceResult.Success<AskResult>) result).data();
                    return Map.of("status", "success", "data", Map.of("result", ar.result()));
                }
                case "CONSTRUCT" -> {
                    var result = sparqlExecutor.executeConstruct(ontologyId, query, jenaModel, GraphScope.EXPLICIT);
                    if (!result.isSuccess()) {
                        return errorResponse(((ServiceResult.Error<ConstructResult>) result).error());
                    }
                    ConstructResult cr = ((ServiceResult.Success<ConstructResult>) result).data();
                    return Map.of("status", "success", "data", Map.of(
                        "triples", cr.triples(),
                        "totalTriples", cr.totalTriples(),
                        "truncated", cr.truncated()
                    ));
                }
                case "DESCRIBE" -> {
                    var result = sparqlExecutor.executeDescribe(ontologyId, query, jenaModel, GraphScope.EXPLICIT);
                    if (!result.isSuccess()) {
                        return errorResponse(((ServiceResult.Error<DescribeResult>) result).error());
                    }
                    DescribeResult dr = ((ServiceResult.Success<DescribeResult>) result).data();
                    return Map.of("status", "success", "data", Map.of(
                        "triples", dr.triples(),
                        "totalTriples", dr.totalTriples(),
                        "truncated", dr.truncated()
                    ));
                }
                default -> {
                    return errorResponse(ServiceError.of(ErrorCode.INVALID_SPARQL, "Unsupported query form: " + expectedForm));
                }
            }
        } catch (Exception e) {
            return errorResponse(ServiceError.of(ErrorCode.INVALID_SPARQL, e.getMessage()));
        }
    }

    private Map<String, Object> executeGetQaContext(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String question = (String) args.get("question");
        if (ontologyIdStr == null || question == null) {
            return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, "ontology_id and question are required"));
        }

        try {
            OntologyId ontologyId = new OntologyId(ontologyIdStr);
            OWLOntology ontology = loadOntology(ontologyId);
            EntityIndex index = new EntityIndex();
            index.buildFromOntology(ontology);

            QaContextService qaService = new QaContextService(index, ontologyId, ontology);

            Integer maxEntities = args.containsKey("max_entities") ? ((Number) args.get("max_entities")).intValue() : null;
            Integer maxDepth = args.containsKey("max_depth") ? ((Number) args.get("max_depth")).intValue() : null;

            ServiceResult<QaContext> result = qaService.generateContext(
                question,
                Optional.ofNullable(maxEntities),
                Optional.ofNullable(maxDepth)
            );

            if (!result.isSuccess()) {
                return errorResponse(((ServiceResult.Error<QaContext>) result).error());
            }

            QaContext ctx = ((ServiceResult.Success<QaContext>) result).data();
            return Map.of("status", "success", "data", Map.of(
                "matchedEntities", ctx.matchedEntities().stream()
                    .map(m -> Map.<String, Object>of("iri", m.iri(), "label", m.label() != null ? m.label() : "", "type", m.type().jsonName()))
                    .collect(Collectors.toList()),
                "classContext", ctx.classContext().stream()
                    .map(c -> Map.<String, Object>of("iri", c.iri(), "label", c.label() != null ? c.label() : ""))
                    .collect(Collectors.toList()),
                "naturalLanguageContext", ctx.naturalLanguageContext(),
                "warnings", ctx.warnings().stream()
                    .map(w -> Map.<String, Object>of("type", w.type(), "message", w.message()))
                    .collect(Collectors.toList())
            ));
        } catch (Exception e) {
            return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage()));
        }
    }

    // ── v0.2 MCP tool implementations ──

    private Map<String, Object> executeListReasoners(Map<String, Object> args) {
        try {
            org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = getReasonerService();
            ServiceResult<ReasonerListResult> result = reasonerService.listReasoners();
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<ReasonerListResult>) result).error());
            ReasonerListResult data = ((ServiceResult.Success<ReasonerListResult>) result).data();
            List<Map<String, Object>> reasoners = data.reasoners().stream()
                .map(r -> Map.<String, Object>of("name", r.name(),
                    "supportedProfiles", r.supportedProfiles(),
                    "supportedOperations", r.supportedOperations(),
                    "explanationSupported", r.explanationSupported()))
                .collect(Collectors.toList());
            return Map.of("status", "success", "data", Map.of("reasoners", reasoners));
        } catch (Exception e) {
            return errorResponse(ServiceError.of(ErrorCode.REASONER_NOT_AVAILABLE, e.getMessage()));
        }
    }

    private Map<String, Object> executeRunReasoner(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        if (ontologyIdStr == null) return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, "ontology_id is required"));
        String reasonerName = (String) args.getOrDefault("reasoner", "auto");
        try {
            org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = getReasonerService();
            ServiceResult<ReasoningReport> result = reasonerService.runReasoner(
                new OntologyId(ontologyIdStr), Optional.ofNullable(reasonerName));
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<ReasoningReport>) result).error());
            ReasoningReport report = ((ServiceResult.Success<ReasoningReport>) result).data();
            return Map.of("status", "success", "data", serializeReport(report));
        } catch (Exception e) {
            return errorResponse(ServiceError.of(ErrorCode.CLASSIFICATION_FAILED, e.getMessage()));
        }
    }

    private Map<String, Object> executeClassify(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        if (ontologyIdStr == null) return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, "ontology_id is required"));
        String reasonerName = (String) args.getOrDefault("reasoner", "auto");
        try {
            org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = getReasonerService();
            ServiceResult<ClassificationResult> result = reasonerService.classify(
                new OntologyId(ontologyIdStr), Optional.ofNullable(reasonerName));
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<ClassificationResult>) result).error());
            ClassificationResult data = ((ServiceResult.Success<ClassificationResult>) result).data();
            return Map.of("status", "success", "data", Map.of(
                "ontologyId", data.ontologyId(), "reasonerName", data.reasonerName(),
                "completeHierarchyCount", data.completeHierarchy().size(), "deltaCount", data.delta().size()));
        } catch (Exception e) {
            return errorResponse(ServiceError.of(ErrorCode.CLASSIFICATION_FAILED, e.getMessage()));
        }
    }

    private Map<String, Object> executeRealize(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        if (ontologyIdStr == null) return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, "ontology_id is required"));
        String reasonerName = (String) args.getOrDefault("reasoner", "auto");
        try {
            org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = getReasonerService();
            ServiceResult<RealizationResult> result = reasonerService.realize(
                new OntologyId(ontologyIdStr), Optional.ofNullable(reasonerName));
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<RealizationResult>) result).error());
            RealizationResult data = ((ServiceResult.Success<RealizationResult>) result).data();
            return Map.of("status", "success", "data", Map.of(
                "ontologyId", data.ontologyId(), "reasonerName", data.reasonerName(),
                "completeTypesCount", data.completeTypes().size(), "deltaCount", data.delta().size()));
        } catch (Exception e) {
            return errorResponse(ServiceError.of(ErrorCode.CLASSIFICATION_FAILED, e.getMessage()));
        }
    }

    private Map<String, Object> executeCheckConsistency(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        if (ontologyIdStr == null) return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, "ontology_id is required"));
        String reasonerName = (String) args.getOrDefault("reasoner", "auto");
        try {
            org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = getReasonerService();
            ServiceResult<ConsistencyResult> result = reasonerService.checkConsistency(
                new OntologyId(ontologyIdStr), Optional.ofNullable(reasonerName));
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<ConsistencyResult>) result).error());
            ConsistencyResult data = ((ServiceResult.Success<ConsistencyResult>) result).data();
            return Map.of("status", "success", "data", Map.of(
                "consistent", data.consistent(), "reasonerName", data.reasonerName(),
                "unsatisfiableClassIRIs", data.unsatisfiableClassIRIs()));
        } catch (Exception e) {
            return errorResponse(ServiceError.of(ErrorCode.CLASSIFICATION_FAILED, e.getMessage()));
        }
    }

    private Map<String, Object> executeExplainInconsistency(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        if (ontologyIdStr == null) return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, "ontology_id is required"));
        String reasonerName = (String) args.getOrDefault("reasoner", "openllet");
        try {
            org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = getReasonerService();
            ServiceResult<InconsistencyExplanation> result = reasonerService.explainInconsistency(
                new OntologyId(ontologyIdStr), Optional.ofNullable(reasonerName));
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<InconsistencyExplanation>) result).error());
            InconsistencyExplanation data = ((ServiceResult.Success<InconsistencyExplanation>) result).data();
            return Map.of("status", "success", "data", Map.of(
                "ontologyId", data.ontologyId(), "explanationCount", data.explanationCount(),
                "conflictingAxiomSets", data.conflictingAxiomSets().stream()
                    .map(s -> Map.<String, Object>of("axiomDescriptions", s.axiomDescriptions(), "syntaxFormat", s.syntaxFormat()))
                    .collect(Collectors.toList())));
        } catch (Exception e) {
            return errorResponse(ServiceError.of(ErrorCode.EXPLANATION_FAILED, e.getMessage()));
        }
    }

    private Map<String, Object> executeExplainUnsatClass(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String classIRI = (String) args.get("class_uri");
        if (ontologyIdStr == null || classIRI == null) return errorResponse(ServiceError.of(ErrorCode.CLASS_NOT_FOUND, "ontology_id and class_uri are required"));
        String reasonerName = (String) args.getOrDefault("reasoner", "openllet");
        try {
            org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = getReasonerService();
            ServiceResult<UnsatClassExplanation> result = reasonerService.explainUnsatClass(
                new OntologyId(ontologyIdStr), classIRI, Optional.ofNullable(reasonerName));
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<UnsatClassExplanation>) result).error());
            UnsatClassExplanation data = ((ServiceResult.Success<UnsatClassExplanation>) result).data();
            return Map.of("status", "success", "data", Map.of(
                "ontologyId", data.ontologyId(), "classURI", data.classIRI(),
                "explanationCount", data.explanationCount()));
        } catch (Exception e) {
            return errorResponse(ServiceError.of(ErrorCode.EXPLANATION_FAILED, e.getMessage()));
        }
    }

    private Map<String, Object> executeGetUnsatClasses(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        if (ontologyIdStr == null) return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, "ontology_id is required"));
        try {
            org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = getReasonerService();
            ServiceResult<List<String>> result = reasonerService.getUnsatClasses(new OntologyId(ontologyIdStr));
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<List<String>>) result).error());
            return Map.of("status", "success", "data", Map.of("unsatisfiableClassIRIs", ((ServiceResult.Success<List<String>>) result).data()));
        } catch (Exception e) {
            return errorResponse(ServiceError.of(ErrorCode.CLASSIFICATION_FAILED, e.getMessage()));
        }
    }

    private Map<String, Object> executeGetReasoningReport(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        if (ontologyIdStr == null) return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, "ontology_id is required"));
        try {
            org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = getReasonerService();
            ServiceResult<ReasoningReport> result = reasonerService.getReasoningReport(new OntologyId(ontologyIdStr));
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<ReasoningReport>) result).error());
            return Map.of("status", "success", "data", serializeReport(((ServiceResult.Success<ReasoningReport>) result).data()));
        } catch (Exception e) {
            return errorResponse(ServiceError.of(ErrorCode.REASONING_NOT_RUN, e.getMessage()));
        }
    }

    private Map<String, Object> executeGetInferredFacts(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        if (ontologyIdStr == null) return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, "ontology_id is required"));
        String entityIRI = (String) args.getOrDefault("entity_iri", null);
        try {
            org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = getReasonerService();
            ServiceResult<InferredFactsResult> result = reasonerService.getInferredFacts(
                new OntologyId(ontologyIdStr), Optional.ofNullable(entityIRI));
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<InferredFactsResult>) result).error());
            InferredFactsResult data = ((ServiceResult.Success<InferredFactsResult>) result).data();
            return Map.of("status", "success", "data", Map.of("ontologyId", data.ontologyId(), "factsCount", data.facts().size()));
        } catch (Exception e) {
            return errorResponse(ServiceError.of(ErrorCode.REASONING_NOT_RUN, e.getMessage()));
        }
    }

    private Map<String, Object> executeCheckEntailment(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String axiomType = (String) args.get("axiom_type");
        if (ontologyIdStr == null || axiomType == null) return errorResponse(ServiceError.of(ErrorCode.INVALID_AXIOM_PARAMETERS, "ontology_id and axiom_type are required"));
        String reasonerName = (String) args.getOrDefault("reasoner", "auto");
        Map<String, String> params = new HashMap<>();
        args.forEach((k, v) -> { if (!k.equals("ontology_id") && !k.equals("axiom_type") && !k.equals("reasoner")) params.put(k, v != null ? v.toString() : null); });
        try {
            org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = getReasonerService();
            ServiceResult<EntailmentResult> result = reasonerService.checkEntailment(
                new OntologyId(ontologyIdStr), axiomType, params, Optional.ofNullable(reasonerName));
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<EntailmentResult>) result).error());
            EntailmentResult data = ((ServiceResult.Success<EntailmentResult>) result).data();
            return Map.of("status", "success", "data", Map.of("result", data.result(), "axiomType", data.axiomType(), "source", data.source() != null ? data.source() : ""));
        } catch (Exception e) {
            return errorResponse(ServiceError.of(ErrorCode.CLASSIFICATION_FAILED, e.getMessage()));
        }
    }

    // v0.2 consistency-analysis tools
    private Map<String, Object> executeCheckClassCompatibility(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String class1IRI = (String) args.get("class1_uri");
        String class2IRI = (String) args.get("class2_uri");
        if (ontologyIdStr == null || class1IRI == null || class2IRI == null) return errorResponse(ServiceError.of(ErrorCode.CLASS_NOT_FOUND, "ontology_id, class1_uri, and class2_uri are required"));
        try {
            org.owl4agents.validation.ConsistencyAnalysisService analysisService = getConsistencyAnalysisService();
            ServiceResult<ClassCompatibilityResult> result = analysisService.checkClassCompatibility(
                new OntologyId(ontologyIdStr), class1IRI, class2IRI);
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<ClassCompatibilityResult>) result).error());
            ClassCompatibilityResult data = ((ServiceResult.Success<ClassCompatibilityResult>) result).data();
            return Map.of("status", "success", "data", Map.of("compatibility", data.compatibility(), "class1IRI", data.class1IRI(), "class2IRI", data.class2IRI()));
        } catch (Exception e) {
            return errorResponse(ServiceError.of(ErrorCode.CLASSIFICATION_FAILED, e.getMessage()));
        }
    }

    private Map<String, Object> executeCheckIndividualMembership(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String individualIRI = (String) args.get("individual_uri");
        String classIRI = (String) args.get("class_uri");
        if (ontologyIdStr == null || individualIRI == null || classIRI == null) return errorResponse(ServiceError.of(ErrorCode.INDIVIDUAL_NOT_FOUND, "ontology_id, individual_uri, and class_uri are required"));
        String reasonerName = (String) args.getOrDefault("reasoner", "auto");
        try {
            org.owl4agents.validation.ConsistencyAnalysisService analysisService = getConsistencyAnalysisService();
            ServiceResult<MembershipResult> result = analysisService.checkIndividualMembership(
                new OntologyId(ontologyIdStr), individualIRI, classIRI, Optional.ofNullable(reasonerName));
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<MembershipResult>) result).error());
            MembershipResult data = ((ServiceResult.Success<MembershipResult>) result).data();
            return Map.of("status", "success", "data", Map.of("isMember", data.isMember(), "membershipType", data.membershipType() != null ? data.membershipType() : ""));
        } catch (Exception e) { return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage())); }
    }

    private Map<String, Object> executeCheckRelationAssertion(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String sourceIRI = (String) args.get("source_individual_uri");
        String propertyIRI = (String) args.get("property_uri");
        String targetIRI = (String) args.get("target_individual_uri");
        if (ontologyIdStr == null || sourceIRI == null || propertyIRI == null || targetIRI == null) return errorResponse(ServiceError.of(ErrorCode.INDIVIDUAL_NOT_FOUND, "ontology_id, source, property, and target are required"));
        String reasonerName = (String) args.getOrDefault("reasoner", "auto");
        try {
            org.owl4agents.validation.ConsistencyAnalysisService analysisService = getConsistencyAnalysisService();
            ServiceResult<RelationAssertionResult> result = analysisService.checkRelationAssertion(
                new OntologyId(ontologyIdStr), sourceIRI, propertyIRI, targetIRI, Optional.ofNullable(reasonerName));
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<RelationAssertionResult>) result).error());
            RelationAssertionResult data = ((ServiceResult.Success<RelationAssertionResult>) result).data();
            return Map.of("status", "success", "data", Map.of("isAsserted", data.isAsserted(), "assertionType", data.assertionType() != null ? data.assertionType() : ""));
        } catch (Exception e) { return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage())); }
    }

    private Map<String, Object> executeGetScope(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        if (ontologyIdStr == null) return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, "ontology_id is required"));
        try {
            org.owl4agents.validation.ConsistencyAnalysisService analysisService = getConsistencyAnalysisService();
            ServiceResult<ScopeDescription> result = analysisService.getScope(new OntologyId(ontologyIdStr));
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<ScopeDescription>) result).error());
            ScopeDescription data = ((ServiceResult.Success<ScopeDescription>) result).data();
            return Map.of("status", "success", "data", Map.of(
                "ontologyId", data.ontologyId(), "coveredDomains", data.coveredDomains(),
                "knownGaps", data.knownGaps(), "profileLimitations", data.profileLimitations(),
                "unsupportedFeatureTypes", data.unsupportedFeatureTypes()));
        } catch (Exception e) { return errorResponse(ServiceError.of(ErrorCode.SCOPE_ANALYSIS_FAILED, e.getMessage())); }
    }

    // v0.2 semantic-deepening tools
    private Map<String, Object> executeGetImports(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        if (ontologyIdStr == null) return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, "ontology_id is required"));
        try {
            org.owl4agents.owlapi.SemanticDeepeningService service = getSemanticDeepeningService();
            ServiceResult<ImportClosureResult> result = service.getImportClosure(new OntologyId(ontologyIdStr));
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<ImportClosureResult>) result).error());
            ImportClosureResult data = ((ServiceResult.Success<ImportClosureResult>) result).data();
            return Map.of("status", "success", "data", Map.of("ontologyId", data.ontologyId(), "imports", data.imports().stream()
                .map(i -> Map.<String, Object>of("ontologyIRI", i.ontologyIRI() != null ? i.ontologyIRI() : "", "isDirect", i.isDirect())).collect(Collectors.toList())));
        } catch (Exception e) { return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage())); }
    }

    private Map<String, Object> executeGetClassRestrictions(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String classIRI = (String) args.get("class_uri");
        if (ontologyIdStr == null || classIRI == null) return errorResponse(ServiceError.of(ErrorCode.CLASS_NOT_FOUND, "ontology_id and class_uri are required"));
        boolean includeInferred = Boolean.parseBoolean((String) args.getOrDefault("include_inferred", "false"));
        try {
            org.owl4agents.owlapi.SemanticDeepeningService service = getSemanticDeepeningService();
            ServiceResult<ClassRestrictionsResult> result = service.getClassRestrictions(new OntologyId(ontologyIdStr), classIRI, includeInferred);
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<ClassRestrictionsResult>) result).error());
            ClassRestrictionsResult data = ((ServiceResult.Success<ClassRestrictionsResult>) result).data();
            return Map.of("status", "success", "data", Map.of("ontologyId", data.ontologyId(), "classIRI", data.classIRI(),
                "restrictions", data.restrictions().stream().map(r -> Map.<String, Object>of(
                    "restrictionType", r.restrictionType(), "onProperty", r.onProperty(),
                    "filler", r.filler() != null ? r.filler() : "", "cardinality", r.cardinality() != null ? r.cardinality() : 0))
                .collect(Collectors.toList())));
        } catch (Exception e) { return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage())); }
    }

    private Map<String, Object> executeGetPropertyCharacteristics(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String propertyIRI = (String) args.get("property_uri");
        if (ontologyIdStr == null || propertyIRI == null) return errorResponse(ServiceError.of(ErrorCode.PROPERTY_NOT_FOUND, "ontology_id and property_uri are required"));
        boolean includeInferred = Boolean.parseBoolean((String) args.getOrDefault("include_inferred", "false"));
        try {
            org.owl4agents.owlapi.SemanticDeepeningService service = getSemanticDeepeningService();
            ServiceResult<PropertyCharacteristicsResult> result = service.getPropertyCharacteristics(new OntologyId(ontologyIdStr), propertyIRI, includeInferred);
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<PropertyCharacteristicsResult>) result).error());
            PropertyCharacteristicsResult data = ((ServiceResult.Success<PropertyCharacteristicsResult>) result).data();
            return Map.of("status", "success", "data", Map.of("propertyIRI", data.propertyIRI(), "propertyType", data.propertyType(),
                "functional", data.functional(), "inverseFunctional", data.inverseFunctional(), "transitive", data.transitive(),
                "symmetric", data.symmetric(), "asymmetric", data.asymmetric(), "reflexive", data.reflexive(), "irreflexive", data.irreflexive()));
        } catch (Exception e) { return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage())); }
    }

    private Map<String, Object> executeGetEquivalentProperties(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String propertyIRI = (String) args.get("property_uri");
        if (ontologyIdStr == null || propertyIRI == null) return errorResponse(ServiceError.of(ErrorCode.PROPERTY_NOT_FOUND, "ontology_id and property_uri are required"));
        boolean includeInferred = Boolean.parseBoolean((String) args.getOrDefault("include_inferred", "false"));
        try {
            org.owl4agents.owlapi.SemanticDeepeningService service = getSemanticDeepeningService();
            ServiceResult<PropertyAxiomsResult> result = service.getEquivalentProperties(new OntologyId(ontologyIdStr), propertyIRI, includeInferred);
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<PropertyAxiomsResult>) result).error());
            PropertyAxiomsResult data = ((ServiceResult.Success<PropertyAxiomsResult>) result).data();
            return Map.of("status", "success", "data", Map.of("propertyIRI", data.propertyIRI(), "relatedProperties", data.relatedPropertyIRIs()));
        } catch (Exception e) { return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage())); }
    }

    private Map<String, Object> executeGetDisjointProperties(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String propertyIRI = (String) args.get("property_uri");
        if (ontologyIdStr == null || propertyIRI == null) return errorResponse(ServiceError.of(ErrorCode.PROPERTY_NOT_FOUND, "ontology_id and property_uri are required"));
        boolean includeInferred = Boolean.parseBoolean((String) args.getOrDefault("include_inferred", "false"));
        try {
            org.owl4agents.owlapi.SemanticDeepeningService service = getSemanticDeepeningService();
            ServiceResult<PropertyAxiomsResult> result = service.getDisjointProperties(new OntologyId(ontologyIdStr), propertyIRI, includeInferred);
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<PropertyAxiomsResult>) result).error());
            PropertyAxiomsResult data = ((ServiceResult.Success<PropertyAxiomsResult>) result).data();
            return Map.of("status", "success", "data", Map.of("propertyIRI", data.propertyIRI(), "disjointProperties", data.relatedPropertyIRIs()));
        } catch (Exception e) { return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage())); }
    }

    private Map<String, Object> executeGetDatatypeConstraints(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String datatypeIRI = (String) args.get("datatype_uri");
        if (ontologyIdStr == null || datatypeIRI == null) return errorResponse(ServiceError.of(ErrorCode.DATATYPE_NOT_FOUND, "ontology_id and datatype_uri are required"));
        try {
            org.owl4agents.owlapi.SemanticDeepeningService service = getSemanticDeepeningService();
            ServiceResult<DatatypeConstraintsResult> result = service.getDatatypeConstraints(new OntologyId(ontologyIdStr), datatypeIRI);
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<DatatypeConstraintsResult>) result).error());
            DatatypeConstraintsResult data = ((ServiceResult.Success<DatatypeConstraintsResult>) result).data();
            return Map.of("status", "success", "data", Map.of("datatypeIRI", data.datatypeIRI(), "baseDatatypeIRI", data.baseDatatypeIRI() != null ? data.baseDatatypeIRI() : "",
                "facets", data.facets().stream().map(f -> Map.<String, Object>of("facetType", f.facetType(), "facetValue", f.facetValue())).collect(Collectors.toList())));
        } catch (Exception e) { return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage())); }
    }

    private Map<String, Object> executeValidateLiteral(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String literalValue = (String) args.get("literal_value");
        String datatypeIRI = (String) args.get("datatype_uri");
        if (ontologyIdStr == null || literalValue == null || datatypeIRI == null) return errorResponse(ServiceError.of(ErrorCode.DATATYPE_NOT_FOUND, "ontology_id, literal_value, and datatype_uri are required"));
        String propertyIRI = (String) args.getOrDefault("property_uri", null);
        try {
            org.owl4agents.owlapi.SemanticDeepeningService service = getSemanticDeepeningService();
            ServiceResult<LiteralValidationResult> result = service.validateLiteral(new OntologyId(ontologyIdStr), literalValue, datatypeIRI, Optional.ofNullable(propertyIRI));
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<LiteralValidationResult>) result).error());
            LiteralValidationResult data = ((ServiceResult.Success<LiteralValidationResult>) result).data();
            return Map.of("status", "success", "data", Map.of("valid", data.valid(), "literalValue", data.literalValue(), "datatypeIRI", data.datatypeIRI(), "violations", data.violations()));
        } catch (Exception e) { return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage())); }
    }

    private Map<String, Object> executeFindRelations(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String sourceIRI = (String) args.get("source_entity_uri");
        String targetIRI = (String) args.get("target_entity_uri");
        if (ontologyIdStr == null || sourceIRI == null || targetIRI == null) return errorResponse(ServiceError.of(ErrorCode.ENTITY_NOT_FOUND, "ontology_id, source_entity_uri, and target_entity_uri are required"));
        boolean includeInferred = Boolean.parseBoolean((String) args.getOrDefault("include_inferred", "false"));
        try {
            org.owl4agents.owlapi.SemanticDeepeningService service = getSemanticDeepeningService();
            ServiceResult<PropertyAxiomsResult> result = service.findRelationsBetweenEntities(new OntologyId(ontologyIdStr), sourceIRI, targetIRI, includeInferred);
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<PropertyAxiomsResult>) result).error());
            PropertyAxiomsResult data = ((ServiceResult.Success<PropertyAxiomsResult>) result).data();
            return Map.of("status", "success", "data", Map.of("relations", data.relatedPropertyIRIs()));
        } catch (Exception e) { return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage())); }
    }

    private Map<String, Object> executeGetObjectPropertyAssertions(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String individualIRI = (String) args.get("individual_uri");
        if (ontologyIdStr == null || individualIRI == null) return errorResponse(ServiceError.of(ErrorCode.INDIVIDUAL_NOT_FOUND, "ontology_id and individual_uri are required"));
        boolean includeInferred = Boolean.parseBoolean((String) args.getOrDefault("include_inferred", "false"));
        try {
            org.owl4agents.owlapi.SemanticDeepeningService service = getSemanticDeepeningService();
            ServiceResult<PropertyAxiomsResult> result = service.getObjectPropertyAssertions(new OntologyId(ontologyIdStr), individualIRI, includeInferred);
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<PropertyAxiomsResult>) result).error());
            return Map.of("status", "success", "data", Map.of("assertions", ((ServiceResult.Success<PropertyAxiomsResult>) result).data().relatedPropertyIRIs()));
        } catch (Exception e) { return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage())); }
    }

    private Map<String, Object> executeGetDataPropertyAssertions(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String individualIRI = (String) args.get("individual_uri");
        if (ontologyIdStr == null || individualIRI == null) return errorResponse(ServiceError.of(ErrorCode.INDIVIDUAL_NOT_FOUND, "ontology_id and individual_uri are required"));
        boolean includeInferred = Boolean.parseBoolean((String) args.getOrDefault("include_inferred", "false"));
        try {
            org.owl4agents.owlapi.SemanticDeepeningService service = getSemanticDeepeningService();
            ServiceResult<PropertyAxiomsResult> result = service.getDataPropertyAssertions(new OntologyId(ontologyIdStr), individualIRI, includeInferred);
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<PropertyAxiomsResult>) result).error());
            return Map.of("status", "success", "data", Map.of("assertions", ((ServiceResult.Success<PropertyAxiomsResult>) result).data().relatedPropertyIRIs()));
        } catch (Exception e) { return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage())); }
    }

    private Map<String, Object> executeGetSameIndividuals(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String individualIRI = (String) args.get("individual_uri");
        if (ontologyIdStr == null || individualIRI == null) return errorResponse(ServiceError.of(ErrorCode.INDIVIDUAL_NOT_FOUND, "ontology_id and individual_uri are required"));
        boolean includeInferred = Boolean.parseBoolean((String) args.getOrDefault("include_inferred", "false"));
        try {
            org.owl4agents.owlapi.SemanticDeepeningService service = getSemanticDeepeningService();
            ServiceResult<PropertyAxiomsResult> result = service.getSameIndividuals(new OntologyId(ontologyIdStr), individualIRI, includeInferred);
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<PropertyAxiomsResult>) result).error());
            return Map.of("status", "success", "data", Map.of("sameAsIndividuals", ((ServiceResult.Success<PropertyAxiomsResult>) result).data().relatedPropertyIRIs()));
        } catch (Exception e) { return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage())); }
    }

    private Map<String, Object> executeGetDifferentIndividuals(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        String individualIRI = (String) args.get("individual_uri");
        if (ontologyIdStr == null || individualIRI == null) return errorResponse(ServiceError.of(ErrorCode.INDIVIDUAL_NOT_FOUND, "ontology_id and individual_uri are required"));
        boolean includeInferred = Boolean.parseBoolean((String) args.getOrDefault("include_inferred", "false"));
        try {
            org.owl4agents.owlapi.SemanticDeepeningService service = getSemanticDeepeningService();
            ServiceResult<PropertyAxiomsResult> result = service.getDifferentIndividuals(new OntologyId(ontologyIdStr), individualIRI, includeInferred);
            if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<PropertyAxiomsResult>) result).error());
            return Map.of("status", "success", "data", Map.of("differentFromIndividuals", ((ServiceResult.Success<PropertyAxiomsResult>) result).data().relatedPropertyIRIs()));
        } catch (Exception e) { return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage())); }
    }

    private Map<String, Object> serializeReport(ReasoningReport report) {
        Map<String, Object> m = new HashMap<>();
        m.put("ontologyId", report.ontologyId());
        m.put("reasonerName", report.reasonerName());
        m.put("owlProfile", report.owlProfile());
        m.put("classificationStatus", report.classificationStatus());
        m.put("realizationStatus", report.realizationStatus());
        m.put("consistencyStatus", report.consistencyStatus());
        m.put("warningCount", report.warningCount());
        m.put("inferredAxiomCountsByType", report.inferredAxiomCountsByType());
        if (report.timingBreakdown() != null) {
            m.put("timingBreakdown", Map.of(
                "initializationTimeMs", report.timingBreakdown().initializationTimeMs(),
                "classificationTimeMs", report.timingBreakdown().classificationTimeMs(),
                "realizationTimeMs", report.timingBreakdown().realizationTimeMs(),
                "totalTimeMs", report.timingBreakdown().totalTimeMs()));
        }
        if (report.errorDetails() != null) {
            m.put("errorDetails", Map.of("errorCode", report.errorDetails().errorCode(), "message", report.errorDetails().message()));
        }
        return m;
    }

    // ── Helper methods ──

    private Path findCanonicalPath(OntologyId ontologyId) {
        ServiceResult<CatalogEntry> result = catalogStore.findEntry(new WorkspaceId("default"), ontologyId);
        if (!result.isSuccess()) {
            throw new RuntimeException("Ontology not found: " + ontologyId.id());
        }
        return ((ServiceResult.Success<CatalogEntry>) result).data().canonicalPath();
    }

    private OWLOntology loadOntology(OntologyId ontologyId) throws Exception {
        Path canonicalPath = findCanonicalPath(ontologyId);
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(canonicalPath.toFile());
    }

    private EntityIndex loadEntityIndex(OntologyId ontologyId) throws Exception {
        OWLOntology ontology = loadOntology(ontologyId);
        EntityIndex index = new EntityIndex();
        index.buildFromOntology(ontology);
        return index;
    }

    private org.apache.jena.rdf.model.Model createJenaModel(OntologyId ontologyId) throws Exception {
        OWLOntology ontology = loadOntology(ontologyId);
        org.apache.jena.rdf.model.Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        org.semanticweb.owlapi.formats.RDFXMLDocumentFormat format = new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat();
        ontology.getOWLOntologyManager().saveOntology(ontology, format, baos);
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(baos.toByteArray());
        model.read(bais, null, "RDF/XML");

        return model;
    }

    private Map<String, Object> errorResponse(ServiceError error) {
        return Map.of("status", "error", "error", Map.of(
            "code", error.code().code(),
            "message", error.message(),
            "details", error.details()
        ));
    }
}
