package org.owl4agents.benchmark;

import java.util.Map;

import org.owl4agents.core.model.Verdict;

/**
 * Summary line appended at the end of benchmark JSONL output.
 * Contains aggregate metrics computed from all per-question result lines.
 *
 * verificationCoverage = (supported + contradicted) / totalClaims per spec.
 * unknownRate and outOfScopeRate are reported separately per spec.
 *
 * v0.8.5: Added p50Ms, p95Ms, p99Ms, maxMs, timeoutCount, errorCount,
 * peakHeapBytes for performance summary (task 13.4).
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
    Map<String, Long> perReasonerTiming,
    // v0.8.5 performance metrics (task 13.4)
    long p50Ms,
    long p95Ms,
    long p99Ms,
    long maxMs,
    int timeoutCount,
    int errorCount,
    long peakHeapBytes
) {

    /** Type marker for JSONL summary lines. */
    public static final String TYPE = "summary";

    /**
     * Legacy constructor for backward compatibility (pre-v0.8.5 callers).
     * Defaults v0.8.5 performance metrics to 0.
     */
    public BenchmarkResultSummary(
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
        this(type, totalQuestions, accuracy, falseSupportRate, falseSupportedCount,
            unresolvedRate, falseUnknownCount, verificationCoverage, unknownRate, outOfScopeRate,
            perVerdictCounts, perReasonerTiming,
            0L, 0L, 0L, 0L, 0, 0, 0L);
    }
}