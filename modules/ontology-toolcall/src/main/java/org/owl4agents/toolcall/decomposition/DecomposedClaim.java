package org.owl4agents.toolcall.decomposition;

import java.util.Optional;

import org.owl4agents.core.GraphScope;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;

/**
 * v0.8.7 CL-001: A single semantic claim emitted by {@link ClaimDecomposer}
 * when decomposing a {@link org.owl4agents.overlay.ToolCallCandidate} against
 * a {@link org.owl4agents.toolcall.ToolContract}.
 *
 * <p>Each {@code DecomposedClaim} is the tool-call-side representation of a
 * single OWL verification obligation. It carries the {@link ClaimCategory}
 * for role re-attachment after batch verification, plus the structured
 * subject/predicate/object tuple that {@link ToolCallClaimBatchAdapter}
 * projects onto the existing {@link org.owl4agents.core.model.Claim} schema
 * consumed by {@code ClaimWorkflowService.verifyBatch}.</p>
 *
 * <p>The record is intentionally narrower than {@code Claim}: it captures
 * only the fields the decomposer can produce from a {@code ToolContract} +
 * {@code ToolCallCandidate}, leaving the {@code ontologyId} to be supplied
 * by the adapter at verification time (so a single batch can be replayed
 * against different ontologies in tests).</p>
 *
 * @param claimId    unique identifier (UUID-style) of this claim within the batch
 * @param callId     the {@code ToolCallCandidate.callId} this claim belongs to
 * @param category   the semantic {@link ClaimCategory} driving role re-attachment
 * @param claimType  the existing {@link ClaimType} used when building the Claim
 * @param subject    subject entity (individual, class, datatype, etc.); nullable
 *                   for claim types that do not require a subject
 * @param predicate  predicate IRI or keyword (e.g. {@code "hasLocation"},
 *                   {@code "subClassOf"}); nullable
 * @param object     object entity (individual, class, literal, etc.); nullable
 * @param required   whether this claim is required for the aggregate verdict
 *                   (defaults to {@code true}; optional claims do not override
 *                   the aggregate status)
 * @param reasoner   optional reasoner override forwarded to {@code Claim}
 * @param graphScope optional graph scope forwarded to {@code Claim}
 * @param evidence   optional human-readable evidence hint describing what the
 *                   reasoner is expected to infer (used in tests and reports)
 */
public record DecomposedClaim(
    String claimId,
    String callId,
    ClaimCategory category,
    ClaimType claimType,
    ClaimEntity subject,
    String predicate,
    ClaimEntity object,
    boolean required,
    Optional<String> reasoner,
    Optional<GraphScope> graphScope,
    Optional<String> evidence
) {
    public DecomposedClaim {
        if (claimId == null || claimId.isBlank()) {
            throw new IllegalArgumentException("DecomposedClaim.claimId must not be blank");
        }
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("DecomposedClaim.callId must not be blank");
        }
        if (category == null) {
            throw new IllegalArgumentException("DecomposedClaim.category must not be null");
        }
        if (claimType == null) {
            throw new IllegalArgumentException("DecomposedClaim.claimType must not be null");
        }
        if (reasoner == null) reasoner = Optional.empty();
        if (graphScope == null) graphScope = Optional.empty();
        if (evidence == null) evidence = Optional.empty();
    }

    /**
     * Convenience constructor for the common case of a required claim with
     * no reasoner override, no graph scope, and no evidence hint.
     */
    public DecomposedClaim(String claimId,
                           String callId,
                           ClaimCategory category,
                           ClaimType claimType,
                           ClaimEntity subject,
                           String predicate,
                           ClaimEntity object) {
        this(claimId, callId, category, claimType, subject, predicate, object,
            true, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
