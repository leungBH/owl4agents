package org.owl4agents.core;

/**
 * Enum representing the graph scope for query and retrieval operations.
 * v0.1 primarily uses EXPLICIT scope.
 */
public enum GraphScope {
    EXPLICIT("explicit"),
    INFERRED("inferred"),
    UNION("union");

    private final String jsonName;

    GraphScope(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}