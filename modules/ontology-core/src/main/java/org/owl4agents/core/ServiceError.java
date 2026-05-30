package org.owl4agents.core;

import java.util.Map;

/**
 * Structured error information returned by the service layer.
 * All errors follow a consistent format with code, message, and optional details.
 */
public record ServiceError(
    ErrorCode code,
    String message,
    Map<String, Object> details
) {

    public ServiceError {
        if (code == null) {
            throw new IllegalArgumentException("Error code must not be null");
        }
        if (message == null || message.isBlank()) {
            message = code.defaultMessage();
        }
        if (details == null) {
            details = Map.of();
        }
    }

    public static ServiceError of(ErrorCode code) {
        return new ServiceError(code, code.defaultMessage(), Map.of());
    }

    public static ServiceError of(ErrorCode code, String message) {
        return new ServiceError(code, message, Map.of());
    }

    public static ServiceError of(ErrorCode code, String message, Map<String, Object> details) {
        return new ServiceError(code, message, details);
    }

    public static ServiceError ontologyNotFound(OntologyId ontologyId) {
        return new ServiceError(ErrorCode.ONTOLOGY_NOT_FOUND,
            "No ontology with ID '" + ontologyId.id() + "' found in the workspace catalog.",
            Map.of("ontologyId", ontologyId.id()));
    }

    public static ServiceError entityNotFound(EntityId entityIri, OntologyId ontologyId) {
        return new ServiceError(ErrorCode.ENTITY_NOT_FOUND,
            "No entity with IRI '" + entityIri.iri() + "' found in ontology '" + ontologyId.id() + "'.",
            Map.of("entityIri", entityIri.iri(), "ontologyId", ontologyId.id()));
    }

    public static ServiceError importFailed(String filePath, String reason) {
        return new ServiceError(ErrorCode.IMPORT_FAILED,
            "Failed to import ontology from '" + filePath + "': " + reason,
            Map.of("filePath", filePath, "reason", reason));
    }

    public static ServiceError readonlyViolation(String operationType) {
        return new ServiceError(ErrorCode.READONLY_VIOLATION,
            "Operation '" + operationType + "' is not allowed in v0.1 readonly mode.",
            Map.of("operationType", operationType, "mode", "readonly"));
    }

    public static ServiceError fileAccessDenied(String requestedPath, String reason) {
        return new ServiceError(ErrorCode.FILE_ACCESS_DENIED,
            "File path '" + requestedPath + "' is not accessible: " + reason,
            Map.of("requestedPath", requestedPath, "reason", reason));
    }

    public static ServiceError queryTimeout(int timeoutMs, int executionTimeMs) {
        return new ServiceError(ErrorCode.QUERY_TIMEOUT,
            "Query exceeded the configured timeout of " + timeoutMs + "ms.",
            Map.of("timeoutMs", timeoutMs, "executionTimeMs", executionTimeMs));
    }

    public static ServiceError invalidSparql(String parseError) {
        return new ServiceError(ErrorCode.INVALID_SPARQL,
            "SPARQL query has syntax errors: " + parseError,
            Map.of("parseError", parseError));
    }
}