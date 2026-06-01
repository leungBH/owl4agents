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
        if (extractionStatus == null) {
            extractionStatus = EXTRACTION_EXPLICIT;
        }
        if (timestamp == null) {
            timestamp = java.time.Instant.now().toString();
        }
    }

    /**
     * Convenience constructor with minimal required fields.
     * v0.2 service methods may not always have an ontologyId at hand.
     */
    public static ResultMetadata empty() {
        return new ResultMetadata(null, null, EXTRACTION_UNKNOWN, java.time.Instant.now().toString());
    }

    public static ResultMetadata explicit(OntologyId ontologyId) {
        return new ResultMetadata(ontologyId, GraphScope.EXPLICIT, EXTRACTION_EXPLICIT,
            java.time.Instant.now().toString());
    }
}