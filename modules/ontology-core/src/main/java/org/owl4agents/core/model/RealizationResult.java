package org.owl4agents.core.model;

import java.util.List;

/**
 * Result of realization: the complete set of inferred individual types
 * and the delta (newly inferred types only) compared to explicit type declarations.
 */
public record RealizationResult(
    String ontologyId,
    String reasonerName,
    List<InferredTypeEntry> completeTypes,
    List<InferredTypeEntry> delta
) {}