package org.owl4agents.core.model;

import java.util.Optional;

/**
 * Workflow options for v0.5 batch claim verification.
 */
public record WorkflowOptions(
    Optional<String> reasoner,
    Optional<Boolean> requireReasoning,
    Optional<Integer> maxEvidencePerClaim,
    Optional<Integer> maxContextTokens
) {}