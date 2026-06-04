package org.owl4agents.core.model;

import java.util.Map;
import java.util.Optional;

import org.owl4agents.core.GraphScope;

/**
 * A structured claim submitted for v0.3 verification.
 * Claims must conform to the structured schema — free-text-only claims are rejected.
 */
public record Claim(
    String claimId,
    ClaimType type,
    String ontologyId,
    ClaimEntity subject,
    String predicate,
    ClaimEntity object,
    Optional<String> reasoner,
    Optional<GraphScope> graphScope,
    Optional<Map<String, Object>> options
) {

    public static final String INCLUDE_EVIDENCE = "includeEvidence";
    public static final String MAX_EVIDENCE = "maxEvidence";
}