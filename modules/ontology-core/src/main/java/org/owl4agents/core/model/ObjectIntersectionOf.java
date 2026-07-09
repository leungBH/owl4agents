package org.owl4agents.core.model;

import java.util.List;

/**
 * OWL ObjectIntersectionOf: operand1 ⊓ operand2 ⊓ ... . Maps to
 * {@code df.getOWLObjectIntersectionOf(operands.map(build))}. Requires at
 * least 2 operands.
 */
public record ObjectIntersectionOf(List<ClassExpression> operands) implements ClassExpression {

    public ObjectIntersectionOf {
        if (operands == null || operands.size() < 2) {
            throw new IllegalArgumentException(
                "ObjectIntersectionOf requires at least 2 operands (got " +
                    (operands == null ? "null" : operands.size()) + ")");
        }
        operands = List.copyOf(operands);
    }
}
