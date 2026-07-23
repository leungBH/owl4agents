package org.owl4agents.reasoner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.8 task 7.3: Unit tests for {@link ELKAdapter#isSatisfiable(org.semanticweb.owlapi.model.OWLClassExpression)}.
 *
 * <p>ELK is an OWL 2 EL reasoner. The satisfiability operation is supported
 * for OWL 2 EL class expressions. These tests use simple EL ontologies
 * (subclass + disjointness, both expressible in OWL 2 EL).
 *
 * <p>Scenarios:
 * <ol>
 *   <li>OWL 2 EL satisfiable class returns true</li>
 *   <li>OWL 2 EL unsatisfiable class returns false (disjoint classes)</li>
 *   <li>Shutdown throws IllegalStateException</li>
 * </ol>
 */
@DisplayName("v0.8.8 7.3: ELKAdapter isSatisfiable")
class ELKAdapterSatisfiabilityTest {

    private static final String TEST_NS = "http://owl4agents.org/test/elk-sat#";

    private OWLDataFactory df;

    @BeforeEach
    void setUp() {
        df = OWLManager.getOWLDataFactory();
    }

    /**
     * Build a simple OWL 2 EL ontology where C is satisfiable:
     *   Declaration(C), Declaration(D), SubClassOf(D, C)
     * (All axioms are in OWL 2 EL profile.)
     */
    private OWLOntology buildSatisfiableElOntology() throws Exception {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = mgr.getOWLDataFactory();
        OWLOntology ont = mgr.createOntology(IRI.create(TEST_NS + "sat"));
        OWLClass c = factory.getOWLClass(IRI.create(TEST_NS + "C"));
        OWLClass d = factory.getOWLClass(IRI.create(TEST_NS + "D"));
        ont.addAxiom(factory.getOWLDeclarationAxiom(c));
        ont.addAxiom(factory.getOWLDeclarationAxiom(d));
        ont.addAxiom(factory.getOWLSubClassOfAxiom(d, c));
        return ont;
    }

    /**
     * Build a simple OWL 2 EL ontology where C is unsatisfiable:
     *   Declaration(C), Declaration(D),
     *   DisjointClasses(C, D), SubClassOf(C, D)
     * (DisjointClasses and SubClassOf are both in OWL 2 EL profile.)
     */
    private OWLOntology buildUnsatisfiableElOntology() throws Exception {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = mgr.getOWLDataFactory();
        OWLOntology ont = mgr.createOntology(IRI.create(TEST_NS + "unsat"));
        OWLClass c = factory.getOWLClass(IRI.create(TEST_NS + "C"));
        OWLClass d = factory.getOWLClass(IRI.create(TEST_NS + "D"));
        ont.addAxiom(factory.getOWLDeclarationAxiom(c));
        ont.addAxiom(factory.getOWLDeclarationAxiom(d));
        ont.addAxiom(factory.getOWLDisjointClassesAxiom(c, d));
        ont.addAxiom(factory.getOWLSubClassOfAxiom(c, d));
        return ont;
    }

    @Test
    @DisplayName("OWL 2 EL satisfiable class returns true")
    void satisfiableElClassReturnsTrue() throws Exception {
        OWLOntology ont = buildSatisfiableElOntology();
        ELKAdapter adapter = new ELKAdapter();
        adapter.initialize(ont);
        try {
            OWLClass c = df.getOWLClass(IRI.create(TEST_NS + "C"));
            assertTrue(adapter.isSatisfiable(c),
                "C should be satisfiable in the simple EL ontology");
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    @DisplayName("OWL 2 EL unsatisfiable class returns false (disjointness)")
    void unsatisfiableElClassReturnsFalse() throws Exception {
        OWLOntology ont = buildUnsatisfiableElOntology();
        ELKAdapter adapter = new ELKAdapter();
        adapter.initialize(ont);
        try {
            OWLClass c = df.getOWLClass(IRI.create(TEST_NS + "C"));
            assertFalse(adapter.isSatisfiable(c),
                "C should be unsatisfiable (C⊑D and Disjoint(C,D) => C⊆⊥)");
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    @DisplayName("isSatisfiable throws IllegalStateException after shutdown")
    void shutdownThrowsIllegalStateException() throws Exception {
        OWLOntology ont = buildSatisfiableElOntology();
        ELKAdapter adapter = new ELKAdapter();
        adapter.initialize(ont);
        adapter.shutdown();
        OWLClass c = df.getOWLClass(IRI.create(TEST_NS + "C"));
        assertThrows(IllegalStateException.class, () -> adapter.isSatisfiable(c),
            "isSatisfiable after shutdown should throw IllegalStateException");
    }
}
