package org.owl4agents.core.model;

import java.util.List;

/**
 * Result of a SPARQL DESCRIBE query execution.
 */
public record DescribeResult(
    String queryForm,
    List<TripleValue> triples,
    int totalTriples,
    boolean truncated
) {}