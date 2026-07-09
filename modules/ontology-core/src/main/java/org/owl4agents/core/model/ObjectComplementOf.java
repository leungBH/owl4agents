package org.owl4agents.core.model;

/**
 * OWL ObjectComplementOf: ¬operand. Maps to {@code df.getOWLObjectComplementOf(build(operand))}.
 */
public record ObjectComplementOf(ClassExpression operand) implements ClassExpression {

    public ObjectComplementOf {
        if (operand == null) {
            throw new IllegalArgumentException("ObjectComplementOf operand must not be null");
        }
    }
}
