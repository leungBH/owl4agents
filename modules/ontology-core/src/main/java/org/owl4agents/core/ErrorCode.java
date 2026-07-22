package org.owl4agents.core;

/**
 * Structured error codes for owl4agents v0.1 through v0.8.5.
 * Each error code corresponds to a specific failure condition in the service layer.
 *
 * <h3>v0.8.5 Exact Consistency Verification Error Codes</h3>
 * <p>These codes are returned by the 5-stage claim verification pipeline
 * ({@code ClaimVerificationService.verify()}) when errors occur during exact
 * consistency checking. All v0.8.5 error codes produce
 * {@code ExecutionStatus.ERROR} or {@code ExecutionStatus.TIMEOUT} with
 * {@code semanticVerdict = null}.
 *
 * <ul>
 *   <li>{@code SOURCE_ONTOLOGY_INCONSISTENT} — Stage 2: the source ontology
 *       is inconsistent. Claim verification cannot proceed. Distinct from
 *       {@code ONTOLOGY_INCONSISTENT} (reserved for direct
 *       {@code ReasonerService.checkConsistency} calls).</li>
 *   <li>{@code REASONER_TIMEOUT} — Stage 4: the exact consistency check
 *       ({@code O ∪ {α} is consistent?}) exceeded the configured timeout.
 *       Produces {@code ExecutionStatus.TIMEOUT} with {@code semanticVerdict = null}.</li>
 *   <li>{@code CLAIM_AXIOM_BUILD_FAILED} — Stage 3a: {@code ClaimAxiomBuilder}
 *       could not construct an OWL axiom from the structured claim (invalid IRI,
 *       entity kind mismatch, or missing required field).</li>
 *   <li>{@code CLAIM_CONSISTENCY_CHECK_FAILED} — Stage 4: the exact consistency
 *       check threw an unexpected exception (not timeout). Indicates a reasoner
 *       or ontology processing failure.</li>
 *   <li>{@code TEMPORARY_ONTOLOGY_CREATION_FAILED} — Stage 4:
 *       {@code TemporaryOntologyFactory} failed to create an isolated in-memory
 *       copy of the source ontology with the claim axiom added.</li>
 *   <li>{@code TRANSIENT_REASONER_INIT_FAILED} — Stage 4:
 *       {@code TransientReasonerSession.create()} failed to initialize a
 *       transient reasoner (e.g. reasoner not on classpath, profile mismatch).</li>
 * </ul>
 */
public enum ErrorCode {
    // v0.1 error codes
    ONTOLOGY_NOT_FOUND("ONTOLOGY_NOT_FOUND",
        "No ontology with the given ID found in the workspace catalog."),
    ENTITY_NOT_FOUND("ENTITY_NOT_FOUND",
        "No entity matching the given IRI or search criteria found in the ontology."),
    IMPORT_FAILED("IMPORT_FAILED",
        "OWL API could not parse or load the ontology file."),
    INVALID_SPARQL("INVALID_SPARQL",
        "SPARQL query has syntax or structural errors."),
    READONLY_VIOLATION("READONLY_VIOLATION",
        "Operation would modify ontology or workspace state, which is not allowed in readonly mode."),
    FILE_ACCESS_DENIED("FILE_ACCESS_DENIED",
        "File path is not cataloged or not an explicit import path."),
    QUERY_TIMEOUT("QUERY_TIMEOUT",
        "SPARQL query exceeded the configured timeout."),

