package org.owl4agents.core.model;

/**
 * A named OWL class identified by IRI. Maps to {@code df.getOWLClass(IRI.create(iri))}.
 */
public record NamedClass(String iri) implements ClassExpression {

    public NamedClass {
        if (iri == null || iri.isBlank()) {
            throw new IllegalArgumentException("NamedClass iri must not be null or blank");
        }
    }
}
