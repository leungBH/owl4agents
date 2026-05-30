package org.owl4agents.core.model;

import org.owl4agents.core.evidence.EvidenceMetadata;

import java.util.List;

/**
 * Result of an entity search operation.
 */
public record SearchResult(
    List<SearchMatch> results,
    int totalResults,
    String query
) {}