package org.owl4agents.core.model;

import java.util.List;
import java.util.Map;

/**
 * Result of a SPARQL SELECT query execution.
 */
public record SelectResult(
    String queryForm,
    List<String> variables,
    List<Map<String, BindingValue>> bindings,
    int totalBindings,
    boolean truncated
) {}