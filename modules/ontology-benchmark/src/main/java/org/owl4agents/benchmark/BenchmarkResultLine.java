package org.owl4agents.benchmark;

import java.util.Optional;

import org.owl4agents.core.model.ExecutionStatus;
import org.owl4agents.core.model.PerStageTiming;
import org.owl4agents.core.model.Verdict;

/**
 * A single result line in benchmark JSONL output.
 * Contains the question's verification results alongside expected values for comparison.
 * verdictMatch is a JSON boolean (true/false), NOT a string.
 *
 * reviewStatus: Optional — present for processed lines (pending/reviewed/approved),
 * absent (null in JSON) for Tier-1 structurally blocked lines.
 * edgeCase: true when the question is flagged edge-case and excluded from primary metrics.
 *
 * v0.8.5: Added executionStatus, errorCode, and perStageTiming for schema v2
 * (tasks 13.1, 13.3). executionStatus is COMPLETED for normal results, TIMEOUT
 * for timed-out claims, ERROR for errored claims. actualVerdict is null when
 * executionStatus != COMPLETED.
 */
public record BenchmarkResultLine(
    String questionId,
    String ontologyId,
    String reasoner,
    int claimsVerified,
    Verdict actualVerdict,
    Verdict expectedVerdict,
    boolean verdictMatch,
    long elapsedMs,
    Optional<String> reviewStatus,
    Optional<String> error,
    boolean edgeCase,
    // v0.8.5 fields
    ExecutionStatus executionStatus,
    Optional<String> errorCode,
    PerStageTiming perStageTiming
) {
    /**
     * Legacy constructor for backward compatibility (pre-v0.8.5 callers).
     * Defaults executionStatus to COMPLETED, errorCode to empty, perStageTiming to empty.
     */
    public BenchmarkResultLine(
        String questionId,
        String ontologyId,
        String reasoner,
        int claimsVerified,
        Verdict actualVerdict,
        Verdict expectedVerdict,
        boolean verdictMatch,
        long elapsedMs,
        Optional<String> reviewStatus,
        Optional<String> error,
        boolean edgeCase
    ) {
        this(questionId, ontologyId, reasoner, claimsVerified, actualVerdict, expectedVerdict,
            verdictMatch, elapsedMs, reviewStatus, error, edgeCase,
            ExecutionStatus.COMPLETED, Optional.empty(), PerStageTiming.empty());
    }
}