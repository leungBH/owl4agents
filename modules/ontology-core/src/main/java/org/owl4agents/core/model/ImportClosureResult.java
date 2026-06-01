package org.owl4agents.core.model;

import java.util.List;

/**
 * Result of import closure inspection.
 * Returns the full import hierarchy with metadata for each imported ontology.
 */
public record ImportClosureResult(
    String ontologyId,
    List<ImportEntry> imports,
    boolean hasCycles,
    List<String> cycleWarning
) {}