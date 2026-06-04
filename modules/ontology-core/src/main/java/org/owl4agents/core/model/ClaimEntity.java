package org.owl4agents.core.model;

/**
 * An entity reference within a structured claim.
 * Kind must be one of the OWL entity types: class, object_property, data_property,
 * annotation_property, individual, or datatype.
 */
public record ClaimEntity(String kind, String iri) {

    public ClaimEntity {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("ClaimEntity kind must not be null or blank");
        }
        if (iri == null || iri.isBlank()) {
            throw new IllegalArgumentException("ClaimEntity iri must not be null or blank");
        }
    }
}