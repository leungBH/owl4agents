package org.owl4agents.core.model;

import java.util.List;

/**
 * Result of listing available reasoner adapters.
 */
public record ReasonerListResult(
    List<ReasonerCapability> reasoners
) {}