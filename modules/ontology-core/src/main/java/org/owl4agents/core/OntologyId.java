package org.owl4agents.core;

/**
 * Represents an ontology identifier in the workspace catalog.
 * Ontology IDs are user-provided identifiers used to reference imported ontologies.
 * They are not IRIs — they are short names like "pizza" or "medical".
 */
public record OntologyId(String id) {

    public OntologyId {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Ontology ID must not be null or blank");
        }
    }
}