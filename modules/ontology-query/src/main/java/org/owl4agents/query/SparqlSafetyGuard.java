package org.owl4agents.query;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;

/**
 * Blocks SPARQL update and graph-management operations in v0.1.
 * This is enforced at the service layer, not only in the SPARQL executor.
 */
public class SparqlSafetyGuard {

    private static final java.util.Set<String> UPDATE_KEYWORDS = java.util.Set.of(
        "INSERT", "DELETE", "LOAD", "CLEAR", "CREATE", "DROP", "MOVE"
    );

    /**
     * Check if a SPARQL query contains update operations.
     * If it does, return a READONLY_VIOLATION error.
     */
    public ServiceResult<Void> checkReadonly(String sparqlQuery) {
        if (sparqlQuery == null || sparqlQuery.isBlank()) {
            return ServiceResult.error(ServiceError.invalidSparql("Query is empty."));
        }

        String normalized = sparqlQuery.toUpperCase().trim();

        for (String keyword : UPDATE_KEYWORDS) {
            if (normalized.contains(keyword)) {
                // Check if it's inside a string literal (rough heuristic)
                // For v0.1, we block any occurrence of update keywords
                return ServiceResult.error(ServiceError.readonlyViolation(keyword));
            }
        }

        return ServiceResult.success(null,
            org.owl4agents.core.ResultMetadata.explicit(
                new org.owl4agents.core.OntologyId("safety-check")));
    }

    /**
     * Get the detected update operation type.
     */
    public String detectUpdateType(String sparqlQuery) {
        if (sparqlQuery == null) return "unknown";
        String normalized = sparqlQuery.toUpperCase().trim();

        for (String keyword : UPDATE_KEYWORDS) {
            if (normalized.contains(keyword)) {
                return keyword;
            }
        }
        return "unknown";
    }
}