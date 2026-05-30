package org.owl4agents.core;

/**
 * Represents an entity identifier within an ontology.
 * Entity IDs are OWL entity IRIs, such as "http://example.org/ontology#MyClass".
 */
public record EntityId(String iri) {

    public EntityId {
        if (iri == null || iri.isBlank()) {
            throw new IllegalArgumentException("Entity IRI must not be null or blank");
        }
    }
}