    // v0.2 reasoning error codes
    REASONING_NOT_RUN("REASONING_NOT_RUN",
        "Reasoning has not been executed for this ontology. Run reasoning first before accessing inferred results."),
    REASONER_NOT_AVAILABLE("REASONER_NOT_AVAILABLE",
        "The requested reasoner implementation is not available on the classpath."),
    PROFILE_NOT_SUPPORTED("PROFILE_NOT_SUPPORTED",
        "The detected OWL profile has no compatible reasoner mapping."),
    EXPLANATION_NOT_SUPPORTED("EXPLANATION_NOT_SUPPORTED",
        "The active reasoner does not support explanation functionality."),
    ONTOLOGY_INCONSISTENT("ONTOLOGY_INCONSISTENT",
        "The ontology is inconsistent; reasoning detected logical contradictions."),
    ONTOLOGY_CONSISTENT("ONTOLOGY_CONSISTENT",
        "The ontology is consistent; no inconsistency explanation is needed."),
    CLASSIFICATION_FAILED("CLASSIFICATION_FAILED",
        "The reasoner could not complete classification."),
    EXPLANATION_FAILED("EXPLANATION_FAILED",
        "The reasoner could not produce an explanation for the requested inference."),
    REASONER_SHUTDOWN("REASONER_SHUTDOWN",
        "The reasoner has been shut down and can no longer perform operations."),
    CLASSIFICATION_CYCLE_DETECTED("CLASSIFICATION_CYCLE_DETECTED",
        "Classification detected potential cyclic class definitions in the ontology."),

    // v0.2 entity and axiom error codes
    CLASS_NOT_FOUND("CLASS_NOT_FOUND",
        "The specified class URI does not exist in the ontology."),
    PROPERTY_NOT_FOUND("PROPERTY_NOT_FOUND",
        "The specified property URI does not exist in the ontology."),
    DATATYPE_NOT_FOUND("DATATYPE_NOT_FOUND",
        "The specified datatype URI does not exist in the ontology."),
    PROPERTY_RANGE_NOT_FOUND("PROPERTY_RANGE_NOT_FOUND",
        "The specified property has no range declaration in the ontology."),
    INDIVIDUAL_NOT_FOUND("INDIVIDUAL_NOT_FOUND",
        "The specified individual URI does not exist in the ontology."),

    // v0.2 axiom and parameter error codes
    INVALID_AXIOM_PARAMETERS("INVALID_AXIOM_PARAMETERS",
        "Required axiom fields are missing or malformed."),
    INVALID_AXIOM_STRUCTURE("INVALID_AXIOM_STRUCTURE",
        "The submitted axiom has missing or malformed structural fields."),
    INVALID_SPARQL_SCOPE("INVALID_SPARQL_SCOPE",
        "The SPARQL scope value is not one of: explicit, inferred, union."),
    SCOPE_ANALYSIS_FAILED("SCOPE_ANALYSIS_FAILED",
        "Scope analysis cannot complete due to an unexpected ontology structure or parsing error."),

    // v0.2 semantic-deepening status codes (non-error informational results)
    IMPORTS_EMPTY("IMPORTS_EMPTY",
        "The loaded ontology has no import declarations."),
    CLASS_NO_RESTRICTIONS("CLASS_NO_RESTRICTIONS",
        "The specified class exists but has no restriction axioms."),
    PROPERTY_NO_EQUIVALENTS("PROPERTY_NO_EQUIVALENTS",
        "The specified property exists but has no equivalent property axioms."),
    PROPERTY_NO_DISJOINTS("PROPERTY_NO_DISJOINTS",
        "The specified property exists but has no disjoint property axioms."),
    DATATYPE_NO_CONSTRAINTS("DATATYPE_NO_CONSTRAINTS",
        "The specified datatype exists but has no defined facet constraints."),
    INDIVIDUAL_NO_OBJECT_ASSERTIONS("INDIVIDUAL_NO_OBJECT_ASSERTIONS",
        "The specified individual exists but has no object property assertions."),
    INDIVIDUAL_NO_DATA_ASSERTIONS("INDIVIDUAL_NO_DATA_ASSERTIONS",
        "The specified individual exists but has no data property assertions."),
    INDIVIDUAL_NO_SAME_AS("INDIVIDUAL_NO_SAME_AS",
        "The specified individual exists but has no sameAs declarations."),
    INDIVIDUAL_NO_DIFFERENT_FROM("INDIVIDUAL_NO_DIFFERENT_FROM",
        "The specified individual exists but has no differentFrom declarations and is not in any AllDifferent axiom."),
    NO_RELATIONS_FOUND("NO_RELATIONS_FOUND",
        "Both entities exist but no object property relation connects them."),

