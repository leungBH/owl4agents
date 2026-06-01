package org.owl4agents.core.model;

import java.util.List;

/**
 * A minimal set of conflicting axioms that together cause inconsistency or unsatisfiability.
 */
public record ConflictingAxiomSet(
    List<String> axiomDescriptions,
    String syntaxFormat
) {}