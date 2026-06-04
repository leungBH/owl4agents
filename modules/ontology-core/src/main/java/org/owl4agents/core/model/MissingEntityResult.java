package org.owl4agents.core.model;

import java.util.List;
import java.util.Optional;

/**
 * Result of detecting missing or ambiguous entities against an ontology.
 * Used by agents to inspect why a claim could not be grounded before verification.
 */
public record MissingEntityResult(
    String ontologyId,
    List<EntityMatch> matched,
    List<EntityMatch> ambiguous,
    List<EntityMatch> missing,
    List<EntityMatch> outOfScope
) {

    /**
     * A single entity match classification with the search term and matched IRI.
     */
    public record EntityMatch(
        String searchTerm,
        Optional<String> matchedIRI,
        Optional<String> kind,
        Optional<String> label
    ) {}
}