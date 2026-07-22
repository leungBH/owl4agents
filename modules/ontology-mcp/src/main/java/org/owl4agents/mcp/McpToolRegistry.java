package org.owl4agents.mcp;

import java.util.*;

/**
 * Registry of v0.1, v0.2, v0.3, v0.5, and v0.6 readonly MCP tools,
 * plus v0.8.7 write tools (ontology_import) and v0.8.7 newly-added
 * readonly tools (SHACL + ToolCall, registered by their respective
 * modules once implemented).
 *
 * <p>v0.8.7 mcp-write-tools D3: the registry exposes both a readonly
 * tool set and a write tool set. {@link #listToolSchemas()} returns
 * only readonly schemas (for backward compatibility with v0.8.6
 * callers). {@link #listToolSchemas(boolean)} returns readonly
 * schemas plus write schemas when {@code readonly=false}.</p>
 */
public class McpToolRegistry {

    private static final List<String> READONLY_TOOLS = List.of(
        // v0.1 tools
        "ontology_list",
        "ontology_summary",
        "ontology_get_metadata",
        "ontology_get_profile",
        "ontology_list_graphs",
        "ontology_search_entities",
        "ontology_get_entity_context",
        "ontology_get_class_context",
        "ontology_get_object_property_context",
        "ontology_get_data_property_context",
        "ontology_get_individual_context",
        "ontology_get_graph_neighborhood",
        "ontology_validate_sparql",
        "ontology_sparql_select",
        "ontology_sparql_ask",
        "ontology_sparql_construct",
        "ontology_sparql_describe",
        "ontology_get_qa_context",
        // v0.2 reasoner tools
        "ontology_list_reasoners",
        "ontology_run_reasoner",
        "ontology_classify",
        "ontology_realize_instances",
        "ontology_check_consistency",
        "ontology_explain_inconsistency",
        "ontology_explain_unsat_class",
        "ontology_get_unsat_classes",
        "ontology_get_reasoning_report",
        "ontology_get_inferred_facts",
        "ontology_check_entailment",
        // v0.2 consistency-analysis tools
        "ontology_check_class_compatibility",
        "ontology_check_individual_membership",
        "ontology_check_relation_assertion",
        "ontology_get_scope",
        // v0.2 semantic-deepening tools
        "ontology_get_imports",
        "ontology_get_class_restrictions",
        "ontology_get_property_characteristics",
        "ontology_get_equivalent_properties",
        "ontology_get_disjoint_properties",
        "ontology_get_datatype_constraints",
        "ontology_validate_literal",
        "ontology_find_relations_between_entities",
        "ontology_get_object_property_assertions",
        "ontology_get_data_property_assertions",
        "ontology_get_same_individuals",
        "ontology_get_different_individuals",
        // v0.3 claim verification and evidence grounding tools
        "ontology_verify_claim",
        "ontology_get_evidence_path",
        "ontology_find_counterexamples",
        "ontology_explain_unknown",
        "ontology_detect_missing_entities",
        // v0.5 batch verification and evidence context tools
        "ontology_verify_claims_batch",
        "ontology_build_evidence_context",
        "ontology_review_answer_claims",
        // v0.6 benchmark tools
        "ontology_benchmark_run",
        // v0.6 QA evaluation tools
        "ontology_eval_qa",
        // v0.6 context-batch tools
        "ontology_context_batch",
        // v0.8.7 SHACL readonly tools (shacl-validation spec "Read-Only SHACL MCP Tools")
        "ontology_validate_shacl",
        "ontology_list_shape_sets",
        "ontology_get_shape_set",
        // v0.8.7 ToolCall readonly tools (toolcall-model spec "ToolContract Registry")
        "ontology_get_tool_contract",
        "ontology_list_tool_contracts",
        // v0.8.7 Pipeline readonly tools (toolcall-validation-pipeline spec "Pipeline MCP Tools")
        "ontology_validate_tool_call",
        "ontology_explain_tool_call",
        "ontology_preview_tool_call_effects"
    );

    /**
     * v0.8.7 mcp-write-tools: write tools registered by the mcp-write-tools
     * capability. Currently only ontology_import. In write mode
     * (--readonly=false) these are added to the readonly set.
     */
    private static final List<String> WRITE_TOOLS = List.of(
        "ontology_import"
    );

