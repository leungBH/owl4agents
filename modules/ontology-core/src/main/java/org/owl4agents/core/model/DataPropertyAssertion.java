package org.owl4agents.core.model;

/**
 * A data property assertion for an individual.
 */
public record DataPropertyAssertion(
    String propertyIri,
    String propertyLabel,
    String literalValue,
    String datatypeIri
) {}