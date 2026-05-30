package org.owl4agents.core.model;

/**
 * A triple value for CONSTRUCT/DESCRIBE results.
 */
public record TripleValue(
    String subject,
    String predicate,
    String object,
    String objectDatatype
) {}