    /**
     * Check if a tool name is a readonly tool.
     */
    public boolean isReadonlyTool(String toolName) {
        return READONLY_TOOLS.contains(toolName);
    }

    /**
     * v0.8.7 mcp-write-tools: Check if a tool name is a write tool
     * registered by the mcp-write-tools capability.
     */
    public boolean isWriteTool(String toolName) {
        return WRITE_TOOLS.contains(toolName);
    }

    /**
     * List all readonly tool schemas (v0.8.6 behavior, backward compatible).
     */
    public List<Map<String, Object>> listToolSchemas() {
        return listToolSchemas(true);
    }

    /**
     * v0.8.7 mcp-write-tools: List tool schemas filtered by mode.
     *
     * @param readonly when true, return only readonly tool schemas;
     *                 when false, return readonly + write tool schemas
     */
    public List<Map<String, Object>> listToolSchemas(boolean readonly) {
        List<Map<String, Object>> schemas = new ArrayList<>(listReadonlySchemas());
        if (!readonly) {
            schemas.addAll(listWriteSchemas());
        }
        return schemas;
    }

    /**
     * Build the list of write tool schemas (currently ontology_import only).
     */
    private List<Map<String, Object>> listWriteSchemas() {
        List<Map<String, Object>> schemas = new ArrayList<>();
        // v0.8.7 ontology_import: write tool for importing OWL/RDF content
        // into the workspace catalog. Per mcp-write-tools spec, accepts
        // ontology_id (required), content_base64 (optional), file_path
        // (optional), overwrite (optional, default false).
        Map<String, Object> importSchema = new LinkedHashMap<>();
        importSchema.put("ontology_id", stringParam("Ontology ID to register in the catalog"));
        importSchema.put("content_base64", stringParam("Base64-encoded OWL/RDF payload (alternative to file_path)"));
        importSchema.put("file_path", stringParam("Server-local path to OWL/RDF file (alternative to content_base64)"));
        importSchema.put("overwrite", Map.of("type", "boolean", "description", "If true and ontology_id exists, replace the existing entry (default false)", "default", false));
        schemas.add(toolSchema("ontology_import", "Import an OWL/RDF ontology into the workspace catalog (write tool, requires --readonly=false)",
            importSchema));
        return schemas;
    }

