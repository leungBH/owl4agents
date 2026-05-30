package org.owl4agents.core.model;

/**
 * Result of a SPARQL ASK query execution.
 */
public record AskResult(
    String queryForm,
    boolean result
) {}