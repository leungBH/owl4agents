package org.owl4agents.toolcall.decomposition;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v0.8.7 CL-001 / CL-002: Output of {@link ClaimDecomposer} for a single
 * {@link org.owl4agents.overlay.ToolCallCandidate}.
 *
 * <p>The batch carries the decomposed claims plus the metadata required by
 * the {@code claim-decomposition} spec:</p>
 * <ul>
 *   <li>{@code callId} — matches {@code ToolCallCandidate.callId}</li>
 *   <li>{@code decompositionTimestamp} — when the decomposition ran
 *       (used for audit and TOCTOU diagnostics)</li>
 *   <li>{@code sourceModel} — propagated from {@code ToolCallCandidate.sourceModel}
 *       so downstream reports can attribute the call</li>
 *   <li>{@code claims} — list of {@link DecomposedClaim} objects (the
 *       structured representation; {@link ToolCallClaimBatchAdapter} projects
 *       them onto {@link org.owl4agents.core.model.Claim} for batch
 *       verification)</li>
 *   <li>{@code claimCount} — {@code claims.size()} (denormalized for
 *       quick audit without iterating the list)</li>
 *   <li>{@code claimRole} — map from {@code claimId} to the role string
 *       ({@code target_class} / {@code location} / {@code capability} /
 *       {@code operation_capability} / {@code permission} / {@code datatype})
 *       per the spec</li>
 * </ul>
 *
 * <p>The "Empty batch for query-only tool" scenario is honored: a contract
 * with no semantic obligations yields an empty {@code claims} list, an
 * empty {@code claimRole} map, and {@code claimCount=0}. The pipeline
 * skips stage 6 (OWL batch verification) without error in this case.</p>
 *
 * <p>The batch is immutable: the canonical constructor defensive-copies
 * the {@code claims} list and the {@code claimRole} map.</p>
 */
public record ToolCallClaimBatch(
    String callId,
    Instant decompositionTimestamp,
    Optional<String> sourceModel,
    List<DecomposedClaim> claims,
    int claimCount,
    Map<String, String> claimRole
) {
    public ToolCallClaimBatch {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("ToolCallClaimBatch.callId must not be blank");
        }
        if (decompositionTimestamp == null) {
            decompositionTimestamp = Instant.now();
        }
        if (sourceModel == null) sourceModel = Optional.empty();
        if (claims == null) {
            claims = List.of();
        } else {
            claims = List.copyOf(claims);
        }
        claimCount = claims.size();
        if (claimRole == null) {
            claimRole = buildDefaultRoleMap(claims);
        } else {
            // Defensive copy + ensure every claim has an entry (defaults
            // derived from the claim's category when missing).
            Map<String, String> merged = buildDefaultRoleMap(claims);
            merged.putAll(claimRole);
            claimRole = Map.copyOf(merged);
        }
    }

    /**
     * Build a {@code claimRole} map from each claim's category, used as
     * the default when the caller does not supply an explicit map. The
     * resulting keys are exactly the {@code claimId} values in
     * {@code claims}; values are the {@link ClaimCategory#roleName()}.
     */
    private static Map<String, String> buildDefaultRoleMap(List<DecomposedClaim> claims) {
        Map<String, String> m = new LinkedHashMap<>();
        for (DecomposedClaim c : claims) {
            m.put(c.claimId(), c.category().roleName());
        }
        return m;
    }

    /**
     * Whether the batch is empty (no claims). The pipeline uses this to
     * skip stage 6 per the "Empty batch for query-only tool" scenario.
     */
    public boolean isEmpty() {
        return claims.isEmpty();
    }
}
