package org.owl4agents.core.model;

/**
 * Entity counts for an ontology summary.
 */
public record EntityCounts(
    int classes,
    int objectProperties,
    int dataProperties,
    int annotationProperties,
    int individuals,
    int datatypes
) {}