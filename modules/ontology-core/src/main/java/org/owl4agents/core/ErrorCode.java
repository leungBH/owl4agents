package org.owl4agents.core;

/**
 * Structured error codes for owl4agents v0.1 and v0.2.
 * Each error code corresponds to a specific failure condition in the service layer.
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
        "Operation would modify ontology or workspace state, which is not allowed in v0.1 readonly mode."),
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
        "ROBOT preprocessing failed; the input ontologies caused an incompatible format error.");

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
}