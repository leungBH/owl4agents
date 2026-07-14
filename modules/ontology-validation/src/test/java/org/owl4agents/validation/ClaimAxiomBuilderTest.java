package org.owl4agents.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.model.NamedClass;
import org.owl4agents.core.model.ObjectIntersectionOf;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.apibinding.OWLManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClaimAxiomBuilder}.
 * Covers all 12 axiom-backed claim types, error handling, and complex
 * class expression delegation.
 */
@DisplayName("ClaimAxiomBuilder unit tests")
class ClaimAxiomBuilderTest {

    private OWLOntology ontology;
    private OWLDataFactory df;
    private ClaimAxiomBuilder builder;

    private static final String NS = "http://example.org/test#";
    private static final String CLASS_A = NS + "A";
    private static final String CLASS_B = NS + "B";
    private static final String CLASS_C = NS + "C";
    private static final String IND_X = NS + "x";
    private static final String IND_Y = NS + "y";
    private static final String OBJ_PROP_R = NS + "r";
    private static final String OBJ_PROP_S = NS + "s";
    private static final String DATA_PROP_P = NS + "p";
    private static final String DATATYPE_INT = "http://www.w3.org/2001/XMLSchema#integer";

    @BeforeEach
    void setUp() throws Exception {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        df = mgr.getOWLDataFactory();
        ontology = mgr.createOntology(IRI.create(NS));
        // Declare entities so IRI resolution succeeds
        ontology.add(df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(CLASS_A))));
        ontology.add(df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(CLASS_B))));
        ontology.add(df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(CLASS_C))));
        ontology.add(df.getOWLDeclarationAxiom(df.getOWLNamedIndividual(IRI.create(IND_X))));
        ontology.add(df.getOWLDeclarationAxiom(df.getOWLNamedIndividual(IRI.create(IND_Y))));
        ontology.add(df.getOWLDeclarationAxiom(df.getOWLObjectProperty(IRI.create(OBJ_PROP_R))));
        ontology.add(df.getOWLDeclarationAxiom(df.getOWLObjectProperty(IRI.create(OBJ_PROP_S))));
        ontology.add(df.getOWLDeclarationAxiom(df.getOWLDataProperty(IRI.create(DATA_PROP_P))));
        builder = new ClaimAxiomBuilder();
    }

    @SuppressWarnings("unchecked")
    private org.semanticweb.owlapi.model.OWLAxiom buildSuccess(Claim claim) {
        ServiceResult<org.semanticweb.owlapi.model.OWLAxiom> result = builder.build(ontology, claim);
        assertTrue(result.isSuccess(), "Expected success but got error: " +
            (result instanceof ServiceResult.Error ? ((ServiceResult.Error<?>) result).error().message() : ""));
        return ((ServiceResult.Success<org.semanticweb.owlapi.model.OWLAxiom>) result).data();
    }

    @SuppressWarnings("unchecked")
    private ErrorCode buildError(Claim claim) {
        ServiceResult<org.semanticweb.owlapi.model.OWLAxiom> result = builder.build(ontology, claim);
        assertFalse(result.isSuccess());
        return ((ServiceResult.Error<org.semanticweb.owlapi.model.OWLAxiom>) result).error().code();
    }

    // ── Positive tests for all 12 types ──

    @Nested
    @DisplayName("Positive axiom construction")
    class PositiveTests {

        @Test
        @DisplayName("SUBCLASS → SubClassOf(A, B)")
        void subClassOf() {
            Claim claim = new Claim("c1", ClaimType.SUBCLASS, NS,
                new ClaimEntity("class", CLASS_A), "subClassOf",
                new ClaimEntity("class", CLASS_B),
                Optional.empty(), Optional.empty(), Optional.empty());
            var axiom = buildSuccess(claim);
            assertTrue(axiom instanceof org.semanticweb.owlapi.model.OWLSubClassOfAxiom);
            assertEquals(IRI.create(CLASS_A), ((org.semanticweb.owlapi.model.OWLSubClassOfAxiom) axiom)
                .getSubClass().asOWLClass().getIRI());
            assertEquals(IRI.create(CLASS_B), ((org.semanticweb.owlapi.model.OWLSubClassOfAxiom) axiom)
                .getSuperClass().asOWLClass().getIRI());
        }

        @Test
        @DisplayName("EQUIVALENT_CLASSES → EquivalentClasses(A, B)")
        void equivalentClasses() {
            Claim claim = new Claim("c2", ClaimType.EQUIVALENT_CLASSES, NS,
                new ClaimEntity("class", CLASS_A), "equivalentTo",
                new ClaimEntity("class", CLASS_B),
                Optional.empty(), Optional.empty(), Optional.empty());
            var axiom = buildSuccess(claim);
            assertTrue(axiom instanceof org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom);
        }

        @Test
        @DisplayName("DISJOINT_CLASSES → DisjointClasses(A, B)")
        void disjointClasses() {
            Claim claim = new Claim("c3", ClaimType.DISJOINT_CLASSES, NS,
                new ClaimEntity("class", CLASS_A), "disjointWith",
                new ClaimEntity("class", CLASS_B),
                Optional.empty(), Optional.empty(), Optional.empty());
            var axiom = buildSuccess(claim);
            assertTrue(axiom instanceof org.semanticweb.owlapi.model.OWLDisjointClassesAxiom);
        }

        @Test
        @DisplayName("INDIVIDUAL_MEMBERSHIP → ClassAssertion(A, x)")
        void classAssertion() {
            Claim claim = new Claim("c4", ClaimType.INDIVIDUAL_MEMBERSHIP, NS,
                new ClaimEntity("individual", IND_X), "type",
                new ClaimEntity("class", CLASS_A),
                Optional.empty(), Optional.empty(), Optional.empty());
            var axiom = buildSuccess(claim);
            assertTrue(axiom instanceof org.semanticweb.owlapi.model.OWLClassAssertionAxiom);
        }

        @Test
        @DisplayName("OBJECT_PROPERTY_ASSERTION → ObjectPropertyAssertion(r, x, y)")
        void objectPropertyAssertion() {
            Claim claim = new Claim("c5", ClaimType.OBJECT_PROPERTY_ASSERTION, NS,
                new ClaimEntity("individual", IND_X), OBJ_PROP_R,
                new ClaimEntity("individual", IND_Y),
                Optional.empty(), Optional.empty(), Optional.empty());
            var axiom = buildSuccess(claim);
            assertTrue(axiom instanceof org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom);
        }

        @Test
        @DisplayName("DATA_PROPERTY_ASSERTION → DataPropertyAssertion(p, x, \"42\")")
        void dataPropertyAssertion() {
            Claim claim = new Claim("c6", ClaimType.DATA_PROPERTY_ASSERTION, NS,
                new ClaimEntity("individual", IND_X), DATA_PROP_P,
                new ClaimEntity("integer", "42"),
                Optional.empty(), Optional.empty(), Optional.empty());
            var axiom = buildSuccess(claim);
            assertTrue(axiom instanceof org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom);
        }

        @Test
        @DisplayName("OBJECT_PROPERTY_DOMAIN → ObjectPropertyDomain(r, A)")
        void objectPropertyDomain() {
            Claim claim = new Claim("c7", ClaimType.OBJECT_PROPERTY_DOMAIN, NS,
                new ClaimEntity("object_property", OBJ_PROP_R), "domain",
                new ClaimEntity("class", CLASS_A),
                Optional.empty(), Optional.empty(), Optional.empty());
            var axiom = buildSuccess(claim);
            assertTrue(axiom instanceof org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom);
        }

        @Test
        @DisplayName("OBJECT_PROPERTY_RANGE → ObjectPropertyRange(r, A)")
        void objectPropertyRange() {
            Claim claim = new Claim("c8", ClaimType.OBJECT_PROPERTY_RANGE, NS,
                new ClaimEntity("object_property", OBJ_PROP_R), "range",
                new ClaimEntity("class", CLASS_A),
                Optional.empty(), Optional.empty(), Optional.empty());
            var axiom = buildSuccess(claim);
            assertTrue(axiom instanceof org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom);
        }

        @Test
        @DisplayName("DATA_PROPERTY_DOMAIN → DataPropertyDomain(p, A)")
        void dataPropertyDomain() {
            Claim claim = new Claim("c9", ClaimType.DATA_PROPERTY_DOMAIN, NS,
                new ClaimEntity("data_property", DATA_PROP_P), "domain",
                new ClaimEntity("class", CLASS_A),
                Optional.empty(), Optional.empty(), Optional.empty());
            var axiom = buildSuccess(claim);
            assertTrue(axiom instanceof org.semanticweb.owlapi.model.OWLDataPropertyDomainAxiom);
        }

        @Test
        @DisplayName("DATA_PROPERTY_RANGE → DataPropertyRange(p, xsd:integer)")
        void dataPropertyRange() {
            // Add declaration for xsd:integer datatype so resolution succeeds
            ontology.add(df.getOWLDeclarationAxiom(df.getOWLDatatype(IRI.create(DATATYPE_INT))));
            Claim claim = new Claim("c10", ClaimType.DATA_PROPERTY_RANGE, NS,
                new ClaimEntity("data_property", DATA_PROP_P), "range",
                new ClaimEntity("datatype", DATATYPE_INT),
                Optional.empty(), Optional.empty(), Optional.empty());
            var axiom = buildSuccess(claim);
            assertTrue(axiom instanceof org.semanticweb.owlapi.model.OWLDataPropertyRangeAxiom);
        }

        @Test
        @DisplayName("DIFFERENT_INDIVIDUALS → DifferentIndividuals(x, y)")
        void differentIndividuals() {
            Claim claim = new Claim("c11", ClaimType.DIFFERENT_INDIVIDUALS, NS,
                new ClaimEntity("individual", IND_X), "differentFrom",
                new ClaimEntity("individual", IND_Y),
                Optional.empty(), Optional.empty(), Optional.empty());
            var axiom = buildSuccess(claim);
            assertTrue(axiom instanceof org.semanticweb.owlapi.model.OWLDifferentIndividualsAxiom);
        }

        @Test
        @DisplayName("OBJECT_PROPERTY_SUBPROPERTY → SubObjectPropertyOf(r, s)")
        void subObjectPropertyOf() {
            Claim claim = new Claim("c12", ClaimType.OBJECT_PROPERTY_SUBPROPERTY, NS,
                new ClaimEntity("object_property", OBJ_PROP_R), "subPropertyOf",
                new ClaimEntity("object_property", OBJ_PROP_S),
                Optional.empty(), Optional.empty(), Optional.empty());
            var axiom = buildSuccess(claim);
            assertTrue(axiom instanceof org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom);
        }
    }

    // ── Negative tests ──

    @Nested
    @DisplayName("Error handling")
    class ErrorTests {

        @Test
        @DisplayName("Missing subject → CLAIM_AXIOM_BUILD_FAILED")
        void missingSubject() {
            Claim claim = new Claim("e1", ClaimType.SUBCLASS, NS,
                null, "subClassOf",
                new ClaimEntity("class", CLASS_B),
                Optional.empty(), Optional.empty(), Optional.empty());
            assertEquals(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, buildError(claim));
        }

        @Test
        @DisplayName("Missing object → CLAIM_AXIOM_BUILD_FAILED")
        void missingObject() {
            Claim claim = new Claim("e2", ClaimType.SUBCLASS, NS,
                new ClaimEntity("class", CLASS_A), "subClassOf",
                null,
                Optional.empty(), Optional.empty(), Optional.empty());
            assertEquals(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, buildError(claim));
        }

        @Test
        @DisplayName("Invalid IRI → CLAIM_AXIOM_BUILD_FAILED")
        void invalidIri() {
            Claim claim = new Claim("e3", ClaimType.SUBCLASS, NS,
                new ClaimEntity("class", "http://nonexistent.org/Foo"), "subClassOf",
                new ClaimEntity("class", CLASS_B),
                Optional.empty(), Optional.empty(), Optional.empty());
            assertEquals(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, buildError(claim));
        }

        @Test
        @DisplayName("Kind mismatch (individual for class) → CLAIM_AXIOM_BUILD_FAILED")
        void kindMismatch() {
            Claim claim = new Claim("e4", ClaimType.SUBCLASS, NS,
                new ClaimEntity("individual", IND_X), "subClassOf",
                new ClaimEntity("class", CLASS_B),
                Optional.empty(), Optional.empty(), Optional.empty());
            assertEquals(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, buildError(claim));
        }

        @Test
        @DisplayName("Unsupported claim type → CLAIM_AXIOM_BUILD_FAILED")
        void unsupportedType() {
            Claim claim = new Claim("e5", ClaimType.ONTOLOGY_CONSISTENCY, NS,
                null, null, null,
                Optional.empty(), Optional.empty(), Optional.empty());
            assertEquals(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, buildError(claim));
        }

        @Test
        @DisplayName("Missing predicate for property assertion → CLAIM_AXIOM_BUILD_FAILED")
        void missingPredicate() {
            Claim claim = new Claim("e6", ClaimType.OBJECT_PROPERTY_ASSERTION, NS,
                new ClaimEntity("individual", IND_X), null,
                new ClaimEntity("individual", IND_Y),
                Optional.empty(), Optional.empty(), Optional.empty());
            assertEquals(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, buildError(claim));
        }
    }

    // ── Complex expression delegation ──

    @Nested
    @DisplayName("Complex class expression delegation")
    class ComplexExpressionTests {

        @Test
        @DisplayName("SUBCLASS with intersection expression on superclass")
        void subClassOfWithIntersection() {
            Claim claim = new Claim("cx1", ClaimType.SUBCLASS, NS,
                new ClaimEntity("class", CLASS_A), "subClassOf",
                new ClaimEntity("class_expression", null,
                    new ObjectIntersectionOf(
                        java.util.List.of(
                            new NamedClass(CLASS_B),
                            new NamedClass(CLASS_C)))),
                Optional.empty(), Optional.empty(), Optional.empty());
            var axiom = buildSuccess(claim);
            assertTrue(axiom instanceof org.semanticweb.owlapi.model.OWLSubClassOfAxiom);
            var subAx = (org.semanticweb.owlapi.model.OWLSubClassOfAxiom) axiom;
            assertTrue(subAx.getSuperClass() instanceof org.semanticweb.owlapi.model.OWLObjectIntersectionOf);
        }
    }
}
