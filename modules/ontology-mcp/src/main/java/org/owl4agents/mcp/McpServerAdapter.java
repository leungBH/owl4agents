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
        return Map.of("status", "success", "data", Map.of("scopes", List.of("explicit")));
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
