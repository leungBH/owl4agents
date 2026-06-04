package org.owl4agents.core.model;

import java.util.List;
import java.util.Optional;

import org.owl4agents.core.GraphScope;

/**
 * The result of verifying a single structured claim against an ontology.
 * Contains the verdict, evidence items, and metadata about the verification process.
 */
public record ClaimVerificationResult(
    String claimId,
    String ontologyId,
    ClaimType claimType,
    Verdict verdict,
    List<EvidenceItem> evidence,
    Optional<UnknownReason> unknownReason,
    Optional<String> unknownExplanation,
    Optional<String> reasonerName,
    Optional<GraphScope> graphScope,
    boolean truncated,
    int totalEvidenceAvailable
) {}