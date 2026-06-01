package org.owl4agents.core.model;

/**
 * An entry in the import closure.
 */
public record ImportEntry(
    String ontologyIRI,
    String versionIRI,
    String owlProfile,
    boolean isDirect,
    boolean isTransitive
) {}