package org.owl4agents.core.model;

import java.util.List;

/**
 * Result of class restrictions retrieval.
 * Returns all explicit (and optionally inferred) class restrictions for a given class.
 */
public record ClassRestrictionsResult(
    String ontologyId,
    String classIRI,
    List<ClassRestriction> restrictions
) {}