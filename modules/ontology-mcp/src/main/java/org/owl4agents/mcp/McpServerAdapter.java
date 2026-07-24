package org.owl4agents.mcp;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;
import org.owl4agents.core.model.AnswerVerificationReport.VerdictSummary;
import org.owl4agents.core.ClaimValidator;
import org.owl4agents.validation.ClaimBatchValidator;
import org.owl4agents.validation.ClaimWorkflowService;
import org.owl4agents.validation.EvidenceContextBuilder;
import org.owl4agents.benchmark.BenchmarkService;
import org.owl4agents.benchmark.BenchmarkQuestionSetValidator;
import org.owl4agents.benchmark.BenchmarkResultLine;
import org.owl4agents.benchmark.BenchmarkResultReader;
import org.owl4agents.benchmark.BenchmarkResultSummary;
import org.owl4agents.benchmark.ConfusionMatrix;
import org.owl4agents.benchmark.ExperimentConfig;
import org.owl4agents.benchmark.ExperimentConfigParser;
import org.owl4agents.benchmark.QaEvaluationService;
import org.owl4agents.benchmark.ContextBatchService;
import org.owl4agents.benchmark.EvidenceContextJsonlSerializer;
import org.owl4agents.owlapi.OntologySummaryExtractor;
import org.owl4agents.query.*;
import org.owl4agents.retrieval.*;
import org.owl4agents.storage.*;

