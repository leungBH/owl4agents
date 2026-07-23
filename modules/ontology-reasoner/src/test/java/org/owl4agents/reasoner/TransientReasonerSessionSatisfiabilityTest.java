package org.owl4agents.reasoner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.ServiceResult;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.8 task 7.4: Unit tests for
 * {@link TransientReasonerSession#isSatisfiable(org.semanticweb.owlapi.model.OWLClassExpression)}.
 *
 * <p>Verifies that the transient session correctly delegates the satisfiability
 * query to the underlying adapter and enforces the disposed-session guard.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Pass-through to HermiT adapter (correct delegation + result)</li>
 *   <li>Pass-through to ELK adapter</li>
 *   <li>After close(), isSatisfiable throws IllegalStateException</li>
 * </ol>
 */
@DisplayName("v0.8.8 7.4: TransientReasonerSession isSatisfiable pass-through")
class TransientReasonerSessionSatisfiabilityTest {

    private static final String NS = "http://owl4agents.org/test/transient-sat#";

    private OWLOntology buildConsistentOntology() {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = mgr.getOWLDataFactory();
        try {
            OWLOntology ont = mgr.createOntology(IRI.create(NS));
            OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));
            OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
            ont.addAxiom(df.getOWLDeclarationAxiom(animal));
            ont.addAxiom(df.getOWLDeclarationAxiom(dog));
            ont.addAxiom(df.getOWLSubClassOfAxiom(dog, animal));
            return ont;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private OWLOntology buildUnsatisfiableOntology() {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = mgr.getOWLDataFactory();
        try {
            OWLOntology ont = mgr.createOntology(IRI.create(NS + "unsat"));
            OWLClass c = df.getOWLClass(IRI.create(NS + "C"));
            OWLClass d = df.getOWLClass(IRI.create(NS + "D"));
            ont.addAxiom(df.getOWLDeclarationAxiom(c));
            ont.addAxiom(df.getOWLDeclarationAxiom(d));
            ont.addAxiom(df.getOWLDisjointClassesAxiom(c, d));
            ont.addAxiom(df.getOWLSubClassOfAxiom(c, d));
            return ont;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("Pass-through to HermiT adapter: satisfiable class returns true")
    void passThroughToHermiTAdapterSatisfiable() {
        OWLOntology ont = buildConsistentOntology();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));

        ServiceResult<TransientReasonerSession> result =
            TransientReasonerSession.create(ont, "HermiT", Duration.ofSeconds(30));
        assertTrue(result.isSuccess(), "Session creation must succeed");

        try (TransientReasonerSession session =
                ((ServiceResult.Success<TransientReasonerSession>) result).data()) {
            assertEquals("HermiT", session.reasonerName());
            assertTrue(session.isSatisfiable(animal),
                "Animal should be satisfiable via HermiT pass-through");
        }
    }

    @Test
    @DisplayName("Pass-through to HermiT adapter: unsatisfiable class returns false")
    void passThroughToHermiTAdapterUnsatisfiable() {
        OWLOntology ont = buildUnsatisfiableOntology();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass c = df.getOWLClass(IRI.create(NS + "C"));

        ServiceResult<TransientReasonerSession> result =
            TransientReasonerSession.create(ont, "HermiT", Duration.ofSeconds(30));
        assertTrue(result.isSuccess());

        try (TransientReasonerSession session =
                ((ServiceResult.Success<TransientReasonerSession>) result).data()) {
            assertFalse(session.isSatisfiable(c),
                "C should be unsatisfiable via HermiT pass-through");
        }
    }

    @Test
    @DisplayName("Pass-through to ELK adapter: satisfiable class returns true")
    void passThroughToElkAdapter() {
        OWLOntology ont = buildConsistentOntology();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));

        ServiceResult<TransientReasonerSession> result =
            TransientReasonerSession.create(ont, "ELK", Duration.ofSeconds(30));
        assertTrue(result.isSuccess(), "ELK session creation must succeed");

        try (TransientReasonerSession session =
                ((ServiceResult.Success<TransientReasonerSession>) result).data()) {
            assertEquals("ELK", session.reasonerName());
            assertTrue(session.isSatisfiable(animal),
                "Animal should be satisfiable via ELK pass-through");
        }
    }

    @Test
    @DisplayName("After close(), isSatisfiable throws IllegalStateException")
    void isSatisfiableAfterCloseThrows() {
        OWLOntology ont = buildConsistentOntology();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));

        ServiceResult<TransientReasonerSession> result =
            TransientReasonerSession.create(ont, "HermiT", Duration.ofSeconds(30));
        assertTrue(result.isSuccess());
        TransientReasonerSession session =
            ((ServiceResult.Success<TransientReasonerSession>) result).data();

        session.close();
        assertThrows(IllegalStateException.class, () -> session.isSatisfiable(animal),
            "isSatisfiable after close() should throw IllegalStateException");
    }
}