    // v0.2 ROBOT integration (optional)
    ROBOT_NOT_AVAILABLE("ROBOT_NOT_AVAILABLE",
        "The ROBOT module is not installed; robot: prefixed operations require the ontology-robot module."),
    ROBOT_PREPROCESSING_FAILED("ROBOT_PREPROCESSING_FAILED",
        "ROBOT preprocessing failed; the input ontologies caused an incompatible format error."),

    // v0.3 claim verification error codes
    INVALID_CLAIM_SCHEMA("INVALID_CLAIM_SCHEMA",
        "The submitted claim does not conform to the structured claim schema."),
    UNSUPPORTED_CLAIM_TYPE("UNSUPPORTED_CLAIM_TYPE",
        "The claim type is not one of the supported v0.3 verification types."),
    EVIDENCE_NOT_AVAILABLE("EVIDENCE_NOT_AVAILABLE",
        "No evidence could be assembled for the given claim verdict."),

    // v0.6 benchmark and QA evaluation error codes
    INVALID_EXPERIMENT_CONFIG("INVALID_EXPERIMENT_CONFIG",
        "The experiment configuration YAML is missing required fields or contains invalid values."),
    QUESTION_SET_NOT_FOUND("QUESTION_SET_NOT_FOUND",
        "The specified question set file path does not exist."),
    RESULTS_NOT_FOUND("RESULTS_NOT_FOUND",
        "No benchmark result JSONL file was found at the specified path."),
    EMPTY_RESULTS("EMPTY_RESULTS",
        "The benchmark result JSONL file contains no result lines."),
    INVALID_QUESTION_SET("INVALID_QUESTION_SET",
        "The question set line has missing required fields or invalid structure."),

    // v0.8.5 exact consistency verification error codes
    SOURCE_ONTOLOGY_INCONSISTENT("SOURCE_ONTOLOGY_INCONSISTENT",
        "The source ontology is inconsistent; claim verification cannot proceed."),
    REASONER_TIMEOUT("REASONER_TIMEOUT",
        "The reasoner exceeded the configured timeout during consistency check."),
    CLAIM_AXIOM_BUILD_FAILED("CLAIM_AXIOM_BUILD_FAILED",
        "Failed to construct an OWL axiom from the structured claim."),
    CLAIM_CONSISTENCY_CHECK_FAILED("CLAIM_CONSISTENCY_CHECK_FAILED",
        "The exact consistency check failed due to an unexpected error."),
    TEMPORARY_ONTOLOGY_CREATION_FAILED("TEMPORARY_ONTOLOGY_CREATION_FAILED",
        "Failed to create an isolated temporary ontology for the consistency check."),
    TRANSIENT_REASONER_INIT_FAILED("TRANSIENT_REASONER_INIT_FAILED",
        "Failed to initialize a transient reasoner session for the consistency check."),

    // v0.8.6 reasoner call wrapper error codes
    REASONER_BUSY("REASONER_BUSY",
        "The reasoner executor rejected the task; another reasoner call is in progress."),
    REASONER_REJECTED_ONTOLOGY("REASONER_REJECTED_ONTOLOGY",
        "The reasoner rejected the ontology (e.g. ELK rejecting non-EL axioms). Retry with a different reasoner."),
    REASONER_INTERNAL_ERROR("REASONER_INTERNAL_ERROR",
        "The reasoner threw an unexpected internal error."),
    REASONER_INTERRUPTED("REASONER_INTERRUPTED",
        "The reasoner call was interrupted before completion."),
    REASONER_EXPLANATION_UNSUPPORTED_FOR_LARGE_ONTOLOGY("REASONER_EXPLANATION_UNSUPPORTED_FOR_LARGE_ONTOLOGY",
        "Explanation requested on large ontology (classCount > 20K). Openllet would OOM. Disable explanation, use a smaller ontology, or explicitly specify reasoner=openllet (accepting OOM risk)."),

