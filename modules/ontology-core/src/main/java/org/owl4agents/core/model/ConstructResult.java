package org.owl4agents.core.model;

import java.util.List;

/**
 * Result of a SPARQL CONSTRUCT query execution.
 */
public record ConstructResult(
    String queryForm,
    List<TripleValue> triples,
    int totalTriples,
    boolean truncated
) {}