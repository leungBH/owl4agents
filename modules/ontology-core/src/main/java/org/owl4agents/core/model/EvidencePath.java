package org.owl4agents.core.model;

import java.util.List;
import java.util.Optional;

import org.owl4agents.core.GraphScope;

/**
 * Evidence path for a claim, containing all supporting or counter evidence items
 * along with metadata about the evidence collection process.
 */
public record EvidencePath(
    String claimId,
    String ontologyId,
    List<EvidenceItem> items,
    Optional<String> reasonerName,
    Optional<GraphScope> graphScope,
    boolean truncated,
    int totalAvailable
) {}