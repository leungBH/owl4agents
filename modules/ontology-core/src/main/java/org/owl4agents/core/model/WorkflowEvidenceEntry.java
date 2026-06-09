package org.owl4agents.core.model;

import java.util.Optional;

/**
 * Canonical evidence entry schema for v0.5 workflow reports and evidence context.
 * This is distinct from the v0.3 single-claim EvidenceItem schema (evidenceId, role, kind,
 * value, source, graphScope, entities). Both schemas coexist; this is a compact projection
 * for batch workflow reports and agent-facing evidence context.
 */
public record WorkflowEvidenceEntry(
    String kind,
    String summary,
    String source,
    Optional<String> reasoner,
    Optional<String> provenance
) {}