package org.owl4agents.core.model;

import java.util.List;
import java.util.Optional;

/**
 * Top-level workflow report for v0.5 answer-level claim verification.
 * Contains the aggregate status, per-claim results, scope diagnostics, and summary counts.
 */
public record AnswerVerificationReport(
    String answerId,
    String ontologyId,
    AggregateAnswerStatus aggregateStatus,
    List<ClaimWorkflowResult> claimResults,
    Optional<ScopeDiagnostic> scopeDiagnostic,
    Optional<VerdictSummary> summary
) {

    /**
     * Counts of claims per verdict category within the batch.
     */
    public record VerdictSummary(
        int supportedCount,
        int contradictedCount,
        int unknownCount,
        int outOfScopeCount,
        int requiredCount,
        int optionalCount
    ) {}
}