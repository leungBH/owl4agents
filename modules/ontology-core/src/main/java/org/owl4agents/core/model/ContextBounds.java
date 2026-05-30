package org.owl4agents.core.model;

import java.util.List;

/**
 * Bounds applied to QA context generation.
 */
public record ContextBounds(
    int maxEntities,
    int maxDepth,
    List<String> includedSections
) {}