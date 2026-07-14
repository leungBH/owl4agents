package org.owl4agents.validation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ResultMetadata;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.owlapi.OntologyIriResolver;
import org.owl4agents.reasoner.ClassExpressionBuilder;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataPropertyExpression;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * v0.8.5 exact-consistency-verification: Builds a single {@link OWLAxiom} from
 * a structured {@link Claim}. The same axiom instance is used for both the
 * entailment check (stage 3) and the exact consistency check (stage 4),
 * guaranteeing reference equality between the two (design D2).
 *
 * <p>Supports 12 axiom-backed claim types. The 4 special types
 * ({@code ONTOLOGY_CONSISTENCY}, {@code ONTOLOGY_SCOPE}, {@code LITERAL_VALIDITY},
 * {@code CLASS_COMPATIBILITY}) do not construct a single claim axiom and are
 * handled by custom dispatch in {@code ClaimVerificationService}.
 *
 * <p>Uses a strategy registry (design task 3.7) so that future claim types
 * (v1.0.0) can be registered without modifying this class.
 */
public final class ClaimAxiomBuilder {

    private final Map<ClaimType, AxiomBuilderStrategy> strategies = new ConcurrentHashMap<>();

    public ClaimAxiomBuilder() {
        register(ClaimType.SUBCLASS, this::buildSubClassOf);
        register(ClaimType.EQUIVALENT_CLASSES, this::buildEquivalentClasses);
        register(ClaimType.DISJOINT_CLASSES, this::buildDisjointClasses);
        register(ClaimType.INDIVIDUAL_MEMBERSHIP, this::buildClassAssertion);
        register(ClaimType.OBJECT_PROPERTY_ASSERTION, this::buildObjectPropertyAssertion);
        register(ClaimType.DATA_PROPERTY_ASSERTION, this::buildDataPropertyAssertion);
        register(ClaimType.OBJECT_PROPERTY_DOMAIN, this::buildObjectPropertyDomain);
        register(ClaimType.OBJECT_PROPERTY_RANGE, this::buildObjectPropertyRange);
        register(ClaimType.DATA_PROPERTY_DOMAIN, this::buildDataPropertyDomain);
        register(ClaimType.DATA_PROPERTY_RANGE, this::buildDataPropertyRange);
        register(ClaimType.DIFFERENT_INDIVIDUALS, this::buildDifferentIndividuals);
        register(ClaimType.OBJECT_PROPERTY_SUBPROPERTY, this::buildSubObjectPropertyOf);
    }

    /**
     * Register a custom strategy for a claim type, overriding the default.
     * Enables v1.0.0 extension without modifying this class.
     */
    public void register(ClaimType type, AxiomBuilderStrategy strategy) {
        strategies.put(type, strategy);
    }

    /**
     * Build the OWL axiom from a structured claim.
     *
     * @param ontology the source ontology (for IRI resolution and data factory)
     * @param claim    the structured claim
     * @return {@code ServiceResult.success(axiom)} or
     *         {@code ServiceResult.error(CLAIM_AXIOM_BUILD_FAILED, ...)}
     */
    public ServiceResult<OWLAxiom> build(OWLOntology ontology, Claim claim) {
        return build(ontology, claim, claim.type());
    }

    /**
     * Build the OWL axiom from a structured claim using an effective claim type
     * that may differ from {@code claim.type()}. Used by
     * {@code ClaimVerificationService} for kind-based dispatch (e.g.,
     * individual-level DISJOINT_CLASSES claims are built as DifferentIndividuals
     * axioms; OBJECT_PROPERTY_ASSERTION claims with {@code subPropertyOf}
     * predicate are built as SubObjectPropertyOf axioms).
     *
     * @param ontology      the source ontology (for IRI resolution and data factory)
     * @param claim         the structured claim
     * @param effectiveType the claim type to use for strategy dispatch
     * @return {@code ServiceResult.success(axiom)} or
     *         {@code ServiceResult.error(CLAIM_AXIOM_BUILD_FAILED, ...)}
     */
    public ServiceResult<OWLAxiom> build(OWLOntology ontology, Claim claim, ClaimType effectiveType) {
        if (ontology == null || claim == null) {
            return ServiceResult.error(ErrorCode.CLAIM_AXIOM_BUILD_FAILED,
                "ontology and claim must not be null");
        }
        AxiomBuilderStrategy strategy = strategies.get(effectiveType);
        if (strategy == null) {
            return ServiceResult.error(ErrorCode.CLAIM_AXIOM_BUILD_FAILED,
                "No axiom builder strategy registered for claim type: " + effectiveType);
        }
        return strategy.build(ontology, claim);
    }

    // ── Strategy implementations ──

