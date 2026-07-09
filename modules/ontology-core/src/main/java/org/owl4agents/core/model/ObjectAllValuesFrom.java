package org.owl4agents.core.model;

/**
 * OWL ObjectAllValuesFrom: ∀property.filler. Maps to
 * {@code df.getOWLObjectAllValuesFrom(prop, build(filler))}.
 */
public record ObjectAllValuesFrom(String propertyIRI, ClassExpression filler) implements ClassExpression {

    public ObjectAllValuesFrom {
        if (propertyIRI == null || propertyIRI.isBlank()) {
            throw new IllegalArgumentException("ObjectAllValuesFrom propertyIRI must not be null or blank");
        }
        if (filler == null) {
            throw new IllegalArgumentException("ObjectAllValuesFrom filler must not be null");
        }
    }
}
