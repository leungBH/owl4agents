package org.owl4agents.core.model;

import java.util.List;
import java.util.Optional;

/**
 * Explanation for why a claim received an unknown verdict.
 * Contains the reason category and supporting details.
 */
public record UnknownExplanation(
    String claimId,
    String ontologyId,
    UnknownReason reason,
    Optional<String> explanation,
    List<String> relevantEntities,
    Optional<String> suggestedAction
) {}