package org.owl4agents.retrieval;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;
import org.owl4agents.core.evidence.EvidenceMetadata;

import java.util.ArrayList;
import java.util.List;

/**
 * Implements entity search by IRI, prefixed name, label, comment, type, and alias index.
 * Uses the EntityIndex for fast lookup.
 */
public class EntitySearchService {

    private final EntityIndex entityIndex;
    private final OntologyId ontologyId;

    public EntitySearchService(EntityIndex entityIndex, OntologyId ontologyId) {
        this.entityIndex = entityIndex;
        this.ontologyId = ontologyId;
    }

    /**
     * Search for entities matching the given query.
     */
    public ServiceResult<SearchResult> search(String query) {
        List<EntityIndex.IndexEntry> indexResults = entityIndex.search(query);

        List<SearchMatch> matches = new ArrayList<>();
        for (EntityIndex.IndexEntry entry : indexResults) {
            double score = computeScore(query, entry);
            String matchReason = determineMatchReason(query, entry);

            matches.add(new SearchMatch(
                entry.iri(),
                entry.prefixedName(),
                entry.label(),
                entry.comment(),
                entry.type(),
                score,
                matchReason,
                EvidenceMetadata.explicit(ontologyId, entry.iri())
            ));
        }

        // Sort by score descending
        matches.sort((a, b) -> Double.compare(b.score(), a.score()));

        SearchResult result = new SearchResult(matches, matches.size(), query);
        return ServiceResult.success(result, ResultMetadata.explicit(ontologyId));
    }

    private double computeScore(String query, EntityIndex.IndexEntry entry) {
        String normalizedQuery = query.toLowerCase().trim();

        // Exact IRI match gets highest score
        if (entry.iri().equals(query)) return 1.0;

        // Exact label match
        if (entry.label() != null && entry.label().equalsIgnoreCase(query)) return 0.9;

        // Exact prefixed name match
        if (entry.prefixedName() != null && entry.prefixedName().equalsIgnoreCase(query)) return 0.85;

        // Partial label match
        if (entry.label() != null && entry.label().toLowerCase().contains(normalizedQuery)) return 0.7;

        // Partial prefixed name match
        if (entry.prefixedName() != null && entry.prefixedName().toLowerCase().contains(normalizedQuery)) return 0.6;

        return 0.5;
    }

    private String determineMatchReason(String query, EntityIndex.IndexEntry entry) {
        if (entry.iri().equals(query)) return "iri";
        if (entry.label() != null && entry.label().equalsIgnoreCase(query)) return "label";
        if (entry.prefixedName() != null && entry.prefixedName().equalsIgnoreCase(query)) return "alias";
        if (entry.comment() != null && entry.comment().toLowerCase().contains(query.toLowerCase())) return "comment";
        return "label";
    }
}