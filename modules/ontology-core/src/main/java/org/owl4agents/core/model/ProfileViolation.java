package org.owl4agents.core.model;

/**
 * A single OWL profile violation.
 */
public record ProfileViolation(
    String profile,
    String description,
    String axiomType,
    int count
) {}