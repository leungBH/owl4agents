package org.owl4agents.benchmark;

import java.util.Map;

import org.owl4agents.core.model.Verdict;

/**
 * Summary line appended at the end of benchmark JSONL output.
 * Contains aggregate metrics computed from all per-question result lines.
 *
 * verificationCoverage = (supported + contradicted) / totalClaims per spec.
 * unknownRate and outOfScopeRate are reported separately per spec.
 */
public record BenchmarkResultSummary(
    String type,
    int totalQuestions,
    double accuracy,
    double falseSupportRate,
    int falseSupportedCount,
    double unresolvedRate,
    int falseUnknownCount,
    double verificationCoverage,
    double unknownRate,
    double outOfScopeRate,
    Map<Verdict, Integer> perVerdictCounts,
    Map<String, Long> perReasonerTiming
) {

    /** Type marker for JSONL summary lines. */
    public static final String TYPE = "summary";
}