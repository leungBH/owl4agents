package org.owl4agents.core.model;

/**
 * An entity reference within a structured claim.
 * Kind must be one of the OWL entity types: class, object_property, data_property,
 * annotation_property, individual, or datatype.
 *
 * <p>v0.8.1: extended with an optional {@code expression} field for complex
 * class expressions (see {@link ClassExpression}). A claim entity refers to
 * either a named entity ({@code iri} is non-null) or a complex class
 * expression ({@code expression} is non-null). At least one of {@code iri} or
 * {@code expression} MUST be non-null; the compact constructor enforces this.
 * The two-argument constructor remains for backward compatibility — v0.8.0
 * clients that send only {@code kind} + {@code iri} continue to parse.</p>
 */
public record ClaimEntity(String kind, String iri, ClassExpression expression) {

    public ClaimEntity {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("ClaimEntity kind must not be null or blank");
        }
        if ((iri == null || iri.isBlank()) && expression == null) {
            throw new IllegalArgumentException(
                "ClaimEntity must have either a non-blank iri or a non-null expression");
        }
    }

    /**
     * Backward-compatible two-argument constructor. Sets {@code expression = null}.
     */
    public ClaimEntity(String kind, String iri) {
        this(kind, iri, null);
    }
}