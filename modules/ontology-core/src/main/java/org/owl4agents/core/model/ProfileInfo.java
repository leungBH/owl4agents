package org.owl4agents.core.model;

import java.util.List;

/**
 * OWL profile information for an imported ontology.
 */
public record ProfileInfo(
    List<String> profiles,
    List<ProfileViolation> violations
) {}