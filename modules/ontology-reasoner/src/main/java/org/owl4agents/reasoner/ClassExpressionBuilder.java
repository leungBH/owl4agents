package org.owl4agents.reasoner;

import org.owl4agents.core.model.ClassExpression;
import org.owl4agents.core.model.NamedClass;
import org.owl4agents.core.model.ObjectAllValuesFrom;
import org.owl4agents.core.model.ObjectComplementOf;
import org.owl4agents.core.model.ObjectIntersectionOf;
import org.owl4agents.core.model.ObjectSomeValuesFrom;
import org.owl4agents.core.model.ObjectUnionOf;
import org.owl4agents.owlapi.OntologyIriResolver;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;

/**
 * v0.8.1 ISSUE-03: Build OWL API {@link org.semanticweb.owlapi.model.OWLClassExpression}
 * instances from a {@link ClassExpression} sealed-interface record.
 *
 * <p>Used by {@code ClaimVerificationService} when an {@code equivalent_classes}
 * claim has a complex expression on either side. Delegates IRI resolution to
 * {@link OntologyIriResolver} (per the v0.8.1 refactor that moved the resolver
 * into the {@code ontology-owlapi} module to avoid forcing OWL API on
 * {@code ontology-core} consumers).
 *
 * <p>Throws {@link EntityNotFoundException} (mapped to {@code ENTITY_NOT_FOUND})
 * when an IRI cannot be resolved. Throws {@link IllegalArgumentException} (mapped
 * to {@code INVALID_CLAIM_SCHEMA}) when the expression tree depth exceeds
 * {@link ClassExpression#MAX_NESTING_DEPTH} (v0.8.1 hard limit of 3).
 */
public final class ClassExpressionBuilder {

    /** Thrown when an IRI inside an expression cannot be resolved. */
    public static final class EntityNotFoundException extends RuntimeException {
        public EntityNotFoundException(String message) {
            super(message);
        }
    }

    /** Thrown when the expression tree depth exceeds {@link ClassExpression#MAX_NESTING_DEPTH}. */
    public static final class ExpressionTooDeepException extends RuntimeException {
        public ExpressionTooDeepException(String message) {
            super(message);
        }
    }

    private ClassExpressionBuilder() {
        // utility class
    }

    /**
     * Build an OWL API class expression from a v0.8.1 {@link ClassExpression}.
     *
     * @param expr     the expression to convert
     * @param ontology the ontology to resolve IRIs against
     * @param df       OWL data factory
     * @return the OWL API class expression
     * @throws EntityNotFoundException      when an IRI cannot be resolved
     * @throws ExpressionTooDeepException   when the tree depth exceeds 3
     * @throws IllegalArgumentException     when a record field is missing or
     *                                      the expression is otherwise malformed
     */
    public static org.semanticweb.owlapi.model.OWLClassExpression build(
            ClassExpression expr,
            org.semanticweb.owlapi.model.OWLOntology ontology,
            OWLDataFactory df) {
        return buildRecursive(expr, ontology, df, 0);
    }

    private static org.semanticweb.owlapi.model.OWLClassExpression buildRecursive(
            ClassExpression expr,
            org.semanticweb.owlapi.model.OWLOntology ontology,
            OWLDataFactory df,
            int depth) {

        if (depth > ClassExpression.MAX_NESTING_DEPTH) {
            throw new ExpressionTooDeepException(
                "ClassExpression nesting depth exceeds " + ClassExpression.MAX_NESTING_DEPTH
                    + " (v0.8.1 hard limit). The 6 supported types are: "
                    + "named, existential, universal, intersection, union, complement. "
                    + "Data-property and cardinality constructors are deferred to v0.9+.");
        }

        if (expr instanceof NamedClass nc) {
            String iri = nc.iri();
            if (iri == null || iri.isBlank()) {
                throw new IllegalArgumentException("NamedClass.iri must not be null or blank");
            }
            IRI resolved = OntologyIriResolver.resolveOntologyIRI(ontology, iri, "class");
            if (resolved == null) {
                throw new EntityNotFoundException("Cannot resolve class IRI: " + iri);
            }
            return df.getOWLClass(resolved);
        }

        if (expr instanceof ObjectSomeValuesFrom svf) {
            String propIri = svf.propertyIRI();
            if (propIri == null || propIri.isBlank()) {
                throw new IllegalArgumentException(
                    "ObjectSomeValuesFrom.propertyIRI must not be null or blank");
            }
            if (svf.filler() == null) {
                throw new IllegalArgumentException(
                    "ObjectSomeValuesFrom.filler must not be null");
            }
            IRI resolvedProp = OntologyIriResolver.resolveOntologyIRI(ontology, propIri, "object_property");
            if (resolvedProp == null) {
                throw new EntityNotFoundException("Cannot resolve object property IRI: " + propIri);
            }
            OWLObjectProperty prop = df.getOWLObjectProperty(resolvedProp);
            var fillerExpr = buildRecursive(svf.filler(), ontology, df, depth + 1);
            return df.getOWLObjectSomeValuesFrom(prop, fillerExpr);
        }

        if (expr instanceof ObjectAllValuesFrom avf) {
            String propIri = avf.propertyIRI();
            if (propIri == null || propIri.isBlank()) {
                throw new IllegalArgumentException(
                    "ObjectAllValuesFrom.propertyIRI must not be null or blank");
            }
            if (avf.filler() == null) {
                throw new IllegalArgumentException(
                    "ObjectAllValuesFrom.filler must not be null");
            }
            IRI resolvedProp = OntologyIriResolver.resolveOntologyIRI(ontology, propIri, "object_property");
            if (resolvedProp == null) {
                throw new EntityNotFoundException("Cannot resolve object property IRI: " + propIri);
            }
            OWLObjectProperty prop = df.getOWLObjectProperty(resolvedProp);
            var fillerExpr = buildRecursive(avf.filler(), ontology, df, depth + 1);
            return df.getOWLObjectAllValuesFrom(prop, fillerExpr);
        }

        if (expr instanceof ObjectIntersectionOf ioo) {
            var operands = ioo.operands();
            if (operands == null || operands.size() < 2) {
                throw new IllegalArgumentException(
                    "ObjectIntersectionOf.operands must contain at least 2 elements, got: "
                        + (operands == null ? "null" : operands.size()));
            }
            var built = operands.stream()
                .map(o -> buildRecursive(o, ontology, df, depth + 1))
                .toList();
            return df.getOWLObjectIntersectionOf(built);
        }

        if (expr instanceof ObjectUnionOf uoo) {
            var operands = uoo.operands();
            if (operands == null || operands.size() < 2) {
                throw new IllegalArgumentException(
                    "ObjectUnionOf.operands must contain at least 2 elements, got: "
                        + (operands == null ? "null" : operands.size()));
            }
            var built = operands.stream()
                .map(o -> buildRecursive(o, ontology, df, depth + 1))
                .toList();
            return df.getOWLObjectUnionOf(built);
        }

        if (expr instanceof ObjectComplementOf cof) {
            if (cof.operand() == null) {
                throw new IllegalArgumentException(
                    "ObjectComplementOf.operand must not be null");
            }
            var inner = buildRecursive(cof.operand(), ontology, df, depth + 1);
            return df.getOWLObjectComplementOf(inner);
        }

        throw new IllegalArgumentException(
            "Unknown ClassExpression subtype: " + (expr == null ? "null" : expr.getClass().getName())
                + ". Supported types: named, existential, universal, intersection, union, complement. "
                + "Data-property and cardinality constructors are deferred to v0.9+.");
    }
}
