package org.owl4agents.core.model;

/**
 * Information about an OWL restriction on a class.
 */
public record RestrictionInfo(
    String restrictionType,
    String onProperty,
    String filler,
    Integer cardinality
) {}