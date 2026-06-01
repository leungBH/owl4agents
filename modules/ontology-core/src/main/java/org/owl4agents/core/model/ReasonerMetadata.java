package org.owl4agents.core.model;

/**
 * Metadata about the reasoner used for explanation.
 */
public record ReasonerMetadata(
    String reasonerName,
    String reasonerVersion,
    long reasoningTimeMs
) {}