    private List<Map<String, Object>> listReadonlySchemas() {
        List<Map<String, Object>> schemas = new ArrayList<>();

        // v0.1 Workspace and ontology tools
        schemas.add(toolSchema("ontology_list", "List imported ontologies in a workspace", Map.of()));
        schemas.add(toolSchema("ontology_summary", "Return ontology metadata, profile, imports, and entity counts",
            Map.of("ontology_id", stringParam("Ontology ID"))));
        schemas.add(toolSchema("ontology_get_metadata", "Return ontology IRI, version IRI, source path, canonical path, and timestamps",
            Map.of("ontology_id", stringParam("Ontology ID"))));
        schemas.add(toolSchema("ontology_get_profile", "Return OWL profile information and profile violations",
            Map.of("ontology_id", stringParam("Ontology ID"))));
        schemas.add(toolSchema("ontology_list_graphs", "List queryable graph scopes: explicit, inferred, union",
            Map.of("ontology_id", stringParam("Ontology ID"))));

        // v0.1 Entity tools
        schemas.add(toolSchema("ontology_search_entities", "Search classes, properties, and individuals",
            Map.of("ontology_id", stringParam("Ontology ID"), "query", stringParam("Search query text"))));
        schemas.add(toolSchema("ontology_get_entity_context", "Return labels, comments, class/property/individual context, and related facts",
            Map.of("ontology_id", stringParam("Ontology ID"), "entity_iri", stringParam("Entity IRI"))));
        schemas.add(toolSchema("ontology_get_class_context", "Return labels, comments, axioms, equivalent classes, super/sub classes, disjoints, and restrictions for a class",
            Map.of("ontology_id", stringParam("Ontology ID"), "entity_iri", stringParam("Class IRI"))));
        schemas.add(toolSchema("ontology_get_object_property_context", "Return labels, comments, domain, range, hierarchy, inverse properties, and characteristics",
            Map.of("ontology_id", stringParam("Ontology ID"), "entity_iri", stringParam("Object property IRI"))));
        schemas.add(toolSchema("ontology_get_data_property_context", "Return labels, comments, domain, range, datatype, and hierarchy",
            Map.of("ontology_id", stringParam("Ontology ID"), "entity_iri", stringParam("Data property IRI"))));
        schemas.add(toolSchema("ontology_get_individual_context", "Return labels, comments, explicit types, object property assertions, and data property assertions",
            Map.of("ontology_id", stringParam("Ontology ID"), "entity_iri", stringParam("Individual IRI"))));
        schemas.add(toolSchema("ontology_get_graph_neighborhood", "Return local graph neighborhood around an entity",
            Map.of("ontology_id", stringParam("Ontology ID"), "entity_iri", stringParam("Entity IRI"), "depth", intParam("Neighborhood depth", 1))));

        // v0.1 SPARQL tools
        schemas.add(toolSchema("ontology_validate_sparql", "Parse and validate a SPARQL query before execution",
            Map.of("query", stringParam("SPARQL query text"))));
        schemas.add(toolSchema("ontology_sparql_select", "Run a read-only SPARQL SELECT query",
            Map.of("ontology_id", stringParam("Ontology ID"), "query", stringParam("SPARQL SELECT query"), "graph_scope", stringParam("Graph scope: explicit, inferred, union"))));
        schemas.add(toolSchema("ontology_sparql_ask", "Run a SPARQL ASK query",
            Map.of("ontology_id", stringParam("Ontology ID"), "query", stringParam("SPARQL ASK query"))));
        schemas.add(toolSchema("ontology_sparql_construct", "Run a SPARQL CONSTRUCT query and return RDF triples",
            Map.of("ontology_id", stringParam("Ontology ID"), "query", stringParam("SPARQL CONSTRUCT query"))));
        schemas.add(toolSchema("ontology_sparql_describe", "Run a SPARQL DESCRIBE query for one or more resources",
            Map.of("ontology_id", stringParam("Ontology ID"), "query", stringParam("SPARQL DESCRIBE query"))));

        // v0.1 QA context tool
        schemas.add(toolSchema("ontology_get_qa_context", "Build ontology-grounded context for an agent question",
            Map.of("ontology_id", stringParam("Ontology ID"), "question", stringParam("Question text"), "max_entities", intParam("Max matched entities", 10), "max_depth", intParam("Max context depth", 3))));

        // v0.2 Reasoner tools
        schemas.add(toolSchema("ontology_list_reasoners", "List available reasoner adapters with their capabilities", Map.of()));
        schemas.add(toolSchema("ontology_run_reasoner", "Run reasoner on an ontology (classify, realize, check consistency)",
            Map.of("ontology_id", stringParam("Ontology ID"), "reasoner", stringParam("Reasoner: auto, hermit, elk, openllet"))));
        schemas.add(toolSchema("ontology_classify", "Classify ontology: compute inferred class hierarchy",
            Map.of("ontology_id", stringParam("Ontology ID"), "reasoner", stringParam("Reasoner name or auto"))));
        schemas.add(toolSchema("ontology_realize_instances", "Realize ontology: compute inferred individual types",
            Map.of("ontology_id", stringParam("Ontology ID"), "reasoner", stringParam("Reasoner name or auto"))));
        schemas.add(toolSchema("ontology_check_consistency", "Check ontology consistency",
            Map.of("ontology_id", stringParam("Ontology ID"), "reasoner", stringParam("Reasoner name or auto"))));
        schemas.add(toolSchema("ontology_explain_inconsistency", "Explain why an ontology is inconsistent",
            Map.of("ontology_id", stringParam("Ontology ID"), "reasoner", stringParam("Reasoner name, default openllet"))));
        schemas.add(toolSchema("ontology_explain_unsat_class", "Explain why a class is unsatisfiable",
            Map.of("ontology_id", stringParam("Ontology ID"), "class_uri", stringParam("Unsat class URI"), "reasoner", stringParam("Reasoner name, default openllet"))));
        schemas.add(toolSchema("ontology_get_unsat_classes", "Get all unsatisfiable class URIs",
            Map.of("ontology_id", stringParam("Ontology ID"))));
        schemas.add(toolSchema("ontology_get_reasoning_report", "Get the reasoning report for an ontology",
            Map.of("ontology_id", stringParam("Ontology ID"))));
        schemas.add(toolSchema("ontology_get_inferred_facts", "Get inferred facts for an entity or ontology scope",
            Map.of("ontology_id", stringParam("Ontology ID"), "entity_iri", stringParam("Entity IRI (optional)"))));
        schemas.add(toolSchema("ontology_check_entailment", "Check whether a structured axiom is entailed",
            Map.of("ontology_id", stringParam("Ontology ID"), "axiom_type", stringParam("Axiom type: SubClassOf, ClassAssertion, etc."), "reasoner", stringParam("Reasoner name or auto"))));

        // v0.2 Consistency-analysis tools
        schemas.add(toolSchema("ontology_check_class_compatibility", "Check whether two classes are compatible, disjoint, or unsatisfiable together",
            Map.of("ontology_id", stringParam("Ontology ID"), "class1_uri", stringParam("First class URI"), "class2_uri", stringParam("Second class URI"))));
        schemas.add(toolSchema("ontology_check_individual_membership", "Check whether an individual belongs to a class",
            Map.of("ontology_id", stringParam("Ontology ID"), "individual_uri", stringParam("Individual URI"), "class_uri", stringParam("Class URI"), "reasoner", stringParam("Reasoner name or auto"))));
        schemas.add(toolSchema("ontology_check_relation_assertion", "Check whether a relation between individuals is asserted or entailed",
            Map.of("ontology_id", stringParam("Ontology ID"), "source_individual_uri", stringParam("Source individual URI"), "property_uri", stringParam("Property URI"), "target_individual_uri", stringParam("Target individual URI"), "reasoner", stringParam("Reasoner name or auto"))));
        schemas.add(toolSchema("ontology_get_scope", "Get ontology domain coverage, known gaps, profile limitations, and unsupported feature types",
            Map.of("ontology_id", stringParam("Ontology ID"))));

        // v0.2 Semantic-deepening tools
        schemas.add(toolSchema("ontology_get_imports", "Get the full import closure hierarchy of an ontology",
            Map.of("ontology_id", stringParam("Ontology ID"))));
        schemas.add(toolSchema("ontology_get_class_restrictions", "Get class restrictions (someValuesFrom, allValuesFrom, cardinality, hasValue)",
            Map.of("ontology_id", stringParam("Ontology ID"), "class_uri", stringParam("Class URI"), "include_inferred", stringParam("Include inferred restrictions (default false)"))));
        schemas.add(toolSchema("ontology_get_property_characteristics", "Get property characteristic flags (functional, transitive, symmetric, etc.)",
            Map.of("ontology_id", stringParam("Ontology ID"), "property_uri", stringParam("Property URI"), "include_inferred", stringParam("Include inferred characteristics (default false)"))));
        schemas.add(toolSchema("ontology_get_equivalent_properties", "Get equivalent property axioms for a property",
            Map.of("ontology_id", stringParam("Ontology ID"), "property_uri", stringParam("Property URI"), "include_inferred", stringParam("Include inferred equivalents (default false)"))));
        schemas.add(toolSchema("ontology_get_disjoint_properties", "Get disjoint property axioms for a property",
            Map.of("ontology_id", stringParam("Ontology ID"), "property_uri", stringParam("Property URI"), "include_inferred", stringParam("Include inferred disjoints (default false)"))));
        schemas.add(toolSchema("ontology_get_datatype_constraints", "Get datatype facet constraints (min, max, pattern, enumeration)",
            Map.of("ontology_id", stringParam("Ontology ID"), "datatype_uri", stringParam("Datatype URI"))));
        schemas.add(toolSchema("ontology_validate_literal", "Validate a literal value against datatype constraints",
            Map.of("ontology_id", stringParam("Ontology ID"), "literal_value", stringParam("Literal value"), "datatype_uri", stringParam("Datatype URI"), "property_uri", stringParam("Property URI (optional)"))));
        schemas.add(toolSchema("ontology_find_relations_between_entities", "Find object property relations between two entities",
            Map.of("ontology_id", stringParam("Ontology ID"), "source_entity_uri", stringParam("Source entity URI"), "target_entity_uri", stringParam("Target entity URI"), "include_inferred", stringParam("Include inferred relations (default false)"))));
        schemas.add(toolSchema("ontology_get_object_property_assertions", "Get object property assertions for an individual",
            Map.of("ontology_id", stringParam("Ontology ID"), "individual_uri", stringParam("Individual URI"), "include_inferred", stringParam("Include inferred assertions (default false)"))));
        schemas.add(toolSchema("ontology_get_data_property_assertions", "Get data property assertions for an individual",
            Map.of("ontology_id", stringParam("Ontology ID"), "individual_uri", stringParam("Individual URI"), "include_inferred", stringParam("Include inferred assertions (default false)"))));
        schemas.add(toolSchema("ontology_get_same_individuals", "Get owl:sameAs individuals for an individual",
            Map.of("ontology_id", stringParam("Ontology ID"), "individual_uri", stringParam("Individual URI"), "include_inferred", stringParam("Include inferred sameAs (default false)"))));
        schemas.add(toolSchema("ontology_get_different_individuals", "Get owl:differentFrom individuals for an individual",
            Map.of("ontology_id", stringParam("Ontology ID"), "individual_uri", stringParam("Individual URI"), "include_inferred", stringParam("Include inferred differentFrom (default false)"))));

        // v0.3 Claim verification and evidence grounding tools
        schemas.add(toolSchema("ontology_verify_claim", "Verify a structured claim against an ontology and return verdict with evidence",
            Map.of("ontology_id", stringParam("Ontology ID"), "claim", objectParam("Structured claim object"), "reasoner", stringParam("Reasoner name or auto"))));
        schemas.add(toolSchema("ontology_get_evidence_path", "Get evidence path for a verified claim with inferred facts and reasoning report enrichment",
            Map.of("ontology_id", stringParam("Ontology ID"), "claim", objectParam("Structured claim object"))));
        schemas.add(toolSchema("ontology_find_counterexamples", "Find counterexamples for a contradicted claim",
            Map.of("ontology_id", stringParam("Ontology ID"), "claim", objectParam("Structured claim object"))));
        schemas.add(toolSchema("ontology_explain_unknown", "Explain why a claim received an unknown verdict with reason category and suggested action",
            Map.of("ontology_id", stringParam("Ontology ID"), "claim", objectParam("Structured claim object"))));
        Map<String, Object> missingEntitiesSchema = new HashMap<>();
        missingEntitiesSchema.put("ontology_id", stringParam("Ontology ID"));
        missingEntitiesSchema.put("claim", objectParam("Structured claim object"));
        missingEntitiesSchema.put("terms", objectParam("Alternative: list of entity IRIs as JSON array"));
        schemas.add(toolSchema("ontology_detect_missing_entities", "Detect matched, ambiguous, missing, and out-of-scope entities in a claim",
            missingEntitiesSchema));

        // v0.5 batch verification and evidence context tools
        Map<String, Object> claimsBatchSchema = new LinkedHashMap<>();
        claimsBatchSchema.put("ontology_id", stringParam("Ontology ID"));
        claimsBatchSchema.put("claims", objectParam("Claims batch JSON: answerId, claims array with id/type/required/subject/predicate/object"));
        claimsBatchSchema.put("options", objectParam("Optional workflow options: reasoner, requireReasoning, maxEvidencePerClaim, maxContextTokens"));
        schemas.add(toolSchema("ontology_verify_claims_batch", "Verify a batch of structured claims and return an answer verification report with aggregate status",
            claimsBatchSchema));

        Map<String, Object> evidenceContextSchema = new LinkedHashMap<>();
        evidenceContextSchema.put("report", stringParam("Answer verification report JSON (from verify-answer or review-answer). Either 'report' or 'ontology_id+claims' is required."));
        evidenceContextSchema.put("ontology_id", stringParam("Ontology ID (required when 'report' is not provided)"));
        evidenceContextSchema.put("claims", objectParam("Claims batch JSON: answerId, claims array (required when 'report' is not provided)"));
        evidenceContextSchema.put("max_context_tokens", intParam("Maximum context tokens budget (0 = no truncation)", 0));
        evidenceContextSchema.put("format", stringParam("Output format: compact (default) or jsonl (streamable JSONL with truncation metadata)"));
        schemas.add(toolSchema("ontology_build_evidence_context", "Build compact evidence context from a claims batch for LLM agent prompts",
            evidenceContextSchema));

        Map<String, Object> reviewClaimsSchema = new LinkedHashMap<>();
        reviewClaimsSchema.put("ontology_id", stringParam("Ontology ID"));
        reviewClaimsSchema.put("claims", objectParam("Claims batch JSON: answerId, claims array"));
        reviewClaimsSchema.put("max_context_tokens", intParam("Maximum context tokens budget (0 = no truncation)", 0));
        reviewClaimsSchema.put("policy", stringParam("Review policy: strict (default), conservative, or report-only"));
        schemas.add(toolSchema("ontology_review_answer_claims", "Review an answer by verifying claims and building evidence context with policy-dependent handling guidance",
            reviewClaimsSchema));

        // v0.6 benchmark tool
        Map<String, Object> benchmarkRunSchema = new LinkedHashMap<>();
        benchmarkRunSchema.put("config_yaml", stringParam("YAML config content (inline) or file path"));
        schemas.add(toolSchema("ontology_benchmark_run", "Run a benchmark experiment from a YAML config, producing JSONL results (readonly)",
            benchmarkRunSchema));

        // v0.6 QA evaluation tool
        Map<String, Object> evalQaSchema = new LinkedHashMap<>();
        evalQaSchema.put("results_path", stringParam("Benchmark result JSONL file path"));
        schemas.add(toolSchema("ontology_eval_qa", "Compute QA evaluation metrics from benchmark result JSONL (accuracy, false support rate, unresolved rate, coverage, 4x4 confusion matrix) (readonly)",
            evalQaSchema));

        // v0.6 context-batch tool
        Map<String, Object> contextBatchSchema = new LinkedHashMap<>();
        contextBatchSchema.put("question_set_path", stringParam("JSONL question set file path"));
        contextBatchSchema.put("ontology_id", stringParam("Ontology ID"));
        contextBatchSchema.put("max_context_tokens", intParam("Maximum context tokens budget (0 = no truncation)", 0));
        schemas.add(toolSchema("ontology_context_batch", "Build per-question evidence context from a JSONL question set with truncation metadata (readonly)",
            contextBatchSchema));

        // v0.8.7 SHACL readonly tools (shacl-validation spec "Read-Only SHACL MCP Tools")
        // ontology_validate_shacl: accepts ONLY shape_set_id, data_graph, options.
        // MUST NOT accept any shapes_graph / shapes / shapes_ttl parameter
        // (shacl-validation spec "Agent Cannot Upload Arbitrary SHACL-SPARQL").
        Map<String, Object> validateShaclSchema = new LinkedHashMap<>();
        validateShaclSchema.put("shape_set_id", stringParam("Registered ShapeSet identifier (inline shapes are NOT accepted)"));
        validateShaclSchema.put("data_graph", stringParam("Inline data graph (Turtle or JSON-LD) to validate"));
        validateShaclSchema.put("options", objectParam("Optional: {includeWarnings: bool, includeInfos: bool, timeout: number(seconds) | ISO-8601}"));
        schemas.add(toolSchema("ontology_validate_shacl",
            "Validate an inline data graph against a registered SHACL ShapeSet (readonly). " +
            "Inline shapes are rejected; only shape_set_id is accepted.",
            validateShaclSchema));

        schemas.add(toolSchema("ontology_list_shape_sets",
            "List all registered SHACL ShapeSets (id, version, domain, trusted). " +
            "Does NOT expose sourcePath or shapes Model content.",
            Map.of()));

        Map<String, Object> getShapeSetSchema = new LinkedHashMap<>();
        getShapeSetSchema.put("shape_set_id", stringParam("Registered ShapeSet identifier"));
        schemas.add(toolSchema("ontology_get_shape_set",
            "Return metadata for a single registered ShapeSet (id, version, domain, checksum, enabled, trusted, requiresInference). " +
            "Does NOT return the raw shapes Model content.",
            getShapeSetSchema));

        // v0.8.7 ToolCall readonly tools (toolcall-model spec "ToolContract Registry")
        Map<String, Object> getToolContractSchema = new LinkedHashMap<>();
        getToolContractSchema.put("toolName", stringParam("Tool name (matches the <toolName>.json file in ~/.owl4agents/contracts/)"));
        schemas.add(toolSchema("ontology_get_tool_contract",
            "Return the full ToolContract record (9 fields: toolName, inputSchema, targetClass, requiredCapabilities, " +
            "requiredStates, effects, riskLevel, requiredPermission, shapeSetIds) for a registered tool name. " +
            "Returns TOOL_CONTRACT_NOT_FOUND when no <toolName>.json exists. Hot-reloads from disk on file modification.",
            getToolContractSchema));

        schemas.add(toolSchema("ontology_list_tool_contracts",
            "List metadata summaries (toolName, riskLevel, hasShacl, targetsEntity, shapeSetIds) for every registered tool contract. " +
            "Excludes any contract whose file does not exist on disk.",
            Map.of()));

        // v0.8.7 Pipeline readonly tools (toolcall-validation-pipeline spec "Pipeline MCP Tools")
        Map<String, Object> validateToolCallSchema = new LinkedHashMap<>();
        validateToolCallSchema.put("ontology_id", stringParam("Base ontology ID to load for the overlay"));
        validateToolCallSchema.put("call", objectParam("ToolCallCandidate JSON (callId, toolName, arguments, targetEntity, ...)"));
        validateToolCallSchema.put("state", objectParam("Optional EnvironmentSnapshot JSON (devices, userContexts, pendingToolCalls)"));
        schemas.add(toolSchema("ontology_validate_tool_call",
            "Run the 10-stage validation pipeline (Parse → Load Contract → JSON Schema → Build Overlay → " +
            "Claim Decomposition → OWL Batch → SHACL → Risk Eval → Decision → Report) and return a " +
            "ToolCallValidationReport with decision (EXECUTE/AUTO_REPAIR/CLARIFY/REQUEST_CONFIRMATION/" +
            "REJECT/RETRY_VALIDATION/SYSTEM_ERROR), per-stage timing, and repair space. Readonly.",
            validateToolCallSchema));

        Map<String, Object> explainToolCallSchema = new LinkedHashMap<>();
        explainToolCallSchema.put("callId", stringParam("callId from a prior ontology_validate_tool_call response"));
        schemas.add(toolSchema("ontology_explain_tool_call",
            "Return the evidence triples, owlClaimResults, and shaclViolations for a prior validation " +
            "(looked up by callId from the in-memory cache). Readonly.",
            explainToolCallSchema));

        Map<String, Object> previewToolCallEffectsSchema = new LinkedHashMap<>();
        previewToolCallEffectsSchema.put("ontology_id", stringParam("Base ontology ID"));
        previewToolCallEffectsSchema.put("call", objectParam("ToolCallCandidate JSON"));
        previewToolCallEffectsSchema.put("state", objectParam("Optional EnvironmentSnapshot JSON"));
        schemas.add(toolSchema("ontology_preview_tool_call_effects",
            "Create a transient overlay, simulate the tool call's effects, return the simulated " +
            "post-state as Turtle, and release the overlay. The workspace ontology is never modified. " +
            "Readonly.",
            previewToolCallEffectsSchema));

        return schemas;
    }

    private Map<String, Object> toolSchema(String name, String description, Map<String, Object> inputSchema) {
        return Map.of(
            "name", name,
            "description", description,
            "inputSchema", Map.of("type", "object", "properties", inputSchema)
        );
    }

    private Map<String, Object> stringParam(String description) {
        return Map.of("type", "string", "description", description);
    }

    private Map<String, Object> intParam(String description, int defaultValue) {
        return Map.of("type", "integer", "description", description, "default", defaultValue);
    }

    private Map<String, Object> objectParam(String description) {
        return Map.of("type", "object", "description", description);
    }
}