    private ServiceResult<OWLAxiom> buildSubClassOf(OWLOntology ontology, Claim claim) {
        if (claim.subject() == null || claim.object() == null) {
            return missingField("SubClassOf requires subject and object");
        }
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        try {
            OWLClassExpression sub = resolveClassExpression(claim.subject(), ontology, df);
            OWLClassExpression sup = resolveClassExpression(claim.object(), ontology, df);
            return ServiceResult.success(df.getOWLSubClassOfAxiom(sub, sup), ResultMetadata.empty());
        } catch (AxiomBuildException e) {
            return ServiceResult.error(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, e.getMessage());
        }
    }

    private ServiceResult<OWLAxiom> buildEquivalentClasses(OWLOntology ontology, Claim claim) {
        if (claim.subject() == null || claim.object() == null) {
            return missingField("EquivalentClasses requires subject and object");
        }
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        try {
            OWLClassExpression left = resolveClassExpression(claim.subject(), ontology, df);
            OWLClassExpression right = resolveClassExpression(claim.object(), ontology, df);
            return ServiceResult.success(df.getOWLEquivalentClassesAxiom(left, right), ResultMetadata.empty());
        } catch (AxiomBuildException e) {
            return ServiceResult.error(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, e.getMessage());
        }
    }

    private ServiceResult<OWLAxiom> buildDisjointClasses(OWLOntology ontology, Claim claim) {
        if (claim.subject() == null || claim.object() == null) {
            return missingField("DisjointClasses requires subject and object");
        }
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        try {
            OWLClassExpression left = resolveClassExpression(claim.subject(), ontology, df);
            OWLClassExpression right = resolveClassExpression(claim.object(), ontology, df);
            return ServiceResult.success(df.getOWLDisjointClassesAxiom(left, right), ResultMetadata.empty());
        } catch (AxiomBuildException e) {
            return ServiceResult.error(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, e.getMessage());
        }
    }

    private ServiceResult<OWLAxiom> buildClassAssertion(OWLOntology ontology, Claim claim) {
        if (claim.subject() == null || claim.object() == null) {
            return missingField("ClassAssertion requires subject (individual) and object (class)");
        }
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        try {
            OWLNamedIndividual ind = resolveIndividual(claim.subject(), ontology);
            OWLClassExpression cls = resolveClassExpression(claim.object(), ontology, df);
            return ServiceResult.success(df.getOWLClassAssertionAxiom(cls, ind), ResultMetadata.empty());
        } catch (AxiomBuildException e) {
            return ServiceResult.error(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, e.getMessage());
        }
    }

    private ServiceResult<OWLAxiom> buildObjectPropertyAssertion(OWLOntology ontology, Claim claim) {
        if (claim.subject() == null || claim.object() == null) {
            return missingField("ObjectPropertyAssertion requires subject and object individuals");
        }
        String propIRI = claim.predicate();
        if (propIRI == null || propIRI.isBlank()) {
            return missingField("ObjectPropertyAssertion requires predicate (property IRI)");
        }
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        try {
            OWLNamedIndividual subj = resolveIndividual(claim.subject(), ontology);
            OWLNamedIndividual obj = resolveIndividual(claim.object(), ontology);
            IRI propIri = resolveIRI(ontology, propIRI, "object_property");
            if (propIri == null) {
                throw new AxiomBuildException("Cannot resolve object property IRI: " + propIRI);
            }
            OWLObjectProperty prop = df.getOWLObjectProperty(propIri);
            return ServiceResult.success(df.getOWLObjectPropertyAssertionAxiom(prop, subj, obj), ResultMetadata.empty());
        } catch (AxiomBuildException e) {
            return ServiceResult.error(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, e.getMessage());
        }
    }

    private ServiceResult<OWLAxiom> buildDataPropertyAssertion(OWLOntology ontology, Claim claim) {
        if (claim.subject() == null || claim.object() == null) {
            return missingField("DataPropertyAssertion requires subject (individual) and object (literal)");
        }
        String propIRI = claim.predicate();
        if (propIRI == null || propIRI.isBlank()) {
            return missingField("DataPropertyAssertion requires predicate (property IRI)");
        }
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        try {
            OWLNamedIndividual subj = resolveIndividual(claim.subject(), ontology);
            IRI propIri = resolveIRI(ontology, propIRI, "data_property");
            if (propIri == null) {
                throw new AxiomBuildException("Cannot resolve data property IRI: " + propIRI);
            }
            OWLDataProperty prop = df.getOWLDataProperty(propIri);
            OWLLiteral literal = parseLiteral(claim.object(), df);
            return ServiceResult.success(df.getOWLDataPropertyAssertionAxiom(prop, subj, literal), ResultMetadata.empty());
        } catch (AxiomBuildException e) {
            return ServiceResult.error(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, e.getMessage());
        }
    }

