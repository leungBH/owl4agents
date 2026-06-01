package org.owl4agents.core.model;

import java.util.List;

/**
 * Result of literal validation against a datatype.
 */
public record LiteralValidationResult(
    String literalValue,
    String datatypeIRI,
    boolean valid,
    List<String> violations
) {}