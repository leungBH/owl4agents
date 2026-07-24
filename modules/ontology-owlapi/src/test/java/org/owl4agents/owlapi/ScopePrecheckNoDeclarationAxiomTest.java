package org.owl4agents.owlapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.9.0 D6 / task 7.7: Verifies that an entity referenced in axioms (e.g.,
 * SubClassOf) but without an explicit Declaration axiom passes the scope
 * pre-check. Per OWL 2 spec, an entity referenced in any axiom is part of
 * the ontology signature, even without a Declaration axiom.
 *
 * <p>This is the D6 BREAKING change: the old spec required a dual check
 * (entity in signature AND has Declaration axiom). The code already
 * implemented OR logic (signature OR Declaration); the spec was corrected
 * to match. This test verifies the OR logic works correctly.</p>
 */
@DisplayName("v0.9.0 D6 / task 7.7: No-Declaration entity passes scope pre-check")
class ScopePrecheckNoDeclarationAxiomTest {

    private static final String TEST_NS = "http://example.org/no-decl-test#";

    @Test
    @DisplayName("Entity in SubClassOf axiom (no Declaration) is found in cache")
    void entityInAxiomWithoutDeclarationIsFound() {
        OWLOntology ontology = buildOntologyWithAxiomsOnly();
        assertEquals(0, ontology.getAxioms(org.semanticweb.owlapi.model.AxiomType.DECLARATION, Imports.EXCLUDED).size(),
            "Test ontology must have zero Declaration axioms");

        EntitySignatureCache cache = EntitySignatureCache.build(ontology);

        // Class A and B are referenced in SubClassOf but have no Declaration.
        assertTrue(cache.contains("class", TEST_NS + "A"),
            "Class A (in SubClassOf, no Declaration) must be found in cache");
        assertTrue(cache.contains("class", TEST_NS + "B"),
            "Class B (in SubClassOf, no Declaration) must be found in cache");

        // Individual X is referenced in ClassAssertion but has no Declaration.
        assertTrue(cache.contains("individual", TEST_NS + "X"),
            "Individual X (in ClassAssertion, no Declaration) must be found in cache");

        // Object property P is referenced in ObjectPropertyAssertion but has no Declaration.
        assertTrue(cache.contains("object_property", TEST_NS + "P"),
            "Object property P (in ObjectPropertyAssertion, no Declaration) must be found in cache");
    }

    @Test
    @DisplayName("Entity not in any axiom is NOT found in cache")
    void entityNotInAnyAxiomIsNotFound() {
        OWLOntology ontology = buildOntologyWithAxiomsOnly();
        EntitySignatureCache cache = EntitySignatureCache.build(ontology);

        assertTrue(!cache.contains("class", TEST_NS + "NonExistent"),
            "Non-existent class must NOT be found in cache");
    }

    /**
     * Build an ontology with entities referenced in axioms but NO Declaration axioms.
     * Per OWL 2 spec, these entities are part of the ontology signature.
     */
    private OWLOntology buildOntologyWithAxiomsOnly() {
        try {
            OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
            OWLDataFactory df = mgr.getOWLDataFactory();
            OWLOntology ontology = mgr.createOntology(IRI.create(TEST_NS + "ontology"));

            OWLClass clsA = df.getOWLClass(IRI.create(TEST_NS + "A"));
            OWLClass clsB = df.getOWLClass(IRI.create(TEST_NS + "B"));
            OWLNamedIndividual indX = df.getOWLNamedIndividual(IRI.create(TEST_NS + "X"));
            OWLObjectProperty propP = df.getOWLObjectProperty(IRI.create(TEST_NS + "P"));

            // Add axioms that reference entities WITHOUT any Declaration axioms.
            ontology.add(df.getOWLSubClassOfAxiom(clsA, clsB));
            ontology.add(df.getOWLClassAssertionAxiom(clsA, indX));
            ontology.add(df.getOWLObjectPropertyAssertionAxiom(propP, indX, indX));

            return ontology;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
