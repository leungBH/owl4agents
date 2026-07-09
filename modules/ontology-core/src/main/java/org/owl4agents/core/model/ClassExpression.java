package org.owl4agents.core.model;

import java.util.List;

/**
 * Sealed interface for complex OWL class expressions used in v0.8.1 claim
 * verification (ISSUE-03). Permits 6 record implementations covering the
 * object-property-side class constructors. Data-property-side class
 * constructors (DataSomeValuesFrom, DataAllValuesFrom, CardinalityRestriction,
 * DataIntersectionOf) and cardinality restrictions are NOT supported in
 * v0.8.1; see {@code ComplexClassExpressionTest} for the rejection behavior.
 *
 * <p>The v0.8.1 JSON schema for {@code object.expression} is:</p>
 * <ul>
 *   <li>{@code "named"} → {@link NamedClass} (requires {@code iri})</li>
 *   <li>{@code "existential"} → {@link ObjectSomeValuesFrom} (requires {@code property} and {@code filler})</li>
 *   <li>{@code "universal"} → {@link ObjectAllValuesFrom} (requires {@code property} and {@code filler})</li>
 *   <li>{@code "intersection"} → {@link ObjectIntersectionOf} (requires {@code operands} with at least 2 elements)</li>
 *   <li>{@code "union"} → {@link ObjectUnionOf} (requires {@code operands} with at least 2 elements)</li>
 *   <li>{@code "complement"} → {@link ObjectComplementOf} (requires {@code operand})</li>
 * </ul>
 */
public sealed interface ClassExpression
    permits NamedClass,
            ObjectSomeValuesFrom,
            ObjectAllValuesFrom,
            ObjectIntersectionOf,
            ObjectUnionOf,
            ObjectComplementOf {

    /** Maximum allowed nesting depth. v0.8.1 enforces a hard limit of 3. */
    int MAX_NESTING_DEPTH = 3;
}
