package org.owl4agents.core.model;

/**
 * An object property assertion for an individual.
 */
public record ObjectPropertyAssertion(
    String propertyIri,
    String propertyLabel,
    String targetIri,
    String targetLabel
) {}