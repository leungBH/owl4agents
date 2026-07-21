package org.owl4agents.core.model;

import java.util.List;
import java.util.Optional;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.GraphScope;

/**
 * The result of verifying a single structured claim against an ontology.
 * Contains the verdict, evidence items, and metadata about the verification process.
 *
 * <p>v0.8.5 BREAKING: schema v2. The {@code verdict} field is renamed to
 * {@code semanticVerdict} and typed as {@code Optional<Verdict>} (nullable
 * when {@code executionStatus != COMPLETED}). New fields
 * {@code executionStatus}, {@code errorCode}, and {@code perStageTiming}
 * are added. The convenience accessor {@link #verdict()} is retained for
 * backward compatibility and returns {@code semanticVerdict.orElse(null)}.
 *
 * <p>v0.8.6: New nullable {@code metadata} field of type
 * {@link ReasonerCallMetadata}, populated from the LAST stage's
 * {@code ServiceResult.reasonerMetadata} that invoked a reasoner
 * (Stage 4 > Stage 3 > Stage 2; Stage 1 short-circuit -> ?null).
 * Existing callers reading {@code semanticVerdict}, {@code errorCode},
 * {@code evidence} are NOT affected -> ?the field is additive.</p>
 */
public record ClaimVerificationResult(
    String claimId,
    String ontologyId,
    ClaimType claimType,
    Optional<Verdict> semanticVerdict,
    List<EvidenceItem> evidence,
    Optional<UnknownReason> unknownReason,
    Optional<String> unknownExplanation,
    Optional<String> reasonerName,
    Optional<GraphScope> graphScope,
    boolean truncated,
    int totalEvidenceAvailable,
    ExecutionStatus executionStatus,
    Optional<ErrorCode> errorCode,
    PerStageTiming perStageTiming,
    ReasonerCallMetadata metadata
) {
    /**
     * Backward-compatible accessor that returns the verdict or {@code null}
     * when absent (timeout/error). Equivalent to {@code semanticVerdict().orElse(null)}.
     */
    public Verdict verdict() {
        return semanticVerdict.orElse(null);
    }

    /**
     * Canonical factory for the common COMPLETED case (no error, no per-stage timing).
     */
    public static ClaimVerificationResult completed(
        String claimId, String ontologyId, ClaimType claimType,
        Verdict verdict, List<EvidenceItem> evidence,
        Optional<UnknownReason> unknownReason, Optional<String> unknownExplanation,
        Optional<String> reasonerName, Optional<GraphScope> graphScope,
        boolean truncated, int totalEvidenceAvailable
    ) {
        return new ClaimVerificationResult(
            claimId, ontologyId, claimType, Optional.ofNullable(verdict),
            evidence, unknownReason, unknownExplanation, reasonerName, graphScope,
            truncated, totalEvidenceAvailable,
            ExecutionStatus.COMPLETED, Optional.empty(), PerStageTiming.empty(), null
        );
    }

    /**
     * v0.8.6: Canonical factory for COMPLETED case with reasoner call metadata.
     */
    public static ClaimVerificationResult completed(
        String claimId, String ontologyId, ClaimType claimType,
        Verdict verdict, List<EvidenceItem> evidence,
        Optional<UnknownReason> unknownReason, Optional<String> unknownExplanation,
        Optional<String> reasonerName, Optional<GraphScope> graphScope,
        boolean truncated, int totalEvidenceAvailable,
        ReasonerCallMetadata metadata
    ) {
        return new ClaimVerificationResult(
            claimId, ontologyId, claimType, Optional.ofNullable(verdict),
            evidence, unknownReason, unknownExplanation, reasonerName, graphScope,
            truncated, totalEvidenceAvailable,
            ExecutionStatus.COMPLETED, Optional.empty(), PerStageTiming.empty(), metadata
        );
    }

    /**
     * Canonical factory for the TIMEOUT or ERROR case (no semantic verdict).
     */
    public static ClaimVerificationResult errored(
        String claimId, String ontologyId, ClaimType claimType,
        ExecutionStatus executionStatus, ErrorCode errorCode,
        Optional<String> reasonerName, Optional<GraphScope> graphScope,
        PerStageTiming perStageTiming
    ) {
        return new ClaimVerificationResult(
            claimId, ontologyId, claimType, Optional.empty(),
            List.of(), Optional.empty(), Optional.empty(), reasonerName, graphScope,
            false, 0,
            executionStatus, Optional.of(errorCode), perStageTiming, null
        );
    }

    /**
     * v0.8.6: Canonical factory for TIMEOUT/ERROR case with reasoner call metadata.
     */
    public static ClaimVerificationResult errored(
        String claimId, String ontologyId, ClaimType claimType,
        ExecutionStatus executionStatus, ErrorCode errorCode,
        Optional<String> reasonerName, Optional<GraphScope> graphScope,
        PerStageTiming perStageTiming, ReasonerCallMetadata metadata
    ) {
        return new ClaimVerificationResult(
            claimId, ontologyId, claimType, Optional.empty(),
            List.of(), Optional.empty(), Optional.empty(), reasonerName, graphScope,
            false, 0,
            executionStatus, Optional.of(errorCode), perStageTiming, metadata
        );
    }
}
