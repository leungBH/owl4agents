package org.owl4agents.core.model;

/**
 * Execution status of a claim verification operation.
 * When not {@link #COMPLETED}, {@code semanticVerdict} is null.
 */
public enum ExecutionStatus {
    COMPLETED("completed"),
    TIMEOUT("timeout"),
    ERROR("error");

    private final String jsonName;

    ExecutionStatus(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}
