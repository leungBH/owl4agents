package org.owl4agents.query;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.ValidationResult;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QueryParseException;
import org.apache.jena.sparql.core.Prologue;

/**
 * SPARQL query validation without execution.
 * Parses the query and reports syntax errors or structural issues.
 */
public class SparqlValidator {

    /**
     * Validate a SPARQL query without executing it.
     * Returns the query form (SELECT, ASK, CONSTRUCT, DESCRIBE) if valid,
     * or parser diagnostics if invalid.
     */
    public ServiceResult<ValidationResult> validate(String sparqlQuery) {
        if (sparqlQuery == null || sparqlQuery.isBlank()) {
            return ServiceResult.error(ServiceError.invalidSparql("Query is empty or null."));
        }

        try {
            Query query = QueryFactory.create(sparqlQuery);

            // Determine the query form
            String queryForm;
            if (query.isSelectType()) queryForm = "SELECT";
            else if (query.isAskType()) queryForm = "ASK";
            else if (query.isConstructType()) queryForm = "CONSTRUCT";
            else if (query.isDescribeType()) queryForm = "DESCRIBE";
            else queryForm = "unknown";

            return ServiceResult.success(ValidationResult.valid(queryForm),
                org.owl4agents.core.ResultMetadata.explicit(
                    new org.owl4agents.core.OntologyId("validation")));

        } catch (QueryParseException e) {
            String errorDetail = "Line " + e.getLine() + ": " + e.getMessage();
            return ServiceResult.error(ServiceError.invalidSparql(errorDetail));
        } catch (Exception e) {
            return ServiceResult.error(ServiceError.invalidSparql(
                "Unexpected parse error: " + e.getMessage()));
        }
    }

    /**
     * Check if a query is a SPARQL Update operation (INSERT, DELETE, etc.)
     * Returns true for any update-like query pattern.
     */
    public boolean isUpdateQuery(String sparqlQuery) {
        if (sparqlQuery == null) return false;
        String normalized = sparqlQuery.toUpperCase().trim();
        return normalized.contains("INSERT") ||
               normalized.contains("DELETE") ||
               normalized.contains("LOAD") ||
               normalized.contains("CLEAR") ||
               normalized.contains("CREATE") ||
               normalized.contains("DROP") ||
               normalized.contains("MOVE") ||
               normalized.startsWith("PREFIX") && normalized.contains("INSERT") ||
               normalized.startsWith("PREFIX") && normalized.contains("DELETE");
    }

    /**
     * Determine the update operation type from a SPARQL Update query.
     */
    public String getUpdateOperationType(String sparqlQuery) {
        if (sparqlQuery == null) return "unknown";
        String normalized = sparqlQuery.toUpperCase().trim();

        if (normalized.contains("INSERT")) return "INSERT";
        if (normalized.contains("DELETE")) return "DELETE";
        if (normalized.contains("LOAD")) return "LOAD";
        if (normalized.contains("CLEAR")) return "CLEAR";
        if (normalized.contains("CREATE")) return "CREATE";
        if (normalized.contains("DROP")) return "DROP";
        if (normalized.contains("MOVE")) return "MOVE";
        return "unknown";
    }
}