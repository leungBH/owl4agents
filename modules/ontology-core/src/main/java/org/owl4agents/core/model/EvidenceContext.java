package org.owl4agents.core.model;

import java.util.List;
import java.util.Optional;

/**
 * Compact evidence context for LLM agents, produced by the v0.5 workflow.
 * Contains per-claim verdicts and evidence in a budget-aware format suitable
 * for injection into agent prompts.
 */
public record EvidenceContext(
    String answerId,
    AggregateAnswerStatus status,
    List<ClaimContextEntry> claims,
    int omittedClaimCount,
    List<String> agentInstructions
) {

    /**
     * A single claim's context entry within the evidence context.
     */
    public record ClaimContextEntry(
        String id,
        Verdict verdict,
        String claimText,
        List<WorkflowEvidenceEntry> evidence,
        int omittedEvidenceCount,
        Optional<String> unknownReason,
        Optional<String> scopeDiagnostic
    ) {}
}