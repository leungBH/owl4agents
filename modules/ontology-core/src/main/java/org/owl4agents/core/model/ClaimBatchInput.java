package org.owl4agents.core.model;

import java.util.List;
import java.util.Optional;

import org.owl4agents.core.GraphScope;

/**
 * Batch claim input for v0.5 answer-level workflow verification.
 * Claims must be structured — free-text-only submissions are rejected as invalid_input.
 */
public record ClaimBatchInput(
    String answerId,
    Optional<String> question,
    Optional<String> answerText,
    List<BatchClaim> claims,
    Optional<WorkflowOptions> options
) {

    /**
     * A single claim within a batch, with id, type, required flag, and type-specific fields
     * compatible with existing v0.3 claim verification.
     */
    public record BatchClaim(
        String id,
        ClaimType type,
        boolean required,
        Optional<ClaimEntity> subject,
        Optional<String> predicate,
        Optional<ClaimEntity> object,
        Optional<String> reasoner,
        Optional<GraphScope> graphScope,
        Optional<java.util.Map<String, Object>> options
    ) {}
}