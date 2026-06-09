package org.owl4agents.core.model;

import java.util.List;
import java.util.Optional;

/**
 * Per-claim result within a v0.5 workflow report.
 * Preserves the full verdict, evidence, and diagnostics for each claim in the batch.
 */
public record ClaimWorkflowResult(
    String claimId,
    ClaimType claimType,
    boolean required,
    Verdict verdict,
    List<WorkflowEvidenceEntry> evidence,
    Optional<String> unknownReason,
    Optional<List<WorkflowEvidenceEntry>> counterexamples,
    Optional<List<MissingEntityResult.EntityMatch>> missingEntities,
    Optional<String> diagnostics
) {}