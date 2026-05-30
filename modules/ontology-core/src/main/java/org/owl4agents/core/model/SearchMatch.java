package org.owl4agents.core.model;

import org.owl4agents.core.EntityType;
import org.owl4agents.core.evidence.EvidenceMetadata;

/**
 * A single entity match in a search result.
 */
public record SearchMatch(
    String iri,
    String prefixedName,
    String label,
    String comment,
    EntityType type,
    double score,
    String matchReason,
    EvidenceMetadata evidence
) {}