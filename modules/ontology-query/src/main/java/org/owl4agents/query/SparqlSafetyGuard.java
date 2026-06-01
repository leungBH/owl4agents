package org.owl4agents.query;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.GraphScope;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;

/**
 * Blocks SPARQL update and graph-management operations in v0.1 and v0.2.
 * Also validates graph scope access (inferred/union require reasoning to have been run).
 * This is enforced at the service layer, not only in the SPARQL executor.
 */
public class SparqlSafetyGuard {

    private static final java.util.Set<String> UPDATE_KEYWORDS = java.util.Set.of(
        "INSERT", "DELETE", "LOAD", "CLEAR", "CREATE", "DROP", "MOVE"
    );

    /**
     * Check if a SPARQL query contains update operations.
     * If it does, return a READONLY_VIOLATION error.
     * v0.2: UPDATE operations are blocked on ALL scopes (explicit, inferred, union).
     */
    public ServiceResult<Void> checkReadonly(String sparqlQuery) {
        if (sparqlQuery == null || sparqlQuery.isBlank()) {
            return ServiceResult.error(ServiceError.invalidSparql("Query is empty."));
        }

        String normalized = sparqlQuery.toUpperCase().trim();

        for (String keyword : UPDATE_KEYWORDS) {
            if (normalized.contains(keyword)) {
                // Check if it's inside a string literal (rough heuristic)
                // For v0.1/v0.2, we block any occurrence of update keywords
                return ServiceResult.error(ServiceError.readonlyViolation(keyword));
            }
        }

        return ServiceResult.success(null,
            org.owl4agents.core.ResultMetadata.explicit(
                new org.owl4agents.core.OntologyId("safety-check")));
    }

    /**
     * Validate graph scope for SPARQL queries.
     * v0.2: inferred and union scopes require reasoning to have been run first.
     * @param scope The requested graph scope
     * @param hasReasoningRun Whether reasoning has been executed for this ontology
     * @return Success if scope is valid, error otherwise
     */
    public ServiceResult<Void> validateScope(GraphScope scope, boolean hasReasoningRun) {
        if (scope == null) {
            return ServiceResult.error(ErrorCode.INVALID_SPARQL_SCOPE,
                "Scope value must be one of: explicit, inferred, union");
        }

        if (scope == GraphScope.EXPLICIT) {
            // Explicit scope always available (v0.1 behavior)
            return ServiceResult.success(null,
                org.owl4agents.core.ResultMetadata.empty());
        }

        if (scope == GraphScope.INFERRED || scope == GraphScope.UNION) {
            if (!hasReasoningRun) {
                return ServiceResult.error(ErrorCode.REASONING_NOT_RUN,
                    "Reasoning has not been executed. " +
                    (scope == GraphScope.INFERRED ?
                        "Inferred graph scope requires reasoning to have been run first." :
                        "Union graph scope requires reasoning to have been run first."));
            }
            return ServiceResult.success(null,
                org.owl4agents.core.ResultMetadata.empty());
        }

        return ServiceResult.error(ErrorCode.INVALID_SPARQL_SCOPE,
            "Invalid scope value: " + scope.jsonName() + ". Must be one of: explicit, inferred, union");
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