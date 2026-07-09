package org.owl4agents.core.model;

import java.util.List;

/**
 * OWL ObjectUnionOf: operand1 ⊔ operand2 ⊔ ... . Maps to
 * {@code df.getOWLObjectUnionOf(operands.map(build))}. Requires at
 * least 2 operands.
 */
public record ObjectUnionOf(List<ClassExpression> operands) implements ClassExpression {

    public ObjectUnionOf {
        if (operands == null || operands.size() < 2) {
            throw new IllegalArgumentException(
                "ObjectUnionOf requires at least 2 operands (got " +
                    (operands == null ? "null" : operands.size()) + ")");
        }
        operands = List.copyOf(operands);
    }
}
