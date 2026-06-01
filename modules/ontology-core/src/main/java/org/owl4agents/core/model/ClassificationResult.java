package org.owl4agents.core.model;

import java.util.List;

/**
 * Result of classification: the complete inferred class hierarchy
 * and the delta (new relationships only) compared to the explicit hierarchy.
 */
public record ClassificationResult(
    String ontologyId,
    String reasonerName,
    List<InferredHierarchyEntry> completeHierarchy,
    List<InferredHierarchyEntry> delta
) {}