    private ServiceResult<OWLAxiom> buildObjectPropertyDomain(OWLOntology ontology, Claim claim) {
        if (claim.subject() == null || claim.object() == null) {
            return missingField("ObjectPropertyDomain requires subject (property) and object (class)");
        }
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        try {
            IRI propIri = resolveIRI(ontology, claim.subject().iri(), "object_property");
            if (propIri == null) {
                throw new AxiomBuildException("Cannot resolve object property IRI: " + claim.subject().iri());
            }
            OWLObjectProperty prop = df.getOWLObjectProperty(propIri);
            OWLClassExpression domain = resolveClassExpression(claim.object(), ontology, df);
            return ServiceResult.success(df.getOWLObjectPropertyDomainAxiom(prop, domain), ResultMetadata.empty());
        } catch (AxiomBuildException e) {
            return ServiceResult.error(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, e.getMessage());
        }
    }

    private ServiceResult<OWLAxiom> buildObjectPropertyRange(OWLOntology ontology, Claim claim) {
        if (claim.subject() == null || claim.object() == null) {
            return missingField("ObjectPropertyRange requires subject (property) and object (class)");
        }
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        try {
            IRI propIri = resolveIRI(ontology, claim.subject().iri(), "object_property");
            if (propIri == null) {
                throw new AxiomBuildException("Cannot resolve object property IRI: " + claim.subject().iri());
            }
            OWLObjectProperty prop = df.getOWLObjectProperty(propIri);
            OWLClassExpression range = resolveClassExpression(claim.object(), ontology, df);
            return ServiceResult.success(df.getOWLObjectPropertyRangeAxiom(prop, range), ResultMetadata.empty());
        } catch (AxiomBuildException e) {
            return ServiceResult.error(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, e.getMessage());
        }
    }

    private ServiceResult<OWLAxiom> buildDataPropertyDomain(OWLOntology ontology, Claim claim) {
        if (claim.subject() == null || claim.object() == null) {
            return missingField("DataPropertyDomain requires subject (property) and object (class)");
        }
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        try {
            IRI propIri = resolveIRI(ontology, claim.subject().iri(), "data_property");
            if (propIri == null) {
                throw new AxiomBuildException("Cannot resolve data property IRI: " + claim.subject().iri());
            }
            OWLDataProperty prop = df.getOWLDataProperty(propIri);
            OWLClassExpression domain = resolveClassExpression(claim.object(), ontology, df);
            return ServiceResult.success(df.getOWLDataPropertyDomainAxiom(prop, domain), ResultMetadata.empty());
        } catch (AxiomBuildException e) {
            return ServiceResult.error(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, e.getMessage());
        }
    }

    private ServiceResult<OWLAxiom> buildDataPropertyRange(OWLOntology ontology, Claim claim) {
        if (claim.subject() == null || claim.object() == null) {
            return missingField("DataPropertyRange requires subject (property) and object (datatype)");
        }
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        try {
            IRI propIri = resolveIRI(ontology, claim.subject().iri(), "data_property");
            if (propIri == null) {
                throw new AxiomBuildException("Cannot resolve data property IRI: " + claim.subject().iri());
            }
            OWLDataProperty prop = df.getOWLDataProperty(propIri);
            IRI dtIri = resolveIRI(ontology, claim.object().iri(), "datatype");
            if (dtIri == null) {
                throw new AxiomBuildException("Cannot resolve datatype IRI: " + claim.object().iri());
            }
            OWLDatatype datatype = df.getOWLDatatype(dtIri);
            return ServiceResult.success(df.getOWLDataPropertyRangeAxiom(prop, datatype), ResultMetadata.empty());
        } catch (AxiomBuildException e) {
            return ServiceResult.error(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, e.getMessage());
        }
    }

    private ServiceResult<OWLAxiom> buildDifferentIndividuals(OWLOntology ontology, Claim claim) {
        if (claim.subject() == null || claim.object() == null) {
            return missingField("DifferentIndividuals requires subject and object individuals");
        }
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        try {
            OWLNamedIndividual ind1 = resolveIndividual(claim.subject(), ontology);
            OWLNamedIndividual ind2 = resolveIndividual(claim.object(), ontology);
            return ServiceResult.success(df.getOWLDifferentIndividualsAxiom(ind1, ind2), ResultMetadata.empty());
        } catch (AxiomBuildException e) {
            return ServiceResult.error(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, e.getMessage());
        }
    }

