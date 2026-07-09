package org.owl4agents.core.model;

/**
 * OWL ObjectSomeValuesFrom: ∃property.filler. Maps to
 * {@code df.getOWLObjectSomeValuesFrom(prop, build(filler))}.
 */
public record ObjectSomeValuesFrom(String propertyIRI, ClassExpression filler) implements ClassExpression {

    public ObjectSomeValuesFrom {
        if (propertyIRI == null || propertyIRI.isBlank()) {
            throw new IllegalArgumentException("ObjectSomeValuesFrom propertyIRI must not be null or blank");
        }
        if (filler == null) {
            throw new IllegalArgumentException("ObjectSomeValuesFrom filler must not be null");
        }
    }
}
