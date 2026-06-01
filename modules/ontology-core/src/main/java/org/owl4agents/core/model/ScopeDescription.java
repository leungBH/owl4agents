package org.owl4agents.core.model;

import java.util.List;

/**
 * Result of ontology scope description.
 * Describes domain coverage and known limitations of the ontology.
 */
public record ScopeDescription(
    String ontologyId,
    List<String> coveredDomains,
    List<String> knownGaps,
    List<String> profileLimitations,
    List<String> unsupportedFeatureTypes
) {}