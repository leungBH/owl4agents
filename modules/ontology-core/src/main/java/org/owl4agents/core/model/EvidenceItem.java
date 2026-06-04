package org.owl4agents.core.model;

import java.util.List;

/**
 * A single evidence item within a claim verification result.
 * Evidence items are typed, source-aware, and carry entity references.
 */
public record EvidenceItem(
    String evidenceId,
    String role,
    EvidenceKind kind,
    String value,
    String source,
    String reasoner,
    String graphScope,
    List<String> entities,
    String confidence
) {

    public static final String ROLE_SUPPORTING = "supporting";
    public static final String ROLE_COUNTER = "counter";

    public static final String CONFIDENCE_ENTAILED = "entailed";
    public static final String CONFIDENCE_EXPLICIT = "explicit";
    public static final String CONFIDENCE_INFERRED = "inferred";
}