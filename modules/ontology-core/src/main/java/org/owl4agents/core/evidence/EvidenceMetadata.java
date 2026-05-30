package org.owl4agents.core.evidence;

import org.owl4agents.core.GraphScope;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ResultMetadata;

/**
 * Evidence metadata for results that include provenance information.
 * Tracks ontology ID, graph scope, entity source, axiom source,
 * and whether the result is explicit or inferred.
 */
public record EvidenceMetadata(
    OntologyId ontologyId,
    GraphScope graphScope,
    String entitySource,
    String axiomSource,
    String tripleSource,
    String extractionStatus
) {
    public static final String EXTRACTION_EXPLICIT = "explicit";
    public static final String EXTRACTION_INFERRED = "inferred";
    public static final String EXTRACTION_UNKNOWN = "unknown";

    public static EvidenceMetadata explicit(OntologyId ontologyId, String entitySource) {
        return new EvidenceMetadata(ontologyId, GraphScope.EXPLICIT, entitySource,
            null, null, EXTRACTION_EXPLICIT);
    }

    public static EvidenceMetadata explicitWithAxiom(OntologyId ontologyId, String entitySource, String axiomSource) {
        return new EvidenceMetadata(ontologyId, GraphScope.EXPLICIT, entitySource,
            axiomSource, null, EXTRACTION_EXPLICIT);
    }
}