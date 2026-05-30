package org.owl4agents.core;

/**
 * Metadata attached to every service operation result.
 * Includes ontology ID, graph scope, extraction status, and timestamp.
 */
public record ResultMetadata(
    OntologyId ontologyId,
    GraphScope graphScope,
    String extractionStatus,
    String timestamp
) {

    public static final String EXTRACTION_EXPLICIT = "explicit";
    public static final String EXTRACTION_INFERRED = "inferred";
    public static final String EXTRACTION_UNKNOWN = "unknown";

    public ResultMetadata {
        if (ontologyId == null) {
            throw new IllegalArgumentException("Ontology ID must not be null");
        }
        if (graphScope == null) {
            graphScope = GraphScope.EXPLICIT;
        }
        if (extractionStatus == null) {
            extractionStatus = EXTRACTION_EXPLICIT;
        }
    }

    public static ResultMetadata explicit(OntologyId ontologyId) {
        return new ResultMetadata(ontologyId, GraphScope.EXPLICIT, EXTRACTION_EXPLICIT,
            java.time.Instant.now().toString());
    }
}