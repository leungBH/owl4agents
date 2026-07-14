package org.owl4agents.benchmark;

import java.util.List;
import java.util.Optional;

import org.owl4agents.core.model.ExecutionStatus;
import org.owl4agents.core.model.PerStageTiming;
import org.owl4agents.core.model.Verdict;

/**
 * v0.8.5: Per-claim result line for exact-results-388.jsonl output (task 13.5).
 *
 * Each line represents a single claim verification with the full schema v2
 * fields: semanticVerdict (nullable for errored results), executionStatus,
 * errorCode, perStageTiming, and consistency check details.
 *
 * This is distinct from {@link BenchmarkResultLine} which operates at the
 * question (aggregate) level.
 */
public record ExactResultLine(
    String claimId,
    String ontologyId,
    String claimAxiom,              // Manchester syntax
    boolean sourceOntologyConsistent,
    String entailed,                // "asserted", "inferred", "not-entailed"
    String consistencyAfterAddition, // "consistent", "inconsistent", "not-checked"
    Verdict semanticVerdict,        // null when executionStatus != completed
    ExecutionStatus executionStatus,
    String errorCode,               // null when executionStatus == completed
    List<String> evidence,          // evidence kind summaries
    String reasoner,
    long elapsedMillis,
    String ontologyFingerprint,
    PerStageTiming perStageTiming
) {}
