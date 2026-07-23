package org.owl4agents.reasoner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.8 task 7.2: Unit tests for {@link OpenlletAdapter#isSatisfiable(OWLClassExpression)}.
 *
 * <p>Same scenarios as {@link HermiTAdapterSatisfiabilityTest}, verifying that
 * the Openllet adapter implements the satisfiability operation with the same
 * flush-then-query contract as HermiT.
 */
@DisplayName("v0.8.8 7.2: OpenlletAdapter isSatisfiable")
class OpenlletAdapterSatisfiabilityTest {

    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";
    private static final String TEST_NS = "http://owl4agents.org/test/openllet-sat#";

    private OWLDataFactory df;

    @BeforeEach
    void setUp() {
        df = OWLManager.getOWLDataFactory();
    }

    private OWLOntology loadPizza() throws Exception {
        Path fixtureDir = Path.of(System.getProperty("corpus.fixtures"));
        Path pizzaPath = fixtureDir.resolve("smoke/pizza.owl");
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        return mgr.loadOntologyFromOntologyDocument(pizzaPath.toFile());
    }

    private OWLOntology buildUnsatisfiableOntology() throws Exception {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = mgr.getOWLDataFactory();
        OWLOntology ont = mgr.createOntology(IRI.create(TEST_NS + "unsat"));
        OWLClass c = factory.getOWLClass(IRI.create(TEST_NS + "C"));
        OWLClass d = factory.getOWLClass(IRI.create(TEST_NS + "D"));
        ont.addAxiom(factory.getOWLDeclarationAxiom(c));
        ont.addAxiom(factory.getOWLDeclarationAxiom(d));
        // DisjointClasses(C, D) + SubClassOf(C, D) => C is unsatisfiable
        ont.addAxiom(factory.getOWLDisjointClassesAxiom(c, d));
        ont.addAxiom(factory.getOWLSubClassOfAxiom(c, d));
        return ont;
    }

    @Test
    @DisplayName("Satisfiable class (Pizza) returns true")
    void satisfiableClassReturnsTrue() throws Exception {
        OWLOntology pizza = loadPizza();
        OpenlletAdapter adapter = new OpenlletAdapter();
        adapter.initialize(pizza);
        try {
            OWLClass pizzaClass = df.getOWLClass(IRI.create(PIZZA_NS + "Pizza"));
            assertTrue(adapter.isSatisfiable(pizzaClass),
                "Pizza should be satisfiable");
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    @DisplayName("Unsatisfiable class (C where Disjoint(C,D) and C⊑D) returns false")
    void unsatisfiableClassReturnsFalse() throws Exception {
        OWLOntology ont = buildUnsatisfiableOntology();
        OpenlletAdapter adapter = new OpenlletAdapter();
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
    @DisplayName("Complex expression (Pizza ⊓ ∃hasTopping.CheeseTopping) is satisfiable")
    void complexExpressionSatisfiable() throws Exception {
        OWLOntology pizza = loadPizza();
        OpenlletAdapter adapter = new OpenlletAdapter();
        adapter.initialize(pizza);
        try {
            OWLClass pizzaClass = df.getOWLClass(IRI.create(PIZZA_NS + "Pizza"));
            OWLClass cheeseTopping = df.getOWLClass(IRI.create(PIZZA_NS + "CheeseTopping"));
            OWLObjectProperty hasTopping = df.getOWLObjectProperty(IRI.create(PIZZA_NS + "hasTopping"));
            OWLClassExpression complex = df.getOWLObjectIntersectionOf(
                pizzaClass,
                df.getOWLObjectSomeValuesFrom(hasTopping, cheeseTopping));
            assertTrue(adapter.isSatisfiable(complex),
                "Pizza ⊓ ∃hasTopping.CheeseTopping should be satisfiable (such a pizza can exist)");
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    @DisplayName("isSatisfiable throws IllegalStateException after shutdown")
    void shutdownThrowsIllegalStateException() throws Exception {
        OWLOntology pizza = loadPizza();
        OpenlletAdapter adapter = new OpenlletAdapter();
        adapter.initialize(pizza);
        adapter.shutdown();
        OWLClass pizzaClass = df.getOWLClass(IRI.create(PIZZA_NS + "Pizza"));
        assertThrows(IllegalStateException.class, () -> adapter.isSatisfiable(pizzaClass),
            "isSatisfiable after shutdown should throw IllegalStateException");
    }

    @Test
    @DisplayName("Flush is called before query: added axiom reflected in satisfiability")
    void flushCalledBeforeQuery() throws Exception {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = mgr.getOWLDataFactory();
        OWLOntology ont = mgr.createOntology(IRI.create(TEST_NS + "flush"));
        OWLClass c = factory.getOWLClass(IRI.create(TEST_NS + "C"));
        OWLClass d = factory.getOWLClass(IRI.create(TEST_NS + "D"));
        ont.addAxiom(factory.getOWLDeclarationAxiom(c));
        ont.addAxiom(factory.getOWLDeclarationAxiom(d));
        ont.addAxiom(factory.getOWLDisjointClassesAxiom(c, d));

        OpenlletAdapter adapter = new OpenlletAdapter();
        adapter.initialize(ont);
        try {
            // Before adding SubClassOf(C, D), C should be satisfiable
            assertTrue(adapter.isSatisfiable(c),
                "C should be satisfiable before adding SubClassOf(C, D)");

            // Add SubClassOf(C, D) — flush inside isSatisfiable must propagate it.
            ont.addAxiom(factory.getOWLSubClassOfAxiom(c, d));

            assertFalse(adapter.isSatisfiable(c),
                "C should be unsatisfiable after adding SubClassOf(C, D) — flush must propagate the axiom");
        } finally {
            adapter.shutdown();
        }
    }
}
