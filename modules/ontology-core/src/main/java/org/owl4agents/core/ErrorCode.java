package org.owl4agents.core;

/**
 * Structured error codes for owl4agents v0.1.
 * Each error code corresponds to a specific failure condition in the service layer.
 */
public enum ErrorCode {
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
        "SPARQL query exceeded the configured timeout.");

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