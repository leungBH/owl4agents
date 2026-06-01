package org.owl4agents.core.model;

import java.util.List;

/**
 * Result of consistency checking.
 * Indicates whether the ontology is consistent and includes
 * the list of unsatisfiable classes if inconsistent.
 */
public record ConsistencyResult(
    String ontologyId,
    String reasonerName,
    boolean consistent,
    List<String> unsatisfiableClassIRIs
) {}