import org.owl4agents.core.util.ClassExpressionAdapter;
import org.owl4agents.core.util.GsonFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP server adapter over the shared ontology services.
 * Exposes readonly tools from v0.1 import/query/context, v0.2 reasoning,
 * v0.3 claim verification / evidence grounding, and v0.5 batch workflow.
 *
 * <p>v0.8.7 mcp-write-tools: the adapter also exposes the {@code ontology_import}
 * write tool when constructed with {@code readonly=false}. Write tool calls
 * are routed through a dedicated {@link OntologyImportToolHandler}.</p>
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
 // v0.8.7 mcp-write-tools: readonly flag + write tool handler.
 private final boolean readonly;
 private final OntologyImportToolHandler importHandler;

 // v0.7.0: Services are eagerly initialized in the constructor in dependency
 // order (reasonerService -> ?consistencyAnalysisService -> ?semanticDeepeningService
 // -> ?claimVerificationService / evidenceGroundingService -> ?claimWorkflowService
 // -> ?evidenceContextBuilder). This removes the lazy-init race that previously
 // existed for concurrent HTTP requests and gives us a single deterministic
 // lifecycle for the 7 services shared by both transports.
 //
 // The eager init order is verified by McpServerAdapterTest
 // (TC-26 constructorInitializesAllServicesEagerlyInDependencyOrder).
 private final org.owl4agents.reasoner.ReasonerServiceImpl reasonerService;
 private final org.owl4agents.validation.ConsistencyAnalysisService consistencyAnalysisService;
 private final org.owl4agents.owlapi.SemanticDeepeningService semanticDeepeningService;
 private final org.owl4agents.validation.ClaimVerificationService claimVerificationService;
 private final org.owl4agents.validation.EvidenceGroundingService evidenceGroundingService;
 // v0.5 batch workflow services
 private final ClaimWorkflowService claimWorkflowService;
 private final EvidenceContextBuilder evidenceContextBuilder;
 // v0.8.4: entity signature cache for O(1) entity lookups
 private final org.owl4agents.owlapi.EntitySignatureCacheManager entitySignatureCacheManager;
 // v0.8.7 SHACL: lazy-initialized ShapeRegistry + ShaclValidationService
    private org.owl4agents.shacl.ShapeRegistry shapeRegistry;
    private org.owl4agents.shacl.ShaclValidationService shaclValidationService;
    // v0.8.7 ToolCall: lazy-initialized ToolContractRegistry
    private org.owl4agents.toolcall.ToolContractRegistry toolContractRegistry;
    // v0.8.7 Pipeline: shared OntologyCache (saved as a field so the lazy-init
    // overlayService() can reuse the same cache as the reasonerService).
    private org.owl4agents.owlapi.OntologyCache ontologyCache;
    // v0.8.7 Pipeline: lazy-initialized TransientOntologyOverlayService.
    private org.owl4agents.overlay.TransientOntologyOverlayService overlayService;
    // v0.8.7 Pipeline: lazy-initialized PipelineMcpTools (wraps the
    // ToolCallValidationPipeline). The pipeline delegates to the lazy-init
    // toolContractRegistry(), overlayService(), shaclService(), and the
    // eagerly-initialized claimWorkflowService field.
    private org.owl4agents.toolcall.pipeline.PipelineMcpTools pipelineMcpTools;

 /**
 * Accessors for the 7 eagerly-initialized services. These are package-private
 * (not private) to give McpServerAdapterTest direct read access for the
 * constructorInitializesAllServicesEagerlyInDependencyOrder assertion.
 *
 * <p>v0.7.0 removed the lazy-init getter pattern. All services are constructed
 * in the McpServerAdapter constructor in dependency order and exposed via
 * these read-only accessors.</p>
 */
 org.owl4agents.reasoner.ReasonerServiceImpl reasonerService() { return reasonerService; }
 org.owl4agents.validation.ConsistencyAnalysisService consistencyAnalysisService() { return consistencyAnalysisService; }
 org.owl4agents.owlapi.SemanticDeepeningService semanticDeepeningService() { return semanticDeepeningService; }
 org.owl4agents.validation.ClaimVerificationService claimVerificationService() { return claimVerificationService; }
 org.owl4agents.validation.EvidenceGroundingService evidenceGroundingService() { return evidenceGroundingService; }
 ClaimWorkflowService claimWorkflowService() { return claimWorkflowService; }
 EvidenceContextBuilder evidenceContextBuilder() { return evidenceContextBuilder; }

 /**
 * v0.8.6 baseline constructor: readonly mode with default 50 MB import limit.
 * Kept for backward compatibility with existing tests and callers that
 * do not opt into write mode. Equivalent to
 * {@code this(serviceContext, logFilePath, true, 50, null)}.
 */
 public McpServerAdapter(Map<String, Object> serviceContext, String logFilePath) {
     this(serviceContext, logFilePath, true, 50, null);
 }

 /**
 * v0.8.7 mcp-write-tools: Full constructor with readonly flag and
 * write-mode configuration.
 *
 * @param serviceContext       shared service context (may include "homeDir")
 * @param logFilePath          path to the JSONL tool call log
 * @param readonly             when true, only readonly tools are registered;
 *                             when false, write tools (ontology_import) are
 *                             also registered and routed through
 *                             {@link OntologyImportToolHandler}
 * @param maxImportSizeMb      per-import size cap in MB (default 50)
 * @param allowedImportRootsCsv comma-separated list of allowed root directories
 *                             for ontology_import file_path; null/blank
 *                             defaults to the workspace imports/ subdirectory
 */
 public McpServerAdapter(Map<String, Object> serviceContext, String logFilePath,
                         boolean readonly, int maxImportSizeMb,
                         String allowedImportRootsCsv) {
 this.serviceContext = serviceContext;
 this.toolRegistry = new McpToolRegistry();
 this.callLogger = new McpToolCallLogger(logFilePath);
 this.readonly = readonly;

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

 // v0.7.0: Eagerly initialize 7 services in dependency order so that
 // concurrent HTTP requests cannot race on lazy initialization.
 // The order matches spec.md §"Service initialization order".
 // v0.8.2: Inject shared OntologyCache into all three services for
 // cross-service ontology reuse (avoiding redundant 36-211s loads
 // of large ontologies like Mondo).
 // v0.8.4: Register EntitySignatureCacheManager as OntologyReloadListener
 // for O(1) entity signature lookups in claim verification hot path.
 // v0.8.7: Save ontologyCache as a field so the lazy-init overlayService()
 // (used by the Pipeline) can reuse the same cache as the reasonerService.
 String workspaceBasePath = homeResolver.resolveHomeDirectory()
 .resolve("workspaces").toString();
 String workspaceName = "default";
 this.ontologyCache =
 new org.owl4agents.owlapi.OntologyCache(workspaceBasePath, workspaceName);
 this.entitySignatureCacheManager = new org.owl4agents.owlapi.EntitySignatureCacheManager();
 this.ontologyCache.addReloadListener(this.entitySignatureCacheManager);
 this.reasonerService = new org.owl4agents.reasoner.ReasonerServiceImpl(
 catalogStore, workspaceBasePath, workspaceName, this.ontologyCache,
 this.entitySignatureCacheManager);
 this.consistencyAnalysisService = new org.owl4agents.validation.ConsistencyAnalysisService(
 reasonerService.getLifecycleManager(), workspaceBasePath, this.ontologyCache,
 this.entitySignatureCacheManager);
 this.semanticDeepeningService = new org.owl4agents.owlapi.SemanticDeepeningService(
 workspaceBasePath, this.ontologyCache);
 this.claimVerificationService = new org.owl4agents.validation.ClaimVerificationService(
 reasonerService, consistencyAnalysisService, semanticDeepeningService,
 catalogStore, new WorkspaceId("default"));
 this.evidenceGroundingService = new org.owl4agents.validation.EvidenceGroundingService(
 reasonerService, consistencyAnalysisService);
 this.claimWorkflowService = new ClaimWorkflowService(
 claimVerificationService, evidenceGroundingService,
 catalogStore, new WorkspaceId("default"), reasonerService);
 this.evidenceContextBuilder = new EvidenceContextBuilder();

 // v0.8.7 mcp-write-tools: instantiate the write tool handler in write mode.
 // The handler captures the OntologyCache reference so overwrite=true can
 // invalidate the cached entry before re-importing. In readonly mode the
 // handler is null and write tool calls fall through to the readonly
 // rejection path in handleToolCall.
 if (!readonly) {
     org.owl4agents.owlapi.OntologyImporter ontologyImporter =
         new org.owl4agents.owlapi.OntologyImporter(homeResolver, catalogStore);
     this.importHandler = new OntologyImportToolHandler(
         homeResolver, catalogStore, ontologyImporter, ontologyCache,
         new WorkspaceId("default"), maxImportSizeMb, allowedImportRootsCsv);
 } else {
     this.importHandler = null;
 }
 }

 /**
 * v0.8.7 mcp-write-tools: Expose the readonly flag for tests and callers.
 */
 public boolean isReadonly() {
     return readonly;
 }

 /**
 * Handle an MCP tool call.
 * Routes through the shared service layer and logs every call.
 *
 * <p>v0.8.7 mcp-write-tools: write tools (currently {@code ontology_import})
 * are routed through a dedicated {@link OntologyImportToolHandler} when the
 * server is in write mode. In readonly mode, write tool calls fall through
 * to the {@link ServiceError#readonlyViolation} rejection path so the
 * behavior is identical to v0.8.6 for unsanctioned writes.</p>
 */
 public Map<String, Object> handleToolCall(String toolName, Map<String, Object> arguments) {
 Instant timestamp = Instant.now();
 String ontologyId = (String) arguments.getOrDefault("ontology_id", null);

 // v0.8.7 mcp-write-tools: route write tools through the dedicated handler
 // when in write mode. In readonly mode, write tools fall through to the
 // readonly rejection below — preserving v0.8.6 behavior for any
 // unsanctioned write attempt.
 if (toolRegistry.isWriteTool(toolName)) {
     if (!readonly && importHandler != null) {
         Map<String, Object> writeResult = importHandler.execute(arguments);
         String writeStatus = writeResult.containsKey("error") ? "error" : "success";
         String writeErrorCode = writeResult.containsKey("error")
             ? ((Map<String, Object>) writeResult.get("error")).get("code").toString()
             : null;
         callLogger.logCall(timestamp, toolName, ontologyId, writeStatus, writeErrorCode);
         return writeResult;
     }
     // readonly mode or handler missing: reject as READONLY_VIOLATION.
     ServiceError error = ServiceError.readonlyViolation(toolName);
     callLogger.logCall(timestamp, toolName, ontologyId, "rejected", error.code().code());
     return errorResponse(error);
 }

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

 // v0.3: Extract claimId and verdict for enhanced logging
 String claimId = null;
 String verdict = null;
 if (result.containsKey("data")) {
 Object dataObj = result.get("data");
 if (dataObj instanceof Map) {
 @SuppressWarnings("unchecked")
 Map<String, Object> dataMap = (Map<String, Object>) dataObj;
 claimId = (String) dataMap.getOrDefault("claimId", null);
 verdict = (String) dataMap.getOrDefault("verdict", null);
 }
 }

 callLogger.logCall(timestamp, toolName, ontologyId, claimId, verdict, resultStatus, errorCode);
 return result;
 }

 /**
 * List available MCP tools.
 *
 * <p>v0.8.7 mcp-write-tools: in readonly mode returns the readonly tool
 * set (v0.8.6 behavior); in write mode returns the readonly set plus
 * write tools (currently {@code ontology_import}).</p>
 */
 public List<Map<String, Object>> listTools() {
 return toolRegistry.listToolSchemas(readonly);
 }

 /**
 * Protocol version advertised by this server on the `initialize` handshake.
 * v0.7.0 upgrades the protocol version from `2024-11-05` (v0.6 baseline) to
 * `2025-06-18` to match the MCP Streamable HTTP transport spec that
 * Trae / Claude Desktop / Cursor SDKs current support. See proposal.md
 * "transport" capability and design.md D9.
 */
 public static final String PROTOCOL_VERSION = "2025-06-18";

 /**
 * Server version. v0.8.6 D9: sourced from a single place via
 * {@link #loadVersion()} -> ?reads the jar manifest
 * {@code Implementation-Version} attribute (production shadowJar),
 * falls back to the {@code owl4agents.version} system property (gradle
 * run, gradle test, IDE runs), then to the literal {@code "0.9.0-dev"}.
 * Single source of truth -> ?read by both stdio and HTTP transports
 * (including the `GET /mcp` SSE path).
 */
 public static final String SERVER_VERSION = loadVersion();

 /**
 * v0.8.6 D9: Load the server version from a single source of truth.
 *
 * <p>Lookup order:</p>
 * <ol>
 *   <li>JAR manifest {@code Implementation-Version} attribute (set by the
 *       {@code shadowJar} and {@code jar} tasks in
 *       {@code modules/ontology-cli/build.gradle.kts}). Works when running
 *       from the production shadowJar.</li>
 *   <li>{@code owl4agents.version} system property (set by the root
 *       {@code build.gradle.kts} {@code test} task). Works for
 *       {@code gradle run}, {@code gradle test}, and IDE runs where the
 *       manifest is not set.</li>
 *   <li>Literal {@code "0.9.0-dev"} fallback so the field is never null.</li>
 * </ol>
 */
 private static String loadVersion() {
 try {
 Package pkg = McpServerAdapter.class.getPackage();
 String implVersion = pkg != null ? pkg.getImplementationVersion() : null;
 // v0.8.6 D9: filter out "unspecified" (the default manifest value when
 // Implementation-Version is not explicitly set) so the fallback chain
 // can proceed to the system property / literal.
 if (implVersion != null && !implVersion.isBlank()
 && !"unspecified".equalsIgnoreCase(implVersion)) {
 return implVersion;
 }
 } catch (Exception ignored) {
 // Fall through to system property / literal fallback.
 }
 String sysProp = System.getProperty("owl4agents.version");
 if (sysProp != null && !sysProp.isBlank()) {
 return sysProp;
 }
 return "0.9.0-dev";
 }

 /**
 * Public JSON-RPC 2.0 entry point used by both the stdio transport
 * (`McpCommand.runStdio` loop) and the HTTP transport (`HttpMcpServer`).
 *
 * <p>Method routing is identical for both transports; transport-specific
 * code (e.g. HTTP status codes, SSE) lives in the caller, not here.</p>
 *
 * <p>Behavior parity with the stdio path is enforced by spec.md §"HTTP /
 * stdio behavior parity".</p>
 *
 * @param request parsed JSON-RPC 2.0 request (must have `method`)
 * @return JSON-RPC 2.0 response, or {@code null} for notifications
 * (e.g. `notifications/initialized`) -> ?the caller translates
 * `null` into "no response written" (stdio: skip stdout write;
 * HTTP: return 202 Accepted with empty body).
 */
 public JsonObject handleJsonRpc(JsonObject request) {
 if (request == null) {
 return buildParseErrorResponse(null, "request is null");
 }
 String method = request.has("method") ? request.get("method").getAsString() : "";
 JsonElement idElement = request.has("id") ? request.get("id") : null;

 JsonObject response = new JsonObject();
 response.addProperty("jsonrpc", "2.0");
 if (idElement != null && !idElement.isJsonNull()) {
 response.add("id", idElement);
 }

 try {
 switch (method) {
 case "initialize" -> buildInitializeResult(response);
 case "notifications/initialized" -> {
 // Notification -> ?no response object
 return null;
 }
 case "tools/list" -> buildToolsListResult(response);
 case "tools/call" -> buildToolsCallResult(response, request);
 default -> {
 JsonObject error = new JsonObject();
 error.addProperty("code", -32601);
 error.addProperty("message", "Method not found: " + method);
 response.add("error", error);
 }
 }
 } catch (Exception e) {
 // Surface adapter exception as JSON-RPC -32603 (Internal error).
 // Caller maps -32603 to transport-specific framing (stdio: write
 // to stdout; HTTP: 500 + same JSON body).
 JsonObject error = new JsonObject();
 error.addProperty("code", -32603);
 error.addProperty("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
 response.add("error", error);
 }

 return response;
 }

 private void buildInitializeResult(JsonObject response) {
 JsonObject result = new JsonObject();
 result.addProperty("protocolVersion", PROTOCOL_VERSION);
 JsonObject capabilities = new JsonObject();
 JsonObject tools = new JsonObject();
 capabilities.add("tools", tools);
 result.add("capabilities", capabilities);
 JsonObject serverInfo = new JsonObject();
 serverInfo.addProperty("name", "owl4agents");
 serverInfo.addProperty("version", SERVER_VERSION);
 result.add("serverInfo", serverInfo);
 response.add("result", result);
 }

 private void buildToolsListResult(JsonObject response) {
 JsonObject result = new JsonObject();
 List<Map<String, Object>> tools = listTools();
 result.add("tools", gson.toJsonTree(tools));
 response.add("result", result);
 }

 private void buildToolsCallResult(JsonObject response, JsonObject request) {
 JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
 String toolName = params.has("name") ? params.get("name").getAsString() : "";
 JsonObject arguments = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();

 Map<String, Object> argsMap = gson.fromJson(arguments, Map.class);
 Map<String, Object> result = handleToolCall(toolName, argsMap);

 JsonObject mcpResult = new JsonObject();
 if (result.containsKey("error")) {
 mcpResult.addProperty("isError", true);
 List<Map<String, Object>> content = new ArrayList<>();
 Map<String, Object> errorContent = new HashMap<>();
 errorContent.put("type", "text");
 errorContent.put("text", gson.toJson(result.get("error")));
 content.add(errorContent);
 mcpResult.add("content", gson.toJsonTree(content));
 } else {
 List<Map<String, Object>> content = new ArrayList<>();
 Map<String, Object> textContent = new HashMap<>();
 textContent.put("type", "text");
 textContent.put("text", gson.toJson(result.get("data")));
 content.add(textContent);
 mcpResult.add("content", gson.toJsonTree(content));
 }
 response.add("result", mcpResult);
 }

 private JsonObject buildParseErrorResponse(JsonElement idElement, String message) {
 JsonObject response = new JsonObject();
 response.addProperty("jsonrpc", "2.0");
 if (idElement != null && !idElement.isJsonNull()) {
 response.add("id", idElement);
 } else {
 response.add("id", null);
 }
 JsonObject error = new JsonObject();
 error.addProperty("code", -32700);
 error.addProperty("message", message);
 response.add("error", error);
 return response;
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
 // v0.3 claim verification and evidence grounding tools
 case "ontology_verify_claim" -> { return executeVerifyClaim(arguments); }
 case "ontology_get_evidence_path" -> { return executeGetEvidencePath(arguments); }
 case "ontology_find_counterexamples" -> { return executeFindCounterexamples(arguments); }
 case "ontology_explain_unknown" -> { return executeExplainUnknown(arguments); }
 case "ontology_detect_missing_entities" -> { return executeDetectMissingEntities(arguments); }
 // v0.5 batch verification and evidence context tools
 case "ontology_verify_claims_batch" -> { return executeVerifyClaimsBatch(arguments); }
 case "ontology_build_evidence_context" -> { return executeBuildEvidenceContext(arguments); }
 case "ontology_review_answer_claims" -> { return executeReviewAnswerClaims(arguments); }
 // v0.6 benchmark tools
 case "ontology_benchmark_run" -> { return executeBenchmarkRun(arguments); }
 // v0.6 QA evaluation tools
 case "ontology_eval_qa" -> { return executeEvalQa(arguments); }
 // v0.6 context-batch tools
 case "ontology_context_batch" -> { return executeContextBatch(arguments); }
 // v0.8.7 SHACL readonly tools
        case "ontology_validate_shacl" -> { return executeValidateShacl(arguments); }
        case "ontology_list_shape_sets" -> { return executeListShapeSets(arguments); }
        case "ontology_get_shape_set" -> { return executeGetShapeSet(arguments); }
        // v0.8.7 ToolCall readonly tools
        case "ontology_get_tool_contract" -> { return executeGetToolContract(arguments); }
        case "ontology_list_tool_contracts" -> { return executeListToolContracts(arguments); }
        // v0.8.7 Pipeline readonly tools (toolcall-validation-pipeline spec "Pipeline MCP Tools")
        case "ontology_validate_tool_call" -> { return executeValidateToolCall(arguments); }
        case "ontology_explain_tool_call" -> { return executeExplainToolCall(arguments); }
        case "ontology_preview_tool_call_effects" -> { return executePreviewToolCallEffects(arguments); }
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
 org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = this.reasonerService;
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
 org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = this.reasonerService;
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
 org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = this.reasonerService;
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
 org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = this.reasonerService;
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
 org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = this.reasonerService;
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
 org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = this.reasonerService;
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
 org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = this.reasonerService;
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
 org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = this.reasonerService;
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
 org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = this.reasonerService;
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
 org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = this.reasonerService;
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
 org.owl4agents.reasoner.ReasonerServiceImpl reasonerService = this.reasonerService;
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
 org.owl4agents.validation.ConsistencyAnalysisService analysisService = this.consistencyAnalysisService;
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
 org.owl4agents.validation.ConsistencyAnalysisService analysisService = this.consistencyAnalysisService;
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
 org.owl4agents.validation.ConsistencyAnalysisService analysisService = this.consistencyAnalysisService;
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
 org.owl4agents.validation.ConsistencyAnalysisService analysisService = this.consistencyAnalysisService;
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
 org.owl4agents.owlapi.SemanticDeepeningService service = this.semanticDeepeningService;
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
 boolean includeInferred = Boolean.parseBoolean(String.valueOf(args.getOrDefault("include_inferred", "false")));
 try {
 org.owl4agents.owlapi.SemanticDeepeningService service = this.semanticDeepeningService;
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
 boolean includeInferred = Boolean.parseBoolean(String.valueOf(args.getOrDefault("include_inferred", "false")));
 try {
 org.owl4agents.owlapi.SemanticDeepeningService service = this.semanticDeepeningService;
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
 boolean includeInferred = Boolean.parseBoolean(String.valueOf(args.getOrDefault("include_inferred", "false")));
 try {
 org.owl4agents.owlapi.SemanticDeepeningService service = this.semanticDeepeningService;
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
 boolean includeInferred = Boolean.parseBoolean(String.valueOf(args.getOrDefault("include_inferred", "false")));
 try {
 org.owl4agents.owlapi.SemanticDeepeningService service = this.semanticDeepeningService;
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
 org.owl4agents.owlapi.SemanticDeepeningService service = this.semanticDeepeningService;
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
 org.owl4agents.owlapi.SemanticDeepeningService service = this.semanticDeepeningService;
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
 boolean includeInferred = Boolean.parseBoolean(String.valueOf(args.getOrDefault("include_inferred", "false")));
 try {
 org.owl4agents.owlapi.SemanticDeepeningService service = this.semanticDeepeningService;
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
 boolean includeInferred = Boolean.parseBoolean(String.valueOf(args.getOrDefault("include_inferred", "false")));
 try {
 org.owl4agents.owlapi.SemanticDeepeningService service = this.semanticDeepeningService;
 ServiceResult<PropertyAxiomsResult> result = service.getObjectPropertyAssertions(new OntologyId(ontologyIdStr), individualIRI, includeInferred);
 if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<PropertyAxiomsResult>) result).error());
 return Map.of("status", "success", "data", Map.of("assertions", ((ServiceResult.Success<PropertyAxiomsResult>) result).data().relatedPropertyIRIs()));
 } catch (Exception e) { return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage())); }
 }

 private Map<String, Object> executeGetDataPropertyAssertions(Map<String, Object> args) {
 String ontologyIdStr = (String) args.get("ontology_id");
 String individualIRI = (String) args.get("individual_uri");
 if (ontologyIdStr == null || individualIRI == null) return errorResponse(ServiceError.of(ErrorCode.INDIVIDUAL_NOT_FOUND, "ontology_id and individual_uri are required"));
 boolean includeInferred = Boolean.parseBoolean(String.valueOf(args.getOrDefault("include_inferred", "false")));
 try {
 org.owl4agents.owlapi.SemanticDeepeningService service = this.semanticDeepeningService;
 ServiceResult<PropertyAxiomsResult> result = service.getDataPropertyAssertions(new OntologyId(ontologyIdStr), individualIRI, includeInferred);
 if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<PropertyAxiomsResult>) result).error());
 return Map.of("status", "success", "data", Map.of("assertions", ((ServiceResult.Success<PropertyAxiomsResult>) result).data().relatedPropertyIRIs()));
 } catch (Exception e) { return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage())); }
 }

 private Map<String, Object> executeGetSameIndividuals(Map<String, Object> args) {
 String ontologyIdStr = (String) args.get("ontology_id");
 String individualIRI = (String) args.get("individual_uri");
 if (ontologyIdStr == null || individualIRI == null) return errorResponse(ServiceError.of(ErrorCode.INDIVIDUAL_NOT_FOUND, "ontology_id and individual_uri are required"));
 boolean includeInferred = Boolean.parseBoolean(String.valueOf(args.getOrDefault("include_inferred", "false")));
 try {
 org.owl4agents.owlapi.SemanticDeepeningService service = this.semanticDeepeningService;
 ServiceResult<PropertyAxiomsResult> result = service.getSameIndividuals(new OntologyId(ontologyIdStr), individualIRI, includeInferred);
 if (!result.isSuccess()) return errorResponse(((ServiceResult.Error<PropertyAxiomsResult>) result).error());
 return Map.of("status", "success", "data", Map.of("sameAsIndividuals", ((ServiceResult.Success<PropertyAxiomsResult>) result).data().relatedPropertyIRIs()));
 } catch (Exception e) { return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage())); }
 }

 private Map<String, Object> executeGetDifferentIndividuals(Map<String, Object> args) {
 String ontologyIdStr = (String) args.get("ontology_id");
 String individualIRI = (String) args.get("individual_uri");
 if (ontologyIdStr == null || individualIRI == null) return errorResponse(ServiceError.of(ErrorCode.INDIVIDUAL_NOT_FOUND, "ontology_id and individual_uri are required"));
 boolean includeInferred = Boolean.parseBoolean(String.valueOf(args.getOrDefault("include_inferred", "false")));
 try {
 org.owl4agents.owlapi.SemanticDeepeningService service = this.semanticDeepeningService;
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

 // ── v0.3 claim verification and evidence grounding tools ──

 private Map<String, Object> executeVerifyClaim(Map<String, Object> args) {
 String ontologyIdStr = (String) args.get("ontology_id");
 Object claimObj = args.get("claim");
 // v0.8.6 D6: Resolve reasoner via shared helper so single-claim and batch
 // paths use identical resolution logic (top-level > options > "auto").
 String reasonerName = resolveReasonerFromArgs(args);
 if (ontologyIdStr == null || claimObj == null) {
 return errorResponse(ServiceError.of(ErrorCode.INVALID_CLAIM_SCHEMA, "ontology_id and claim are required"));
 }

 Claim claim;
 try {
 claim = parseClaimFromMcpArgs(claimObj, reasonerName);
 } catch (IllegalArgumentException e) {
 // v0.8.6 D7: Surface the clarifying claimId/id alias message.
 return errorResponse(ServiceError.invalidClaimSchema(e.getMessage()));
 }
 if (claim == null) {
 return errorResponse(ServiceError.invalidClaimSchema("Failed to parse claim from arguments."));
 }
 claim = withAuthoritativeOntologyId(claim, ontologyIdStr);

 // Validate
 ClaimValidator validator = new ClaimValidator();
 ServiceResult<Claim> validationResult = validator.validate(claim);
 if (!validationResult.isSuccess()) {
 return errorResponse(((ServiceResult.Error<Claim>) validationResult).error());
 }

 Claim validClaim = ((ServiceResult.Success<Claim>) validationResult).data();

 // Verify
 ServiceResult<ClaimVerificationResult> result = claimVerificationService().verify(validClaim);
 if (!result.isSuccess()) {
 return errorResponse(((ServiceResult.Error<ClaimVerificationResult>) result).error());
 }

 ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
 Map<String, Object> responseData = new HashMap<>();
 // v0.8.5: schema v2 fields
 responseData.put("schemaVersion", "claim-verification-result/2");
 responseData.put("executionStatus", data.executionStatus().jsonName());
 responseData.put("claimId", data.claimId());
 responseData.put("ontologyId", data.ontologyId());
 responseData.put("claimType", data.claimType().jsonName());
 // v0.8.5: semanticVerdict is null when executionStatus != completed
 responseData.put("semanticVerdict", data.verdict() != null ? data.verdict().jsonName() : null);
 if (data.errorCode().isPresent()) {
 responseData.put("errorCode", data.errorCode().get().code());
 }
 responseData.put("truncated", data.truncated());
 responseData.put("totalEvidenceAvailable", data.totalEvidenceAvailable());
 if (data.unknownReason().isPresent()) {
 responseData.put("unknownReason", data.unknownReason().get().jsonName());
 }
 if (data.unknownExplanation().isPresent()) {
 responseData.put("unknownExplanation", data.unknownExplanation().get());
 }
 if (data.reasonerName().isPresent()) {
 responseData.put("reasonerName", data.reasonerName().get());
 }
 // v0.8.5: per-stage timing metadata
 PerStageTiming timing = data.perStageTiming();
 if (timing != null) {
 Map<String, Object> timingMap = new LinkedHashMap<>();
 timingMap.put("axiomBuildMs", timing.axiomBuildMs());
 timingMap.put("sourceConsistencyMs", timing.sourceConsistencyMs());
 timingMap.put("entailmentMs", timing.entailmentMs());
 timingMap.put("temporaryCopyMs", timing.temporaryCopyMs());
 timingMap.put("reasonerInitMs", timing.reasonerInitMs());
 timingMap.put("consistencyCheckMs", timing.consistencyCheckMs());
 timingMap.put("explanationMs", timing.explanationMs());
 timingMap.put("totalMs", timing.totalMs());
 responseData.put("perStageTiming", timingMap);
 }
 List<Map<String, Object>> evidenceItems = data.evidence().stream()
 .map(e -> {
 Map<String, Object> m = new HashMap<>();
 m.put("evidenceId", e.evidenceId());
 m.put("role", e.role());
 m.put("kind", e.kind().jsonName());
 m.put("value", e.value());
 m.put("source", e.source());
 m.put("confidence", e.confidence());
 return m;
 })
 .collect(Collectors.toList());
 responseData.put("evidence", evidenceItems);

 return Map.of("status", "success", "data", responseData);
 }

 private Map<String, Object> executeGetEvidencePath(Map<String, Object> args) {
 String ontologyIdStr = (String) args.get("ontology_id");
 Object claimObj = args.get("claim");
 if (ontologyIdStr == null || claimObj == null) {
 return errorResponse(ServiceError.of(ErrorCode.INVALID_CLAIM_SCHEMA, "ontology_id and claim are required"));
 }

 Claim claim;
 try {
 claim = parseClaimFromMcpArgs(claimObj, null);
 } catch (IllegalArgumentException e) {
 return errorResponse(ServiceError.invalidClaimSchema(e.getMessage()));
 }
 if (claim == null) {
 return errorResponse(ServiceError.invalidClaimSchema("Failed to parse claim from arguments."));
 }
 claim = withAuthoritativeOntologyId(claim, ontologyIdStr);

 ClaimValidator validator = new ClaimValidator();
 ServiceResult<Claim> validationResult = validator.validate(claim);
 if (!validationResult.isSuccess()) {
 return errorResponse(((ServiceResult.Error<Claim>) validationResult).error());
 }
 Claim validClaim = ((ServiceResult.Success<Claim>) validationResult).data();

 ServiceResult<ClaimVerificationResult> verifyResult = claimVerificationService().verify(validClaim);
 if (!verifyResult.isSuccess()) {
 return errorResponse(((ServiceResult.Error<ClaimVerificationResult>) verifyResult).error());
 }
 ClaimVerificationResult verification = ((ServiceResult.Success<ClaimVerificationResult>) verifyResult).data();

 ServiceResult<EvidencePath> result = evidenceGroundingService().getEvidencePath(validClaim, verification);
 if (!result.isSuccess()) {
 return errorResponse(((ServiceResult.Error<EvidencePath>) result).error());
 }

 EvidencePath path = ((ServiceResult.Success<EvidencePath>) result).data();
 Map<String, Object> responseData = new HashMap<>();
 responseData.put("claimId", path.claimId());
 responseData.put("ontologyId", path.ontologyId());
 responseData.put("truncated", path.truncated());
 responseData.put("totalAvailable", path.totalAvailable());
 List<Map<String, Object>> pathItems = path.items().stream()
 .map(e -> {
 Map<String, Object> m = new HashMap<>();
 m.put("evidenceId", e.evidenceId());
 m.put("role", e.role());
 m.put("kind", e.kind().jsonName());
 m.put("value", e.value());
 m.put("source", e.source());
 m.put("confidence", e.confidence());
 return m;
 })
 .collect(Collectors.toList());
 responseData.put("items", pathItems);

 return Map.of("status", "success", "data", responseData);
 }

 private Map<String, Object> executeFindCounterexamples(Map<String, Object> args) {
 String ontologyIdStr = (String) args.get("ontology_id");
 Object claimObj = args.get("claim");
 if (ontologyIdStr == null || claimObj == null) {
 return errorResponse(ServiceError.of(ErrorCode.INVALID_CLAIM_SCHEMA, "ontology_id and claim are required"));
 }

 Claim claim;
 try {
 claim = parseClaimFromMcpArgs(claimObj, null);
 } catch (IllegalArgumentException e) {
 return errorResponse(ServiceError.invalidClaimSchema(e.getMessage()));
 }
 if (claim == null) {
 return errorResponse(ServiceError.invalidClaimSchema("Failed to parse claim from arguments."));
 }
 claim = withAuthoritativeOntologyId(claim, ontologyIdStr);

 ClaimValidator validator = new ClaimValidator();
 ServiceResult<Claim> validationResult = validator.validate(claim);
 if (!validationResult.isSuccess()) {
 return errorResponse(((ServiceResult.Error<Claim>) validationResult).error());
 }
 Claim validClaim = ((ServiceResult.Success<Claim>) validationResult).data();

 ServiceResult<ClaimVerificationResult> verifyResult = claimVerificationService().verify(validClaim);
 if (!verifyResult.isSuccess()) {
 return errorResponse(((ServiceResult.Error<ClaimVerificationResult>) verifyResult).error());
 }
 ClaimVerificationResult verification = ((ServiceResult.Success<ClaimVerificationResult>) verifyResult).data();

 ServiceResult<List<EvidenceItem>> result = evidenceGroundingService().findCounterexamples(validClaim, verification);
 if (!result.isSuccess()) {
 return errorResponse(((ServiceResult.Error<List<EvidenceItem>>) result).error());
 }

 List<EvidenceItem> counterexamples = ((ServiceResult.Success<List<EvidenceItem>>) result).data();
 List<Map<String, Object>> counterexampleMaps = counterexamples.stream()
 .map(e -> {
 Map<String, Object> m = new HashMap<>();
 m.put("evidenceId", e.evidenceId());
 m.put("role", e.role());
 m.put("kind", e.kind().jsonName());
 m.put("value", e.value());
 m.put("source", e.source());
 m.put("confidence", e.confidence());
 return m;
 })
 .collect(Collectors.toList());

 return Map.of("status", "success", "data", Map.of(
 "claimId", validClaim.claimId(),
 "verdict", verification.verdict().jsonName(),
 "counterexamples", counterexampleMaps
 ));
 }

 private Map<String, Object> executeExplainUnknown(Map<String, Object> args) {
 String ontologyIdStr = (String) args.get("ontology_id");
 Object claimObj = args.get("claim");
 if (ontologyIdStr == null || claimObj == null) {
 return errorResponse(ServiceError.of(ErrorCode.INVALID_CLAIM_SCHEMA, "ontology_id and claim are required"));
 }

 Claim claim;
 try {
 claim = parseClaimFromMcpArgs(claimObj, null);
 } catch (IllegalArgumentException e) {
 return errorResponse(ServiceError.invalidClaimSchema(e.getMessage()));
 }
 if (claim == null) {
 return errorResponse(ServiceError.invalidClaimSchema("Failed to parse claim from arguments."));
 }
 claim = withAuthoritativeOntologyId(claim, ontologyIdStr);

 ClaimValidator validator = new ClaimValidator();
 ServiceResult<Claim> validationResult = validator.validate(claim);
 if (!validationResult.isSuccess()) {
 return errorResponse(((ServiceResult.Error<Claim>) validationResult).error());
 }
 Claim validClaim = ((ServiceResult.Success<Claim>) validationResult).data();

 ServiceResult<ClaimVerificationResult> verifyResult = claimVerificationService().verify(validClaim);
 if (!verifyResult.isSuccess()) {
 return errorResponse(((ServiceResult.Error<ClaimVerificationResult>) verifyResult).error());
 }
 ClaimVerificationResult verification = ((ServiceResult.Success<ClaimVerificationResult>) verifyResult).data();

 ServiceResult<UnknownExplanation> result = evidenceGroundingService().explainUnknown(validClaim, verification);
 if (!result.isSuccess()) {
 return errorResponse(((ServiceResult.Error<UnknownExplanation>) result).error());
 }

 UnknownExplanation explanation = ((ServiceResult.Success<UnknownExplanation>) result).data();
 Map<String, Object> responseData = new HashMap<>();
 responseData.put("claimId", explanation.claimId());
 responseData.put("ontologyId", explanation.ontologyId());
 responseData.put("reason", explanation.reason().jsonName());
 responseData.put("relevantEntities", explanation.relevantEntities());
 if (explanation.explanation().isPresent()) {
 responseData.put("explanation", explanation.explanation().get());
 }
 if (explanation.suggestedAction().isPresent()) {
 responseData.put("suggestedAction", explanation.suggestedAction().get());
 }

 return Map.of("status", "success", "data", responseData);
 }

 private Map<String, Object> executeDetectMissingEntities(Map<String, Object> args) {
        String ontologyIdStr = (String) args.get("ontology_id");
        Object claimObj = args.get("claim");
        Object termsObj = args.get("terms");
        if (ontologyIdStr == null) {
            return errorResponse(ServiceError.of(ErrorCode.INVALID_CLAIM_SCHEMA, "ontology_id is required"));
        }

        Claim claim = null;
        if (claimObj != null) {
            try {
                claim = parseClaimFromMcpArgs(claimObj, null);
            } catch (IllegalArgumentException e) {
                return errorResponse(ServiceError.invalidClaimSchema(e.getMessage()));
            }
            if (claim != null) {
                // Top-level ontology_id is authoritative — override any
                // ontologyId embedded in the claim so the service never sees
                // a blank/foreign ontology id.
                claim = new Claim(claim.claimId(), claim.type(), ontologyIdStr,
                    claim.subject(), claim.predicate(), claim.object(),
                    claim.reasoner(), claim.graphScope(), claim.options());
            }
        } else if (termsObj != null) {
            // Build a minimal claim from terms list
            String[] iris = parseTermsListFromMcpArgs(termsObj);
            if (iris != null && iris.length > 0) {
                ClaimEntity subject = new ClaimEntity("class", iris[0]);
                ClaimEntity object = iris.length > 1 ? new ClaimEntity("class", iris[1]) : null;
                claim = new Claim("missing-entities-check", ClaimType.SUBCLASS, ontologyIdStr,
                    subject, null, object, Optional.empty(), Optional.empty(), Optional.empty());
            }
        }

 if (claim == null) {
 return errorResponse(ServiceError.invalidClaimSchema("Provide claim or terms with valid data."));
 }

 ServiceResult<MissingEntityResult> result = evidenceGroundingService().detectMissingEntities(claim);
 if (!result.isSuccess()) {
 return errorResponse(((ServiceResult.Error<MissingEntityResult>) result).error());
 }

 MissingEntityResult data = ((ServiceResult.Success<MissingEntityResult>) result).data();
 return Map.of("status", "success", "data", Map.of(
 "ontologyId", data.ontologyId(),
 "matched", serializeEntityMatches(data.matched()),
 "ambiguous", serializeEntityMatches(data.ambiguous()),
 "missing", serializeEntityMatches(data.missing()),
 "outOfScope", serializeEntityMatches(data.outOfScope())
 ));
 }

 private List<Map<String, Object>> serializeEntityMatches(List<MissingEntityResult.EntityMatch> matches) {
 return matches.stream()
 .map(m -> {
 Map<String, Object> map = new HashMap<>();
 map.put("searchTerm", m.searchTerm());
 map.put("matchedIRI", m.matchedIRI().orElse(null));
 map.put("kind", m.kind().orElse(null));
 map.put("label", m.label().orElse(null));
 return map;
 })
 .collect(Collectors.toList());
 }

 // ── v0.5 batch verification and evidence context tools ──

 private static final Gson gson = GsonFactory.createGson();

 private static final java.lang.reflect.Type MAP_TYPE = new TypeToken<Map<String, Object>>(){}.getType();

 private static final java.util.Set<String> VALID_POLICIES = java.util.Set.of("strict", "conservative", "report-only");

 private Map<String, Object> executeVerifyClaimsBatch(Map<String, Object> args) {
 String ontologyIdStr = (String) args.get("ontology_id");
 if (ontologyIdStr == null) {
 return errorResponse(ServiceError.of(ErrorCode.INVALID_CLAIM_SCHEMA, "ontology_id is required"));
 }

 // v0.8.6 D6: Resolve reasoner override via shared helper
 // (top-level reasoner > options.reasoner > "auto"). The override is
 // injected into every parsed claim inside parseClaimsBatchFromArgs
 // (single injection point - no post-parse loop).
 String reasonerOverride = resolveReasonerFromArgs(args);
 Map<String, Object> batchMap = parseClaimsBatchFromArgs(args, reasonerOverride);
 if (batchMap == null) {
 return Map.of("status", "error", "data", Map.of(
 "aggregateStatus", "invalid_input",
 "diagnostics", List.of(Map.of("field", "claims", "reason", "Failed to parse claims batch"))));
 }

 // Validate the batch
 ClaimBatchValidator validator = new ClaimBatchValidator();
 ClaimBatchValidator.BatchValidationResult validationResult = validator.validateMap(batchMap);

 if (!validationResult.isSuccess()) {
 ClaimBatchValidator.BatchValidationResult.Error errorResult =
 (ClaimBatchValidator.BatchValidationResult.Error) validationResult;
 List<Map<String, Object>> diagnostics = errorResult.diagnostics().stream()
 .map(d -> Map.<String, Object>of("field", d.field(), "reason", d.reason()))
 .collect(Collectors.toList());
 return Map.of("status", "error", "data", Map.of(
 "aggregateStatus", errorResult.aggregateStatus().jsonName(), "diagnostics", diagnostics));
 }

 ClaimBatchInput batch = ((ClaimBatchValidator.BatchValidationResult.Success) validationResult).batch();

 // Verify the batch
 ServiceResult<AnswerVerificationReport> verifyResult =
 claimWorkflowService().verifyBatch(batch, ontologyIdStr);

 if (!verifyResult.isSuccess()) {
 ServiceError error = ((ServiceResult.Error<AnswerVerificationReport>) verifyResult).error();
 return errorResponse(error);
 }

 AnswerVerificationReport report = ((ServiceResult.Success<AnswerVerificationReport>) verifyResult).data();
 return Map.of("status", "success", "data", serializeVerificationReport(report));
 }

 private Map<String, Object> executeBuildEvidenceContext(Map<String, Object> args) {
 int maxContextTokens = args.containsKey("max_context_tokens")
 ? ((Number) args.get("max_context_tokens")).intValue() : 0;

 if (maxContextTokens < 0) {
 return errorResponse(ServiceError.of(ErrorCode.INVALID_CLAIM_SCHEMA,
 "max_context_tokens must be >= 0, got: " + maxContextTokens));
 }

 String format = (String) args.getOrDefault("format", "compact");

 // Mode 1: Direct report input -> ?build evidence context from a pre-generated report
 String reportStr = (String) args.get("report");
 if (reportStr != null) {
 AnswerVerificationReport report;
 try {
 report = gson.fromJson(reportStr, AnswerVerificationReport.class);
 } catch (Exception e) {
 return errorResponse(ServiceError.of(ErrorCode.INVALID_CLAIM_SCHEMA,
 "Failed to parse report JSON: " + e.getMessage()));
 }
 if (report == null) {
 return errorResponse(ServiceError.of(ErrorCode.INVALID_CLAIM_SCHEMA,
 "Report JSON parsing produced null."));
 }
 EvidenceContext context = evidenceContextBuilder().buildContext(report, maxContextTokens);

 if ("jsonl".equalsIgnoreCase(format)) {
 return buildJsonlResponse(context, report, maxContextTokens);
 }
 return Map.of("status", "success", "data", Map.of(
 "aggregateStatus", report.aggregateStatus().jsonName(),
 "evidenceContext", serializeEvidenceContext(context)));
 }

 // Mode 2: ontology_id + claims -> ?verify batch first, then build evidence context
 String ontologyIdStr = (String) args.get("ontology_id");
 if (ontologyIdStr == null) {
 return errorResponse(ServiceError.of(ErrorCode.INVALID_CLAIM_SCHEMA,
 "Either 'report' or 'ontology_id' is required."));
 }

 // v0.8.6 D6: context_batch does not apply a reasoner override (callers
 // cannot pass one); pass "auto" to preserve each claim's existing reasoner.
 Map<String, Object> batchMap = parseClaimsBatchFromArgs(args, "auto");
 if (batchMap == null) {
 return Map.of("status", "error", "data", Map.of(
 "aggregateStatus", "invalid_input",
 "diagnostics", List.of(Map.of("field", "claims", "reason", "Failed to parse claims batch"))));
 }

 ClaimBatchValidator validator = new ClaimBatchValidator();
 ClaimBatchValidator.BatchValidationResult validationResult = validator.validateMap(batchMap);

 if (!validationResult.isSuccess()) {
 ClaimBatchValidator.BatchValidationResult.Error errorResult =
 (ClaimBatchValidator.BatchValidationResult.Error) validationResult;
 List<Map<String, Object>> diagnostics = errorResult.diagnostics().stream()
 .map(d -> Map.<String, Object>of("field", d.field(), "reason", d.reason()))
 .collect(Collectors.toList());
 return Map.of("status", "error", "data", Map.of(
 "aggregateStatus", errorResult.aggregateStatus().jsonName(), "diagnostics", diagnostics));
 }

 ClaimBatchInput batch = ((ClaimBatchValidator.BatchValidationResult.Success) validationResult).batch();

 ServiceResult<AnswerVerificationReport> verifyResult =
 claimWorkflowService().verifyBatch(batch, ontologyIdStr);

 if (!verifyResult.isSuccess()) {
 ServiceError error = ((ServiceResult.Error<AnswerVerificationReport>) verifyResult).error();
 return errorResponse(error);
 }

 AnswerVerificationReport report = ((ServiceResult.Success<AnswerVerificationReport>) verifyResult).data();
 EvidenceContext context = evidenceContextBuilder().buildContext(report, maxContextTokens);

 if ("jsonl".equalsIgnoreCase(format)) {
 return buildJsonlResponse(context, report, maxContextTokens);
 }
 return Map.of("status", "success", "data", Map.of(
 "aggregateStatus", report.aggregateStatus().jsonName(),
 "evidenceContext", serializeEvidenceContext(context)));
 }

 /**
 * Build JSONL format response for evidence context with truncation metadata.
 */
 private Map<String, Object> buildJsonlResponse(EvidenceContext context,
 AnswerVerificationReport report,
 int maxContextTokens) {
 int budgetCharsUsed = maxContextTokens > 0 ? 4 * maxContextTokens : 0;
 int totalAvailableChars = estimateTotalAvailableChars(report);

 EvidenceContextJsonlSerializer serializer = new EvidenceContextJsonlSerializer();
 String jsonlLine = serializer.serializeToJsonl(context, budgetCharsUsed, totalAvailableChars);

 Map<String, Object> response = new LinkedHashMap<>();
 response.put("status", "success");
 response.put("data", Map.of(
 "aggregateStatus", report.aggregateStatus().jsonName(),
 "jsonl", jsonlLine));
 return response;
 }

 private int estimateTotalAvailableChars(AnswerVerificationReport report) {
 int total = 0;
 for (var claimResult : report.claimResults()) {
 total += claimResult.claimId().length() + 30;
 for (var ev : claimResult.evidence()) {
 total += ev.summary().length() + ev.kind().length() + ev.source().length() + 40;
 }
 }
 return total;
 }

 private Map<String, Object> executeReviewAnswerClaims(Map<String, Object> args) {
 String ontologyIdStr = (String) args.get("ontology_id");
 if (ontologyIdStr == null) {
 return errorResponse(ServiceError.of(ErrorCode.INVALID_CLAIM_SCHEMA, "ontology_id is required"));
 }

 String policy = (String) args.getOrDefault("policy", "strict");
 if (!VALID_POLICIES.contains(policy)) {
 return errorResponse(ServiceError.of(ErrorCode.INVALID_CLAIM_SCHEMA,
 "Unsupported policy: '" + policy + "'. Supported policies: strict, conservative, report-only"));
 }

 int maxContextTokens = args.containsKey("max_context_tokens")
 ? ((Number) args.get("max_context_tokens")).intValue() : 0;

 if (maxContextTokens < 0) {
 return errorResponse(ServiceError.of(ErrorCode.INVALID_CLAIM_SCHEMA,
 "max_context_tokens must be >= 0, got: " + maxContextTokens));
 }

 // Parse claims batch from arguments
 // v0.8.6 D6: review_answer_claims does not apply a reasoner override
 // (callers cannot pass one); pass "auto" to preserve each claim's
 // existing per-claim reasoner field.
 Map<String, Object> batchMap = parseClaimsBatchFromArgs(args, "auto");
 if (batchMap == null) {
 return Map.of("status", "error", "data", Map.of(
 "aggregateStatus", "invalid_input",
 "diagnostics", List.of(Map.of("field", "claims", "reason", "Failed to parse claims batch"))));
 }

 // Validate the batch
 ClaimBatchValidator validator = new ClaimBatchValidator();
 ClaimBatchValidator.BatchValidationResult validationResult = validator.validateMap(batchMap);

 if (!validationResult.isSuccess()) {
 ClaimBatchValidator.BatchValidationResult.Error errorResult =
 (ClaimBatchValidator.BatchValidationResult.Error) validationResult;
 List<Map<String, Object>> diagnostics = errorResult.diagnostics().stream()
 .map(d -> Map.<String, Object>of("field", d.field(), "reason", d.reason()))
 .collect(Collectors.toList());
 return Map.of("status", "error", "data", Map.of(
 "aggregateStatus", errorResult.aggregateStatus().jsonName(), "diagnostics", diagnostics));
 }

 ClaimBatchInput batch = ((ClaimBatchValidator.BatchValidationResult.Success) validationResult).batch();

 // Verify the batch
 ServiceResult<AnswerVerificationReport> verifyResult =
 claimWorkflowService().verifyBatch(batch, ontologyIdStr);

 if (!verifyResult.isSuccess()) {
 ServiceError error = ((ServiceResult.Error<AnswerVerificationReport>) verifyResult).error();
 return errorResponse(error);
 }

 AnswerVerificationReport report = ((ServiceResult.Success<AnswerVerificationReport>) verifyResult).data();

 // Build evidence context
 EvidenceContext context = evidenceContextBuilder().buildContext(report, maxContextTokens);

 // Build policy-dependent handling guidance
 List<String> handlingGuidance = buildHandlingGuidance(report.aggregateStatus(), policy);

 Map<String, Object> result = new LinkedHashMap<>();
 result.put("status", "success");
 result.put("data", Map.of(
 "report", serializeVerificationReport(report),
 "evidenceContext", serializeEvidenceContext(context),
 "policy", policy,
 "handlingGuidance", handlingGuidance
 ));
 return result;
 }

 // ── v0.6 benchmark tool ──

 private Map<String, Object> executeBenchmarkRun(Map<String, Object> args) {
 String configYaml = (String) args.get("config_yaml");
 if (configYaml == null || configYaml.isBlank()) {
 return errorResponse(ServiceError.of(ErrorCode.INVALID_EXPERIMENT_CONFIG,
 "config_yaml is required"));
 }

 // Determine if config_yaml is a file path or inline YAML content.
 // The parser expects a file path, so inline YAML needs a temp file.
 String configPath;
 java.nio.file.Path tempFile = null;
 if (!configYaml.trim().startsWith("name:") && !configYaml.trim().startsWith("name :")) {
 // Treat as file path -> ?check it exists
 java.nio.file.Path filePath = java.nio.file.Path.of(configYaml);
 if (!java.nio.file.Files.exists(filePath)) {
 return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND,
 "Config file not found: " + configYaml));
 }
 configPath = configYaml;
 } else {
 // Inline YAML content -> ?write to temp file for the parser
 try {
 tempFile = java.nio.file.Files.createTempFile("owl4agents-bench-", ".yaml");
 java.nio.file.Files.writeString(tempFile, configYaml);
 configPath = tempFile.toString();
 } catch (java.io.IOException e) {
 return errorResponse(ServiceError.of(ErrorCode.INVALID_EXPERIMENT_CONFIG,
 "Cannot write temp config file: " + e.getMessage()));
 }
 }

 // v0.8.6 D8: If inline question_set_content is supplied, write it to a
 // temp JSONL file and pass the path as questionSetPathOverride to the
 // parser. This decouples the benchmark from server-local questionSetPath
 // (the YAML's questionSetPath can be a placeholder).
 String questionSetContent = (String) args.get("question_set_content");
 java.nio.file.Path questionSetTempFile = null;
 String questionSetPathOverride = null;
 if (questionSetContent != null && !questionSetContent.isBlank()) {
 try {
 questionSetTempFile = java.nio.file.Files.createTempFile("owl4agents-qs-", ".jsonl");
 java.nio.file.Files.writeString(questionSetTempFile, questionSetContent);
 questionSetTempFile.toFile().deleteOnExit();
 questionSetPathOverride = questionSetTempFile.toString();
 } catch (java.io.IOException e) {
 if (tempFile != null) {
 try { java.nio.file.Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
 }
 return errorResponse(ServiceError.of(ErrorCode.INVALID_EXPERIMENT_CONFIG,
 "Cannot write temp question_set_content file: " + e.getMessage()));
 }
 }

 // Parse config
 ExperimentConfigParser parser = new ExperimentConfigParser();
 ExperimentConfigParser.ParseResult parseResult = parser.parse(configPath, questionSetPathOverride);
 // Clean up temp config file if created (the question_set temp file is
 // retained until JVM exit via deleteOnExit so the benchmark service can
 // read it during run()).
 if (tempFile != null) {
 try { java.nio.file.Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
 }
 if (!parseResult.isSuccess()) {
 ExperimentConfigParser.ConfigError error = parseResult.error();
 // v0.8.6 D8: Preserve the parser's structured error code (e.g.
 // QUESTION_SET_NOT_FOUND) instead of always wrapping as
 // INVALID_EXPERIMENT_CONFIG. ErrorCode.fromCode matches the
 // parser's uppercase-under-score code format case-insensitively.
 ErrorCode mappedCode = ErrorCode.fromCode(error.code())
 .orElse(ErrorCode.INVALID_EXPERIMENT_CONFIG);
 return errorResponse(ServiceError.of(mappedCode, error.diagnostic()));
 }

 ExperimentConfig config = parseResult.config();

 // Run benchmark
 BenchmarkQuestionSetValidator validator = new BenchmarkQuestionSetValidator();
 BenchmarkService benchmarkService = new BenchmarkService(claimWorkflowService(), validator);
 BenchmarkService.BenchmarkRunResult runResult;
 try {
 runResult = benchmarkService.run(config);
 } finally {
 // v0.8.6 D8: Clean up the question_set temp file as soon as the
 // benchmark has finished reading it. deleteOnExit() is the safety
 // net for early-return paths; this is the primary cleanup.
 if (questionSetTempFile != null) {
 try { java.nio.file.Files.deleteIfExists(questionSetTempFile); } catch (Exception ignored) {}
 }
 }

 // Serialize result lines
 List<Map<String, Object>> lines = runResult.lines().stream()
 .map(line -> {
 Map<String, Object> m = new LinkedHashMap<>();
 m.put("questionId", line.questionId());
 m.put("ontologyId", line.ontologyId());
 m.put("reasoner", line.reasoner());
 m.put("claimsVerified", line.claimsVerified());
 m.put("expectedVerdict", line.expectedVerdict().jsonName());
 m.put("actualVerdict", line.actualVerdict().jsonName());
 m.put("verdictMatch", line.verdictMatch());
 m.put("elapsedMs", line.elapsedMs());
 m.put("reviewStatus", line.reviewStatus());
 line.error().ifPresent(e -> m.put("error", e));
 return m;
 }).collect(Collectors.toList());

 // Serialize summary
 BenchmarkResultSummary summary = runResult.summary();
 Map<String, Object> summaryMap = new LinkedHashMap<>();
 summaryMap.put("type", summary.type());
 summaryMap.put("totalQuestions", summary.totalQuestions());
 summaryMap.put("accuracy", summary.accuracy());
 summaryMap.put("falseSupportRate", summary.falseSupportRate());
 summaryMap.put("falseSupportedCount", summary.falseSupportedCount());
 summaryMap.put("unresolvedRate", summary.unresolvedRate());
 summaryMap.put("falseUnknownCount", summary.falseUnknownCount());
 summaryMap.put("verificationCoverage", summary.verificationCoverage());
 Map<String, Object> verdictCounts = new LinkedHashMap<>();
 for (Map.Entry<Verdict, Integer> entry : summary.perVerdictCounts().entrySet()) {
 verdictCounts.put(entry.getKey().jsonName(), entry.getValue());
 }
 summaryMap.put("perVerdictCounts", verdictCounts);
 summaryMap.put("perReasonerTiming", summary.perReasonerTiming());

 return Map.of("status", "success", "data", Map.of(
 "lines", lines, "summary", summaryMap));
 }

 // ── v0.6 QA evaluation tool ──

 private Map<String, Object> executeEvalQa(Map<String, Object> args) {
 String resultsPath = (String) args.get("results_path");
 if (resultsPath == null || resultsPath.isBlank()) {
 return errorResponse(ServiceError.of(ErrorCode.RESULTS_NOT_FOUND,
 "results_path is required"));
 }

 java.nio.file.Path path = java.nio.file.Path.of(resultsPath);
 if (!java.nio.file.Files.exists(path)) {
 return errorResponse(ServiceError.of(ErrorCode.RESULTS_NOT_FOUND,
 "Results file not found: " + resultsPath));
 }

 // Read results from JSONL
 BenchmarkResultReader reader = new BenchmarkResultReader();
 java.util.List<BenchmarkResultLine> results;
 try {
 results = reader.readResults(path);
 } catch (java.io.IOException e) {
 return errorResponse(ServiceError.of(ErrorCode.RESULTS_NOT_FOUND,
 "Cannot read results file: " + e.getMessage()));
 }

 if (results.isEmpty()) {
 return errorResponse(ServiceError.of(ErrorCode.EMPTY_RESULTS,
 "Results file contains no result lines"));
 }

 // Evaluate
 QaEvaluationService service = new QaEvaluationService();
 QaEvaluationService.QaEvaluation evaluation = service.evaluate(results);

 // Serialize evaluation results
 Map<String, Object> metrics = new LinkedHashMap<>();
 metrics.put("accuracy", evaluation.accuracy());
 metrics.put("falseSupportRate", evaluation.falseSupportRate());
 metrics.put("falseSupportedCount", evaluation.falseSupportedCount());
 metrics.put("unresolvedRate", evaluation.unresolvedRate());
 metrics.put("falseUnknownCount", evaluation.falseUnknownCount());
 metrics.put("verificationCoverage", evaluation.verificationCoverage());

 // 4x4 confusion matrix
 Map<String, Object> matrix = new LinkedHashMap<>();
 for (Map.Entry<Verdict, Map<Verdict, Integer>> rowEntry : evaluation.confusionMatrix().matrix().entrySet()) {
 Map<String, Integer> inner = new LinkedHashMap<>();
 for (Map.Entry<Verdict, Integer> colEntry : rowEntry.getValue().entrySet()) {
 inner.put(colEntry.getKey().jsonName(), colEntry.getValue());
 }
 matrix.put(rowEntry.getKey().jsonName(), inner);
 }

 return Map.of("status", "success", "data", Map.of(
 "metrics", metrics, "confusionMatrix", matrix));
 }

 // ── v0.6 context-batch tool ──

 private Map<String, Object> executeContextBatch(Map<String, Object> args) {
 String questionSetPath = (String) args.get("question_set_path");
 String ontologyId = (String) args.get("ontology_id");

 if (questionSetPath == null || questionSetPath.isBlank()) {
 return errorResponse(ServiceError.of(ErrorCode.QUESTION_SET_NOT_FOUND,
 "question_set_path is required"));
 }
 if (ontologyId == null || ontologyId.isBlank()) {
 return errorResponse(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND,
 "ontology_id is required"));
 }

 java.nio.file.Path path = java.nio.file.Path.of(questionSetPath);
 if (!java.nio.file.Files.exists(path)) {
 return errorResponse(ServiceError.of(ErrorCode.QUESTION_SET_NOT_FOUND,
 "Question set file not found: " + questionSetPath));
 }

 int maxContextTokens = args.containsKey("max_context_tokens")
 ? ((Number) args.get("max_context_tokens")).intValue() : 0;

 // Process batch
 BenchmarkQuestionSetValidator validator = new BenchmarkQuestionSetValidator();
 ContextBatchService batchService = new ContextBatchService(
 claimWorkflowService(), evidenceContextBuilder(), validator);
 ContextBatchService.ContextBatchResult result =
 batchService.processBatch(questionSetPath, ontologyId, maxContextTokens);

 // Serialize entries
 List<Map<String, Object>> entries = result.entries().stream()
 .map(entry -> {
 Map<String, Object> m = new LinkedHashMap<>();
 m.put("questionId", entry.questionId());
 m.put("ontologyId", entry.ontologyId());
 m.put("budgetCharsUsed", entry.budgetCharsUsed());
 m.put("totalAvailableEvidenceChars", entry.totalAvailableEvidenceChars());
 m.put("omittedEvidenceCount", entry.omittedEvidenceCount());
 m.put("omittedClaimCount", entry.omittedClaimCount());
 if (entry.evidenceContext() != null) {
 m.put("evidenceContext", serializeEvidenceContext(entry.evidenceContext()));
 }
 if (entry.error() != null) {
 m.put("error", entry.error());
 }
 return m;
 }).collect(Collectors.toList());

 List<String> errorMessages = result.errors();

 return Map.of("status", "success", "data", Map.of(
 "entries", entries, "errors", errorMessages));
 }

 /**
 * Parse claims batch from MCP arguments.
 * The claims can be a Map (from JSON-RPC) or a JSON string.
 *
 * <p>v0.8.6 D6: This is the single injection point for the reasoner
 * override resolved by {@link #resolveReasonerFromArgs(Map)}. When
 * {@code reasonerOverride} is non-null and not equal to {@code "auto"}
 * (case-insensitive), every parsed claim's {@code reasoner} field is set
 * to {@code reasonerOverride}, overriding any per-claim value. When
 * {@code reasonerOverride} is {@code null} or {@code "auto"}, each claim's
 * existing {@code reasoner} field is preserved as-is (priority order:
 * top-level {@code reasoner} > {@code options.reasoner} > per-claim
 * {@code reasoner} > {@code auto}).</p>
 *
 * @param args the MCP tool call arguments (must contain a {@code claims} key)
 * @param reasonerOverride the resolved reasoner name (e.g. {@code "ELK"},
 *                          {@code "HermiT"}) or {@code "auto"} to preserve
 *                          per-claim values
 * @return the parsed batch map, or {@code null} if parsing failed
 */
 private Map<String, Object> parseClaimsBatchFromArgs(Map<String, Object> args, String reasonerOverride) {
 Object claimsObj = args.get("claims");
 if (claimsObj == null) return null;

 try {
 Map<String, Object> claimsMap;
 if (claimsObj instanceof Map) {
 @SuppressWarnings("unchecked")
 Map<String, Object> map = (Map<String, Object>) claimsObj;
 claimsMap = map;
 } else if (claimsObj instanceof String) {
 claimsMap = gson.fromJson((String) claimsObj, MAP_TYPE);
 } else {
 return null;
 }

 // v0.8.6 D6: Single injection point for the reasoner override.
 // When the resolved reasoner is not "auto", set every claim's reasoner
 // field to the override value (overriding any per-claim reasoner).
 // When "auto", preserve each claim's existing reasoner field (if any).
 if (reasonerOverride != null && !"auto".equalsIgnoreCase(reasonerOverride)) {
 Object claimsListObj = claimsMap.get("claims");
 if (claimsListObj instanceof List) {
 @SuppressWarnings("unchecked")
 List<Object> claimsList = (List<Object>) claimsListObj;
 for (Object claimEntry : claimsList) {
 if (claimEntry instanceof Map) {
 @SuppressWarnings("unchecked")
 Map<String, Object> claimMap = (Map<String, Object>) claimEntry;
 claimMap.put("reasoner", reasonerOverride);
 }
 }
 }
 }
 return claimsMap;
 } catch (Exception e) {
 return null;
 }
 }

 /**
 * v0.8.6 D6: Resolve the reasoner name from MCP tool call arguments using
 * the shared priority order: top-level {@code reasoner} >
 * {@code options.reasoner} > {@code "auto"}. Both single-claim
 * ({@code executeVerifyClaim}) and batch ({@code executeVerifyClaimsBatch})
 * paths call this helper so the resolution logic cannot drift between them.
 *
 * <p>Null-safe: a missing or null top-level {@code reasoner} falls back to
 * {@code options.reasoner}; a missing or null {@code options.reasoner}
 * returns {@code "auto"}.</p>
 *
 * @param args the MCP tool call arguments
 * @return the resolved reasoner name (never {@code null})
 */
 private String resolveReasonerFromArgs(Map<String, Object> args) {
 Object topLevelRaw = args.get("reasoner");
 String topLevel = topLevelRaw == null ? "auto" : topLevelRaw.toString();
 if (!"auto".equalsIgnoreCase(topLevel)) {
 return topLevel;
 }
 Object optionsObj = args.get("options");
 if (optionsObj instanceof Map) {
 @SuppressWarnings("unchecked")
 Map<String, Object> options = (Map<String, Object>) optionsObj;
 Object optionsReasoner = options.get("reasoner");
 if (optionsReasoner != null) {
 String optionsStr = optionsReasoner.toString();
 if (!optionsStr.isBlank() && !"auto".equalsIgnoreCase(optionsStr)) {
 return optionsStr;
 }
 }
 }
 return "auto";
 }

 /**
 * Serialize an AnswerVerificationReport to a Map for MCP response.
 */
 private Map<String, Object> serializeVerificationReport(AnswerVerificationReport report) {
 Map<String, Object> m = new LinkedHashMap<>();
 // v0.8.5: schema v2
 m.put("schemaVersion", "claim-verification-result/2");
 m.put("answerId", report.answerId());
 m.put("aggregateStatus", report.aggregateStatus().jsonName());
 m.put("claimResults", report.claimResults().stream()
 .map(this::serializeClaimWorkflowResult)
 .collect(Collectors.toList()));
 if (report.summary().isPresent()) {
 VerdictSummary vs = report.summary().get();
 m.put("verdictSummary", Map.of(
 "supportedCount", vs.supportedCount(),
 "contradictedCount", vs.contradictedCount(),
 "unknownCount", vs.unknownCount(),
 "outOfScopeCount", vs.outOfScopeCount(),
 "requiredCount", vs.requiredCount(),
 "optionalCount", vs.optionalCount()));
 }
 return m;
 }

 private Map<String, Object> serializeClaimWorkflowResult(ClaimWorkflowResult result) {
 Map<String, Object> m = new LinkedHashMap<>();
 m.put("claimId", result.claimId());
 m.put("claimType", result.claimType().jsonName());
 m.put("required", result.required());
 // v0.8.5: null-safe verdict (ClaimWorkflowService wraps errored claims as
 // UNKNOWN with diagnostics, but defensive null check prevents NPE).
 m.put("verdict", result.verdict() != null ? result.verdict().jsonName() : null);
 if (result.unknownReason().isPresent()) {
 m.put("unknownReason", result.unknownReason().get());
 }
 if (result.evidence() != null && !result.evidence().isEmpty()) {
 m.put("evidence", result.evidence().stream()
 .map(this::serializeWorkflowEvidenceEntry)
 .collect(Collectors.toList()));
 }
 if (result.counterexamples().isPresent() && !result.counterexamples().get().isEmpty()) {
 m.put("counterexamples", result.counterexamples().get().stream()
 .map(this::serializeWorkflowEvidenceEntry)
 .collect(Collectors.toList()));
 }
 if (result.missingEntities().isPresent() && !result.missingEntities().get().isEmpty()) {
 m.put("missingEntities", result.missingEntities().get());
 }
 if (result.diagnostics().isPresent()) {
 m.put("diagnostics", result.diagnostics().get());
 }
 return m;
 }

 private Map<String, Object> serializeWorkflowEvidenceEntry(WorkflowEvidenceEntry entry) {
 Map<String, Object> m = new LinkedHashMap<>();
 m.put("kind", entry.kind());
 m.put("summary", entry.summary());
 m.put("source", entry.source());
 if (entry.reasoner() != null) {
 m.put("reasoner", entry.reasoner());
 }
 if (entry.provenance() != null) {
 m.put("provenance", entry.provenance());
 }
 return m;
 }

 /**
 * Serialize an EvidenceContext to a Map for MCP response.
 */
 private Map<String, Object> serializeEvidenceContext(EvidenceContext context) {
 Map<String, Object> m = new LinkedHashMap<>();
 m.put("answerId", context.answerId());
 m.put("aggregateStatus", context.status().jsonName());
 m.put("claims", context.claims().stream()
 .map(this::serializeClaimContextEntry)
 .collect(Collectors.toList()));
 m.put("omittedClaimCount", context.omittedClaimCount());
 m.put("agentInstructions", context.agentInstructions());
 return m;
 }

 private Map<String, Object> serializeClaimContextEntry(EvidenceContext.ClaimContextEntry entry) {
 Map<String, Object> m = new LinkedHashMap<>();
 m.put("id", entry.id());
 m.put("verdict", entry.verdict().jsonName());
 if (entry.claimText() != null) {
 m.put("claimText", entry.claimText());
 }
 if (entry.evidence() != null && !entry.evidence().isEmpty()) {
 m.put("evidence", entry.evidence().stream()
 .map(this::serializeWorkflowEvidenceEntry)
 .collect(Collectors.toList()));
 }
 m.put("omittedEvidenceCount", entry.omittedEvidenceCount());
 if (entry.unknownReason().isPresent()) {
 m.put("unknownReason", entry.unknownReason().get());
 }
 if (entry.scopeDiagnostic().isPresent()) {
 m.put("scopeDiagnostic", entry.scopeDiagnostic().get());
 }
 return m;
 }

 /**
 * Build policy-dependent handling guidance based on aggregate status.
 * Mirrors ReviewAnswerCommand.buildHandlingGuidance().
 */
 private List<String> buildHandlingGuidance(AggregateAnswerStatus status, String policy) {
 List<String> guidance = new ArrayList<>();

 if ("strict".equals(policy)) {
 guidance.add("Policy: strict -> ?do not present any claim as fact unless it is supported by ontology evidence.");
 if (status == AggregateAnswerStatus.CONTRADICTED) {
 guidance.add("The answer must be rejected -> ?at least one required claim is contradicted.");
 } else if (status == AggregateAnswerStatus.INSUFFICIENT_EVIDENCE) {
 guidance.add("The answer cannot be confirmed -> ?at least one required claim lacks evidence. State limitations clearly.");
 } else if (status == AggregateAnswerStatus.PARTIALLY_VERIFIED) {
 guidance.add("Only present supported claims as verified. Explicitly mark out-of-scope claims as unverified.");
 } else if (status == AggregateAnswerStatus.OUT_OF_SCOPE) {
 guidance.add("No claims can be verified -> ?all required claims reference entities outside the ontology.");
 }
 } else if ("conservative".equals(policy)) {
 guidance.add("Policy: conservative -> ?prefer caution. Only cite explicitly verified claims.");
 if (status == AggregateAnswerStatus.VERIFIED) {
 guidance.add("All required claims are supported, but verify each optional claim independently before citing.");
 } else if (status != AggregateAnswerStatus.INVALID_INPUT) {
 guidance.add("Not all claims are fully verified. Present only supported claims and clearly state limitations.");
 }
 } else if ("report-only".equals(policy)) {
 guidance.add("Policy: report-only -> ?provide the factual report without judgment. The agent decides how to use the evidence.");
 }

 return guidance;
 }

 /**
 * Parse a Claim object from MCP arguments.
 * The claim can be a Map (from JSON-RPC) or a JSON string.
 */
 private Claim parseClaimFromMcpArgs(Object claimObj, String reasonerOverride) {
 try {
 if (claimObj instanceof Map) {
 @SuppressWarnings("unchecked")
 Map<String, Object> claimMap = (Map<String, Object>) claimObj;
 // v0.8.6 D7: claimId / id alias. Question set files use "id",
 // MCP single-claim uses "claimId". Both are accepted as aliases;
 // claimId takes precedence when both are present.
 String claimId = (String) claimMap.getOrDefault("claimId", "");
 if (claimId == null || claimId.isBlank()) {
 claimId = (String) claimMap.getOrDefault("id", "");
 }
 if (claimId == null || claimId.isBlank()) {
 throw new IllegalArgumentException(
 "claimId (or id) is required and must not be blank. " +
 "Note: question set files use 'id', MCP single-claim uses 'claimId'. " +
 "Both fields are accepted as aliases.");
 }
 String typeStr = (String) claimMap.getOrDefault("type", "");
 String ontologyId = (String) claimMap.getOrDefault("ontologyId", "");
 String predicate = (String) claimMap.getOrDefault("predicate", null);

 ClaimEntity subject = parseEntityFromMap(claimMap.get("subject"));
 ClaimEntity object = parseEntityFromMap(claimMap.get("object"));

 ClaimType type = ClaimType.fromJsonName(typeStr);
 if (type == null) {
 StringBuilder supported = new StringBuilder();
 for (ClaimType t : ClaimType.values()) {
 if (supported.length() > 0) supported.append(", ");
 supported.append(t.jsonName());
 }
 throw new IllegalArgumentException(
 "Unsupported claim type: '" + typeStr + "'. Supported: " + supported);
 }

 Optional<String> reasoner = reasonerOverride != null && !"auto".equals(reasonerOverride)
 ? Optional.of(reasonerOverride)
 : Optional.ofNullable((String) claimMap.get("reasoner"));

 Optional<GraphScope> graphScope = Optional.empty();
 String scopeStr = (String) claimMap.getOrDefault("graphScope", null);
 if (scopeStr != null) {
 graphScope = Optional.of(GraphScope.valueOf(scopeStr.toUpperCase()));
 }

 Optional<Map<String, Object>> options = Optional.empty();
 Object optionsObj = claimMap.get("options");
 if (optionsObj instanceof Map) {
 @SuppressWarnings("unchecked")
 Map<String, Object> optionsMap = (Map<String, Object>) optionsObj;
 options = Optional.of(optionsMap);
 }

 return new Claim(claimId, type, ontologyId, subject, predicate, object,
 reasoner, graphScope, options);
 } else if (claimObj instanceof String) {
 Claim parsed = gson.fromJson((String) claimObj, Claim.class);
 if (parsed != null && reasonerOverride != null && !"auto".equals(reasonerOverride)) {
 return new Claim(parsed.claimId(), parsed.type(), parsed.ontologyId(),
 parsed.subject(), parsed.predicate(), parsed.object(),
 Optional.of(reasonerOverride), parsed.graphScope(), parsed.options());
 }
 return parsed;
 }
 return null;
 } catch (IllegalArgumentException e) {
 // v0.8.6 D7: Propagate clarifying error messages (e.g. blank
 // claimId/id, unsupported claim type) so callers can surface them
 // to the user instead of receiving a generic "parse failed" error.
 throw e;
 } catch (Exception e) {
 return null;
 }
 }

    /**
     * If the caller supplied a non-blank top-level ontology_id and the parsed
     * claim either has no ontologyId or has a different one, rebuild the claim
     * with the caller's value. This keeps the MCP-level ontology_id as the
     * single source of truth and prevents downstream services from receiving
     * a blank or foreign ontologyId (which would NPE in many services).
     */
    private Claim withAuthoritativeOntologyId(Claim claim, String ontologyIdStr) {
        if (claim == null || ontologyIdStr == null || ontologyIdStr.isBlank()) {
            return claim;
        }
        String claimOntology = claim.ontologyId();
        if (claimOntology == null || claimOntology.isBlank() || !claimOntology.equals(ontologyIdStr)) {
            return new Claim(claim.claimId(), claim.type(), ontologyIdStr,
                claim.subject(), claim.predicate(), claim.object(),
                claim.reasoner(), claim.graphScope(), claim.options());
        }
        return claim;
    }

 private ClaimEntity parseEntityFromMap(Object entityObj) {
 if (entityObj == null) return null;
 if (entityObj instanceof Map) {
 @SuppressWarnings("unchecked")
 Map<String, Object> entityMap = (Map<String, Object>) entityObj;
 String kind = (String) entityMap.getOrDefault("kind", "class");
 Object iriObj = entityMap.get("iri");
 String iri = iriObj == null ? null : iriObj.toString();
 Object expressionObj = entityMap.get("expression");
 if (expressionObj instanceof Map) {
 @SuppressWarnings("unchecked")
 Map<String, Object> expressionMap = (Map<String, Object>) expressionObj;
 try {
 ClassExpression expression = ClassExpressionAdapter.fromMap(expressionMap);
 return new ClaimEntity(kind, iri, expression);
 } catch (IllegalArgumentException e) {
 return null;
 }
 }
 if (kind != null && iri != null) {
 return new ClaimEntity(kind, iri);
 }
 }
 return null;
 }

 private String[] parseTermsListFromMcpArgs(Object termsObj) {
 try {
 if (termsObj instanceof java.util.List) {
 @SuppressWarnings("unchecked")
 java.util.List<Object> list = (java.util.List<Object>) termsObj;
 return list.stream().map(Object::toString).toArray(String[]::new);
 } else if (termsObj instanceof String) {
 return gson.fromJson((String) termsObj, String[].class);
 }
 return null;
 } catch (Exception e) {
 return null;
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

 // ── v0.8.7 SHACL readonly tool implementations ──

 /**
  * Lazy-initialize the ShapeRegistry + ShaclValidationService on first use.
  * Both are lazily initialized so the MCP server does not pay the
  * registry.json load cost on startup when no SHACL tool is invoked.
  */
 private synchronized org.owl4agents.shacl.ShaclValidationService shaclService() {
     if (shaclValidationService == null) {
         if (shapeRegistry == null) {
             shapeRegistry = new org.owl4agents.shacl.FileShapeRegistry();
         }
         shaclValidationService = new org.owl4agents.shacl.JenaShaclValidationService(shapeRegistry);
     }
     return shaclValidationService;
 }

 /**
  * Package-private accessor for tests to inject a custom service.
  */
 synchronized void setShaclServices(org.owl4agents.shacl.ShapeRegistry registry,
                                    org.owl4agents.shacl.ShaclValidationService service) {
     this.shapeRegistry = registry;
     this.shaclValidationService = service;
 }

 /**
  * ontology_validate_shacl: validate inline data_graph against a registered
  * ShapeSet. Per spec "Agent Cannot Upload Arbitrary SHACL-SPARQL", the
  * tool MUST NOT accept any shapes_graph parameter; only shape_set_id is
  * accepted.
  */
 private Map<String, Object> executeValidateShacl(Map<String, Object> args) {
     // Per spec: reject any attempt to upload inline shapes.
     // Recognized aliases: shapes_graph, shapes, shapes_ttl.
     if (args.containsKey("shapes_graph") || args.containsKey("shapes")
         || args.containsKey("shapes_ttl")) {
         ServiceError error = ServiceError.of(ErrorCode.INVALID_ARGUMENTS,
             "ontology_validate_shacl only accepts 'shape_set_id'; inline shapes are rejected. " +
             "Register shapes via the shacl-register CLI command.");
         return errorResponse(error);
     }
     String shapeSetId = (String) args.get("shape_set_id");
     if (shapeSetId == null || shapeSetId.isBlank()) {
         return errorResponse(ServiceError.of(ErrorCode.INVALID_ARGUMENTS,
             "shape_set_id is required"));
     }
     String dataGraphStr = (String) args.get("data_graph");
     if (dataGraphStr == null || dataGraphStr.isBlank()) {
         return errorResponse(ServiceError.of(ErrorCode.INVALID_ARGUMENTS,
             "data_graph is required (inline Turtle or JSON-LD)"));
     }

     // Parse the inline data graph with Jena RDFDataMgr.
     org.apache.jena.rdf.model.Model dataModel;
     try {
         dataModel = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
         org.apache.jena.riot.RDFDataMgr.read(dataModel,
             new java.io.ByteArrayInputStream(dataGraphStr.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
             org.apache.jena.riot.Lang.TTL);
     } catch (RuntimeException ttlEx) {
         // Fallback: try JSON-LD
         try {
             dataModel = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
             org.apache.jena.riot.RDFDataMgr.read(dataModel,
                 new java.io.ByteArrayInputStream(dataGraphStr.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                 org.apache.jena.riot.Lang.JSONLD);
         } catch (RuntimeException jsonLdEx) {
             return errorResponse(ServiceError.of(ErrorCode.SHACL_SHAPES_MALFORMED,
                 "Failed to parse data_graph (tried Turtle and JSON-LD): " + ttlEx.getMessage()));
         }
     }

     // Parse options (includeWarnings, includeInfos, timeout).
     Object optionsObj = args.get("options");
     org.owl4agents.shacl.ShaclValidationOptions opts = org.owl4agents.shacl.ShaclValidationOptions.defaults();
     if (optionsObj instanceof Map) {
         @SuppressWarnings("unchecked")
         Map<String, Object> optionsMap = (Map<String, Object>) optionsObj;
         opts = org.owl4agents.shacl.ShaclValidationOptions.fromMap(optionsMap);
     }

     ServiceResult<org.owl4agents.shacl.ShaclValidationReport> result =
         shaclService().validateRegisteredShapes(shapeSetId, dataModel, opts);
     if (!result.isSuccess()) {
         return errorResponse(((ServiceResult.Error<org.owl4agents.shacl.ShaclValidationReport>) result).error());
     }
     org.owl4agents.shacl.ShaclValidationReport report =
         ((ServiceResult.Success<org.owl4agents.shacl.ShaclValidationReport>) result).data();
     return Map.of("status", "success",
         "data", org.owl4agents.shacl.ShaclJsonSerializer.reportToMap(report));
 }

 /**
  * ontology_list_shape_sets: return registered ShapeSet metadata (no sourcePath,
  * no Model content).
  */
 private Map<String, Object> executeListShapeSets(Map<String, Object> args) {
     // Lazy-init the registry if not yet done.
     shaclService();
     java.util.List<org.owl4agents.shacl.ShapeSet> sets = shapeRegistry.list();
     java.util.List<Map<String, Object>> serialized = sets.stream()
         .map(org.owl4agents.shacl.ShaclJsonSerializer::shapeSetToMap)
         .collect(Collectors.toList());
     return Map.of("status", "success", "data", Map.of("shapeSets", serialized));
 }

 /**
  * ontology_get_shape_set: return a single ShapeSet's metadata (no Model content).
  */
 private Map<String, Object> executeGetShapeSet(Map<String, Object> args) {
     String shapeSetId = (String) args.get("shape_set_id");
     if (shapeSetId == null || shapeSetId.isBlank()) {
         return errorResponse(ServiceError.of(ErrorCode.INVALID_ARGUMENTS,
             "shape_set_id is required"));
     }
     shaclService();
     java.util.Optional<org.owl4agents.shacl.ShapeSet> opt = shapeRegistry.get(shapeSetId);
     if (opt.isEmpty()) {
         return errorResponse(ServiceError.of(ErrorCode.SHAPE_SET_NOT_FOUND,
             "No ShapeSet registered with id '" + shapeSetId + "'.",
             Map.of("shapeSetId", shapeSetId)));
     }
     return Map.of("status", "success",
            "data", org.owl4agents.shacl.ShaclJsonSerializer.shapeSetToMap(opt.get()));
    }

    // ── v0.8.7 ToolCall readonly tool implementations ──

    /**
     * Lazy-initialize the ToolContractRegistry on first use.
     * Mirrors the {@link #shaclService()} pattern: lazy-init so the MCP
     * server does not pay the contracts-directory load cost on startup
     * when no ToolCall tool is invoked. The registry itself is mtime-aware
     * so hot-reload happens transparently inside {@code get(toolName)}.
     */
    private synchronized org.owl4agents.toolcall.ToolContractRegistry toolContractRegistry() {
        if (toolContractRegistry == null) {
            toolContractRegistry = new org.owl4agents.toolcall.ToolContractRegistry();
        }
        return toolContractRegistry;
    }

    /**
     * Package-private accessor for tests to inject a custom registry
     * (e.g. one pointed at a temp directory with fixture contracts).
     */
    synchronized void setToolContractRegistry(
        org.owl4agents.toolcall.ToolContractRegistry registry) {
        this.toolContractRegistry = registry;
    }

    /**
     * ontology_get_tool_contract: return the full ToolContract record
     * (9 fields) for a registered tool name. Queries the
     * {@link ToolContractRegistry} which loads from
     * {@code ~/.owl4agents/contracts/<toolName>.json} with mtime-based
     * hot-reload. Returns {@code TOOL_CONTRACT_NOT_FOUND} when the file
     * does not exist, with a hint about the expected file path convention.
     */
    private Map<String, Object> executeGetToolContract(Map<String, Object> args) {
        String toolName = (String) args.get("toolName");
        if (toolName == null || toolName.isBlank()) {
            return errorResponse(ServiceError.of(ErrorCode.INVALID_ARGUMENTS,
                "toolName is required"));
        }
        ServiceResult<org.owl4agents.toolcall.ToolContract> r =
            toolContractRegistry().get(toolName);
        if (!r.isSuccess()) {
            return errorResponse(
                ((ServiceResult.Error<org.owl4agents.toolcall.ToolContract>) r).error());
        }
        org.owl4agents.toolcall.ToolContract contract =
            ((ServiceResult.Success<org.owl4agents.toolcall.ToolContract>) r).data();
        return Map.of("status", "success",
            "data", org.owl4agents.toolcall.ToolCallJsonSerializer.contractToMap(contract));
    }

    /**
     * ontology_list_tool_contracts: list metadata summaries for every
     * registered tool contract. Per spec "List registered contracts", each
     * entry carries at minimum {@code toolName} and {@code riskLevel};
     * the serializer also emits {@code hasShacl}, {@code targetsEntity},
     * and {@code shapeSetIds} for caller convenience. Removed files are
     * excluded even when a stale cache entry remains.
     */
    private Map<String, Object> executeListToolContracts(Map<String, Object> args) {
        java.util.List<org.owl4agents.toolcall.ToolContract> contracts =
            toolContractRegistry().list();
        java.util.List<Map<String, Object>> serialized = contracts.stream()
            .map(org.owl4agents.toolcall.ToolCallJsonSerializer::contractSummaryToMap)
            .collect(Collectors.toList());
        return Map.of("status", "success",
            "data", Map.of("contracts", serialized));
    }

    // ── v0.8.7 Pipeline readonly tool implementations ──

    /**
     * Lazy-initialize the {@link TransientOntologyOverlayService} on first
     * use. Reuses the shared {@link #ontologyCache} (saved as a field in
     * the constructor) so the overlay resolves base ontologies through
     * the same cache as the {@link #reasonerService}.
     */
    private synchronized org.owl4agents.overlay.TransientOntologyOverlayService overlayService() {
        if (overlayService == null) {
            if (ontologyCache == null) {
                // Defensive: the constructor always sets ontologyCache, but
                // tests using legacy constructors may bypass that path.
                // Reconstruct from the home resolver so the overlay still
                // works in test contexts.
                String workspaceBasePath = homeResolver.resolveHomeDirectory()
                    .resolve("workspaces").toString();
                ontologyCache = new org.owl4agents.owlapi.OntologyCache(
                    workspaceBasePath, "default");
            }
            overlayService = new org.owl4agents.overlay.TransientOntologyOverlayServiceImpl(
                ontologyCache);
        }
        return overlayService;
    }

    /**
     * Package-private accessor for tests to inject a custom overlay service.
     */
    synchronized void setOverlayService(
        org.owl4agents.overlay.TransientOntologyOverlayService service) {
        this.overlayService = service;
    }

    /**
     * Lazy-initialize the {@link PipelineMcpTools} on first use. The
     * underlying {@link ToolCallValidationPipeline} is constructed with
     * the lazy-init {@link #toolContractRegistry()}, the lazy-init
     * {@link #overlayService()}, the lazy-init {@link #shaclService()}
     * (which also lazy-inits {@link #shapeRegistry}), and the
     * eagerly-initialized {@link #claimWorkflowService} field.
     *
     * <p>Per spec "CLI and MCP Service Contract Sharing", the same
     * pipeline instance is shared by both the MCP tools and (when the
     * CLI is wired through {@code CliServiceFactory}) the CLI. The
     * pipeline is stateless and safe to call concurrently.</p>
     */
    private synchronized org.owl4agents.toolcall.pipeline.PipelineMcpTools pipelineMcpTools() {
        if (pipelineMcpTools == null) {
            // Ensure shapeRegistry + shaclValidationService are initialized
            // (the pipeline needs both for stage 7 SHACL validation).
            shaclService();
            org.owl4agents.toolcall.pipeline.ToolCallValidationPipeline pipeline =
                org.owl4agents.toolcall.pipeline.ToolCallValidationPipeline.builder()
                    .toolContractRegistry(toolContractRegistry())
                    .overlayService(overlayService())
                    .claimWorkflowService(claimWorkflowService)
                    .shapeRegistry(shapeRegistry)
                    .shaclValidationService(shaclValidationService)
                    .build();
            pipelineMcpTools = new org.owl4agents.toolcall.pipeline.PipelineMcpTools(
                pipeline, toolContractRegistry(), overlayService());
        }
        return pipelineMcpTools;
    }

    /**
     * Package-private accessor for tests to inject a custom
     * {@link PipelineMcpTools} (e.g. one wired with a temp-directory
     * ToolContractRegistry and an in-memory overlay service).
     */
    synchronized void setPipelineMcpTools(
        org.owl4agents.toolcall.pipeline.PipelineMcpTools tools) {
        this.pipelineMcpTools = tools;
    }

    /**
     * ontology_validate_tool_call: run the 10-stage validation pipeline
     * (Parse -> Load Contract -> JSON Schema -> Build Overlay -> Claim
     * Decomposition -> OWL Batch -> SHACL -> Risk Eval -> Decision ->
     * Report) and return a {@link ToolCallValidationReport}.
     */
    private Map<String, Object> executeValidateToolCall(Map<String, Object> args) {
        return pipelineMcpTools().validateToolCall(args);
    }

    /**
     * ontology_explain_tool_call: return the evidence, owlClaimResults,
     * and shaclViolations for a prior validation (looked up by callId
     * from the in-memory cache).
     */
    private Map<String, Object> executeExplainToolCall(Map<String, Object> args) {
        return pipelineMcpTools().explainToolCall(args);
    }

    /**
     * ontology_preview_tool_call_effects: create a transient overlay,
     * simulate the tool call's effects, return the simulated post-state
     * as Turtle, and release the overlay. The workspace ontology is
     * never modified.
     */
    private Map<String, Object> executePreviewToolCallEffects(Map<String, Object> args) {
        return pipelineMcpTools().previewToolCallEffects(args);
    }
}
