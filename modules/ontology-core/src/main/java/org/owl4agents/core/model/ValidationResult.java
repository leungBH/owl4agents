package org.owl4agents.core.model;

/**
 * Result of SPARQL query validation.
 */
public record ValidationResult(
    boolean isValid,
    String queryForm,
    String parseError,
    Integer errorLine
) {
    public static ValidationResult valid(String queryForm) {
        return new ValidationResult(true, queryForm, null, null);
    }

    public static ValidationResult invalid(String parseError, Integer errorLine) {
        return new ValidationResult(false, "unknown", parseError, errorLine);
    }
}