    // v0.8.7 mcp-write-tools error codes
    INVALID_IMPORT_ARGUMENTS("INVALID_IMPORT_ARGUMENTS",
        "ontology_import requires either content_base64 or file_path (at least one must be provided)."),
    IMPORT_SIZE_LIMIT_EXCEEDED("IMPORT_SIZE_LIMIT_EXCEEDED",
        "The ontology_import payload exceeds the configured maximum size."),
    IMPORT_PATH_OUTSIDE_ALLOWED_ROOTS("IMPORT_PATH_OUTSIDE_ALLOWED_ROOTS",
        "The ontology_import file_path resolves outside the configured allowed roots."),
    IMPORT_ID_CONFLICT("IMPORT_ID_CONFLICT",
        "The ontology_id already exists in the catalog; pass overwrite=true to replace."),
    TOOL_CONTRACT_NOT_FOUND("TOOL_CONTRACT_NOT_FOUND",
        "No tool contract registered for the requested tool name."),

    // v0.8.7 SHACL validation error codes
    SHACL_SHAPES_MALFORMED("SHACL_SHAPES_MALFORMED",
        "The SHACL shapes file could not be parsed by Jena RDFDataMgr.loadModel."),
    SHACL_SHAPES_LOAD_FAILED("SHACL_SHAPES_LOAD_FAILED",
        "A registered ShapeSet's source file could not be loaded (deleted, checksum mismatch, or I/O error)."),
    SHAPE_SET_NOT_FOUND("SHAPE_SET_NOT_FOUND",
        "The requested shape_set_id is not present in the ShapeRegistry."),
    SHAPE_SET_ID_CONFLICT("SHAPE_SET_ID_CONFLICT",
        "A ShapeSet with the same id already exists with a different checksum; pass --force to overwrite."),
    SHACL_TIMEOUT("SHACL_TIMEOUT",
        "The SHACL validation exceeded the configured timeout."),
    INVALID_ARGUMENTS("INVALID_ARGUMENTS",
        "One or more arguments supplied to the tool were invalid."),

    // v0.8.7 reasoner isolation error codes (D15 / REL-001)
    REASONER_WORKER_CRASHED("REASONER_WORKER_CRASHED",
        "The isolated reasoner worker JVM exited unexpectedly before responding."),
    REASONER_WORKER_PROTOCOL_ERROR("REASONER_WORKER_PROTOCOL_ERROR",
        "The isolated reasoner worker produced malformed output on stdout."),

    // v0.8.7 cache governance error code (D16 / REL-003)
    SNAPSHOT_EXPIRED("SNAPSHOT_EXPIRED",
        "The dynamic state snapshot exceeded its TTL window before pipeline entry.");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    /**
     * v0.8.6: Look up an ErrorCode by its string code (case-insensitive).
     * Used by {@code executeBenchmarkRun} to preserve the parser's error code
     * (e.g. QUESTION_SET_NOT_FOUND) instead of always wrapping as
     * INVALID_EXPERIMENT_CONFIG.
     *
     * @param code the string code to look up (case-insensitive, e.g. "QUESTION_SET_NOT_FOUND")
     * @return the matching ErrorCode, or empty Optional if no match
     */
    public static java.util.Optional<ErrorCode> fromCode(String code) {
        if (code == null) return java.util.Optional.empty();
        for (ErrorCode ec : values()) {
            if (ec.code.equalsIgnoreCase(code)) return java.util.Optional.of(ec);
        }
        return java.util.Optional.empty();
    }
}