    private ServiceResult<OWLAxiom> buildSubObjectPropertyOf(OWLOntology ontology, Claim claim) {
        if (claim.subject() == null || claim.object() == null) {
            return missingField("SubObjectPropertyOf requires subject and object properties");
        }
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        try {
            IRI subIri = resolveIRI(ontology, claim.subject().iri(), "object_property");
            IRI supIri = resolveIRI(ontology, claim.object().iri(), "object_property");
            if (subIri == null) {
                throw new AxiomBuildException("Cannot resolve sub-property IRI: " + claim.subject().iri());
            }
            if (supIri == null) {
                throw new AxiomBuildException("Cannot resolve super-property IRI: " + claim.object().iri());
            }
            OWLObjectProperty subProp = df.getOWLObjectProperty(subIri);
            OWLObjectProperty supProp = df.getOWLObjectProperty(supIri);
            return ServiceResult.success(df.getOWLSubObjectPropertyOfAxiom(subProp, supProp), ResultMetadata.empty());
        } catch (AxiomBuildException e) {
            return ServiceResult.error(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, e.getMessage());
        }
    }

    // ── Helpers ──

    private OWLClassExpression resolveClassExpression(ClaimEntity entity, OWLOntology ontology, OWLDataFactory df)
            throws AxiomBuildException {
        if (entity.expression() != null) {
            try {
                return ClassExpressionBuilder.build(entity.expression(), ontology, df);
            } catch (ClassExpressionBuilder.EntityNotFoundException e) {
                throw new AxiomBuildException("Entity not found in class expression: " + e.getMessage());
            } catch (ClassExpressionBuilder.ExpressionTooDeepException e) {
                throw new AxiomBuildException("Class expression too deep: " + e.getMessage());
            } catch (IllegalArgumentException e) {
                throw new AxiomBuildException("Invalid class expression: " + e.getMessage());
            }
        }
        String kind = entity.kind() == null ? "" : entity.kind().toLowerCase();
        if (!kind.isEmpty() && !kind.equals("class") && !kind.equals("class_expression")) {
            throw new AxiomBuildException(
                "Kind mismatch: expected 'class' or 'class_expression' but got '" + entity.kind() + "'");
        }
        IRI iri = resolveIRI(ontology, entity.iri(), "class");
        if (iri == null) {
            throw new AxiomBuildException("Cannot resolve class IRI: " + entity.iri());
        }
        return df.getOWLClass(iri);
    }

    private OWLNamedIndividual resolveIndividual(ClaimEntity entity, OWLOntology ontology)
            throws AxiomBuildException {
        String kind = entity.kind() == null ? "" : entity.kind().toLowerCase();
        if (!kind.isEmpty() && !kind.equals("individual") && !kind.equals("named_individual")) {
            throw new AxiomBuildException(
                "Kind mismatch: expected 'individual' but got '" + entity.kind() + "'");
        }
        IRI iri = resolveIRI(ontology, entity.iri(), "individual");
        if (iri == null) {
            throw new AxiomBuildException("Cannot resolve individual IRI: " + entity.iri());
        }
        return ontology.getOWLOntologyManager().getOWLDataFactory().getOWLNamedIndividual(iri);
    }

    private IRI resolveIRI(OWLOntology ontology, String iri, String entityType) {
        return OntologyIriResolver.resolveOntologyIRI(ontology, iri, entityType);
    }

    private OWLLiteral parseLiteral(ClaimEntity literalEntity, OWLDataFactory df)
            throws AxiomBuildException {
        String value = literalEntity.iri();
        if (value == null || value.isBlank()) {
            throw new AxiomBuildException("Literal value must not be blank");
        }
        String kind = literalEntity.kind() == null ? "" : literalEntity.kind().toLowerCase();
        // If kind indicates a datatype, try to parse accordingly
        if (kind.contains("integer") || kind.contains("int")) {
            try {
                return df.getOWLLiteral(Integer.parseInt(value));
            } catch (NumberFormatException e) {
                throw new AxiomBuildException("Cannot parse '" + value + "' as integer");
            }
        }
        if (kind.contains("boolean")) {
            if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
                return df.getOWLLiteral(true);
            }
            if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
                return df.getOWLLiteral(false);
            }
            throw new AxiomBuildException("Cannot parse '" + value + "' as boolean");
        }
        if (kind.contains("decimal") || kind.contains("double") || kind.contains("float")) {
            try {
                return df.getOWLLiteral(Double.parseDouble(value));
            } catch (NumberFormatException e) {
                throw new AxiomBuildException("Cannot parse '" + value + "' as decimal/double");
            }
        }
        // Default: treat as string literal
        return df.getOWLLiteral(value);
    }

    private ServiceResult<OWLAxiom> missingField(String message) {
        return ServiceResult.error(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, message);
    }

    private static class AxiomBuildException extends Exception {
        AxiomBuildException(String message) {
            super(message);
        }
    }
}
