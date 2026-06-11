package org.owl4agents.benchmark;

import java.util.Optional;

import org.owl4agents.core.model.Verdict;

/**
 * A single result line in benchmark JSONL output.
 * Contains the question's verification results alongside expected values for comparison.
 * verdictMatch is a JSON boolean (true/false), NOT a string.
 *
 * reviewStatus: Optional — present for processed lines (pending/reviewed/approved),
 * absent (null in JSON) for Tier-1 structurally blocked lines.
 * edgeCase: true when the question is flagged edge-case and excluded from primary metrics.
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
    boolean edgeCase
) {}