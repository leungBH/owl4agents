package org.owl4agents.core.model;

/**
 * A SPARQL evidence entry in QA context.
 */
public record SparqlEvidence(
    String query,
    String resultSummary,
    int bindingCount
) {}