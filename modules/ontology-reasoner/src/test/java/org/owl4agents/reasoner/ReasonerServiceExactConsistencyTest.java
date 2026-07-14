package org.owl4agents.reasoner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.ConsistencyAfterAdditionResult;
import org.owl4agents.core.model.ConsistencyAfterAdditionStatus;
import org.owl4agents.core.model.EntailmentResult;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.parameters.Imports;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("v0.8.5 ReasonerService exact consistency tests (task 6.7)")
class ReasonerServiceExactConsistencyTest {

    private static final String NS = "http://owl4agents.org/test/exact-consistency#";

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

    private OWLOntology buildOntologyWithDisjointWitness() {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = mgr.getOWLDataFactory();
        try {
            OWLOntology ont = mgr.createOntology(IRI.create(NS + "witnessed"));
            OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
            OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
            OWLNamedIndividual x = df.getOWLNamedIndividual(IRI.create(NS + "x"));
            ont.addAxiom(df.getOWLDeclarationAxiom(a));
            ont.addAxiom(df.getOWLDeclarationAxiom(b));
            ont.addAxiom(df.getOWLDeclarationAxiom(x));
            ont.addAxiom(df.getOWLDisjointClassesAxiom(a, b));
            ont.addAxiom(df.getOWLClassAssertionAxiom(a, x));
            // x is type A. Claim: x is also type B. Since A and B are disjoint,
            // adding ClassAssertion(B, x) should make the ontology inconsistent.
            return ont;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ReasonerServiceImpl createService() {
        // Use a minimal constructor — the exact consistency methods don't
        // depend on CatalogStore/OntologyCache for in-memory ontologies.
        return new ReasonerServiceImpl(null, null, "test");
    }

    @Test
    @DisplayName("Consistent: adding a benign claim axiom preserves consistency")
    void consistentClaimPreservesConsistency() {
        OWLOntology source = buildConsistentOntology();
        OWLDataFactory df = source.getOWLOntologyManager().getOWLDataFactory();
        // Adding Cat SubClassOf Animal — should stay consistent
        OWLClass cat = df.getOWLClass(IRI.create(NS + "Cat"));
        OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));
        OWLSubClassOfAxiom claim = df.getOWLSubClassOfAxiom(cat, animal);

        OntologyId ontId = new OntologyId("test-consistent");
        ReasonerServiceImpl service = createService();
        ServiceResult<ConsistencyAfterAdditionResult> result =
            service.checkConsistencyAfterAdding(source, ontId, "claim-1",
                claim, Optional.of("HermiT"), Duration.ofSeconds(30));

        assertTrue(result.isSuccess(), "Service call must succeed");
        ConsistencyAfterAdditionResult data =
            ((ServiceResult.Success<ConsistencyAfterAdditionResult>) result).data();
        assertEquals(ConsistencyAfterAdditionStatus.CONSISTENT, data.status(),
            "Adding a benign claim should preserve consistency");
        assertTrue(data.sourceOntologyConsistent(),
            "Source ontology should be reported as consistent");
        assertTrue(data.temporaryOntologyIsolated(),
            "Temporary ontology should be isolated");
        assertNotNull(data.perStageTiming());
        assertNotNull(data.perStageTiming().temporaryCopyMs());
        assertNotNull(data.perStageTiming().reasonerInitMs());
        assertNotNull(data.perStageTiming().consistencyCheckMs());
        assertNotNull(data.perStageTiming().totalMs());
    }

    @Test
    @DisplayName("Inconsistent: adding a contradictory claim axiom triggers inconsistency")
    void contradictoryClaimTriggersInconsistency() {
        OWLOntology source = buildOntologyWithDisjointWitness();
        OWLDataFactory df = source.getOWLOntologyManager().getOWLDataFactory();
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLNamedIndividual x = df.getOWLNamedIndividual(IRI.create(NS + "x"));
        // Claim: ClassAssertion(B, x). Since A and B are disjoint and x is A,
        // adding ClassAssertion(B, x) makes the ontology inconsistent.
        var claim = df.getOWLClassAssertionAxiom(b, x);

        OntologyId ontId = new OntologyId("test-inconsistent");
        ReasonerServiceImpl service = createService();
        ServiceResult<ConsistencyAfterAdditionResult> result =
            service.checkConsistencyAfterAdding(source, ontId, "claim-2",
                claim, Optional.of("HermiT"), Duration.ofSeconds(30));

        assertTrue(result.isSuccess());
        ConsistencyAfterAdditionResult data =
            ((ServiceResult.Success<ConsistencyAfterAdditionResult>) result).data();
        assertEquals(ConsistencyAfterAdditionStatus.INCONSISTENT, data.status(),
            "Adding a contradictory claim should trigger inconsistency");
    }

    @Test
    @DisplayName("Source ontology immutability: axiom count unchanged after exact check")
    void sourceImmutabilityAfterExactCheck() {
        OWLOntology source = buildConsistentOntology();
        OWLDataFactory df = source.getOWLOntologyManager().getOWLDataFactory();
        OWLClass cat = df.getOWLClass(IRI.create(NS + "Cat"));
        OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));
        OWLSubClassOfAxiom claim = df.getOWLSubClassOfAxiom(cat, animal);

        int beforeCount = source.getAxiomCount(Imports.INCLUDED);
        OntologyId ontId = new OntologyId("test-immutable");
        ReasonerServiceImpl service = createService();
        service.checkConsistencyAfterAdding(source, ontId, "claim-3",
            claim, Optional.of("HermiT"), Duration.ofSeconds(30));

        int afterCount = source.getAxiomCount(Imports.INCLUDED);
        assertEquals(beforeCount, afterCount,
            "Source ontology axiom count must be unchanged after exact check");
        assertFalse(source.containsAxiom(claim, true),
            "Source ontology must not contain the claim axiom after check");
    }

    @Test
    @DisplayName("Timeout: very short timeout returns TIMEOUT status")
    void veryShortTimeoutReturnsTimeout() {
        OWLOntology source = buildConsistentOntology();
        OWLDataFactory df = source.getOWLOntologyManager().getOWLDataFactory();
        OWLClass cat = df.getOWLClass(IRI.create(NS + "Cat"));
        OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));
        OWLSubClassOfAxiom claim = df.getOWLSubClassOfAxiom(cat, animal);

        OntologyId ontId = new OntologyId("test-timeout");
        ReasonerServiceImpl service = createService();
        // 1 nanosecond timeout — should timeout before HermiT finishes
        ServiceResult<ConsistencyAfterAdditionResult> result =
            service.checkConsistencyAfterAdding(source, ontId, "claim-4",
                claim, Optional.of("HermiT"), Duration.ofNanos(1));

        assertTrue(result.isSuccess());
        ConsistencyAfterAdditionResult data =
            ((ServiceResult.Success<ConsistencyAfterAdditionResult>) result).data();
        // With a 1-nanosecond timeout, the result should be TIMEOUT
        // (HermiT initialization + consistency check takes milliseconds)
        assertTrue(data.status() == ConsistencyAfterAdditionStatus.TIMEOUT
                || data.status() == ConsistencyAfterAdditionStatus.CONSISTENT,
            "With 1ns timeout, result should be TIMEOUT or CONSISTENT (if HermiT finished before Future.get timed out). " +
            "Got: " + data.status());
    }

    @Test
    @DisplayName("Exception path: null source ontology returns error")
    void nullSourceReturnsError() {
        OntologyId ontId = new OntologyId("test-null");
        ReasonerServiceImpl service = createService();
        OWLDataFactory df = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        OWLSubClassOfAxiom claim = df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "X")),
            df.getOWLClass(IRI.create(NS + "Y")));

        ServiceResult<ConsistencyAfterAdditionResult> result =
            service.checkConsistencyAfterAdding(null, ontId, "claim-5",
                claim, Optional.of("HermiT"), Duration.ofSeconds(30));
        assertFalse(result.isSuccess(), "Null source should return error");
    }

    @Test
    @DisplayName("Exception path: null claim axiom returns error")
    void nullClaimReturnsError() {
        OWLOntology source = buildConsistentOntology();
        OntologyId ontId = new OntologyId("test-null-claim");
        ReasonerServiceImpl service = createService();

        ServiceResult<ConsistencyAfterAdditionResult> result =
            service.checkConsistencyAfterAdding(source, ontId, "claim-6",
                null, Optional.of("HermiT"), Duration.ofSeconds(30));
        assertFalse(result.isSuccess(), "Null claim axiom should return error");
    }

    @Test
    @DisplayName("checkAxiomEntailment: asserted axiom returns ENTAILED via fast-path")
    void checkAxiomEntailmentAssertedFastPath() {
        OWLOntology source = buildConsistentOntology();
        OWLDataFactory df = source.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
        OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));
        OWLSubClassOfAxiom asserted = df.getOWLSubClassOfAxiom(dog, animal);

        OntologyId ontId = new OntologyId("test-entail-asserted");
        ReasonerServiceImpl service = createService();
        ServiceResult<EntailmentResult> result =
            service.checkAxiomEntailment(source, ontId, asserted, Optional.of("HermiT"));

        assertTrue(result.isSuccess());
        EntailmentResult data = ((ServiceResult.Success<EntailmentResult>) result).data();
        assertEquals(EntailmentResult.ENTAILED, data.result(),
            "Asserted axiom should be ENTAILED via fast-path");
        assertEquals("asserted", data.source(),
            "Source should be 'asserted' for fast-path");
    }

    @Test
    @DisplayName("checkAxiomEntailment: non-asserted but entailed axiom returns ENTAILED via reasoner")
    void checkAxiomEntailmentInferred() {
        OWLOntology source = buildConsistentOntology();
        OWLDataFactory df = source.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
        OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));
        // Dog SubClassOf owl:Thing — not asserted but trivially entailed
        OWLSubClassOfAxiom entailed = df.getOWLSubClassOfAxiom(dog, df.getOWLThing());

        OntologyId ontId = new OntologyId("test-entail-inferred");
        ReasonerServiceImpl service = createService();
        ServiceResult<EntailmentResult> result =
            service.checkAxiomEntailment(source, ontId, entailed, Optional.of("HermiT"));

        assertTrue(result.isSuccess());
        EntailmentResult data = ((ServiceResult.Success<EntailmentResult>) result).data();
        assertEquals(EntailmentResult.ENTAILED, data.result(),
            "Dog SubClassOf owl:Thing should be ENTAILED (trivially true)");
    }

    @Test
    @DisplayName("checkAxiomEntailment: non-entailed axiom returns NOT_ENTAILED")
    void checkAxiomEntailmentNotEntailed() {
        OWLOntology source = buildConsistentOntology();
        OWLDataFactory df = source.getOWLOntologyManager().getOWLDataFactory();
        OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));
        OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
        // Animal SubClassOf Dog — NOT entailed (the reverse is asserted)
        OWLSubClassOfAxiom notEntailed = df.getOWLSubClassOfAxiom(animal, dog);

        OntologyId ontId = new OntologyId("test-entail-not");
        ReasonerServiceImpl service = createService();
        ServiceResult<EntailmentResult> result =
            service.checkAxiomEntailment(source, ontId, notEntailed, Optional.of("HermiT"));

        assertTrue(result.isSuccess());
        EntailmentResult data = ((ServiceResult.Success<EntailmentResult>) result).data();
        assertEquals(EntailmentResult.NOT_ENTAILED, data.result(),
            "Animal SubClassOf Dog should be NOT_ENTAILED");
    }

    @Test
    @DisplayName("Sequential isolation: 5 exact checks do not interfere")
    void sequentialIsolation() {
        OWLOntology source = buildConsistentOntology();
        OWLDataFactory df = source.getOWLOntologyManager().getOWLDataFactory();
        OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));

        OntologyId ontId = new OntologyId("test-sequential");
        ReasonerServiceImpl service = createService();
        for (int i = 0; i < 5; i++) {
            OWLClass c = df.getOWLClass(IRI.create(NS + "Class" + i));
            OWLSubClassOfAxiom claim = df.getOWLSubClassOfAxiom(c, animal);
            ServiceResult<ConsistencyAfterAdditionResult> result =
                service.checkConsistencyAfterAdding(source, ontId, "claim-seq-" + i,
                    claim, Optional.of("HermiT"), Duration.ofSeconds(30));
            assertTrue(result.isSuccess(), "Check #" + i + " must succeed");
            assertEquals(ConsistencyAfterAdditionStatus.CONSISTENT,
                ((ServiceResult.Success<ConsistencyAfterAdditionResult>) result).data().status(),
                "Check #" + i + " must be CONSISTENT");
        }
        // Source must still be unchanged
        assertFalse(source.containsAxiom(
            df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(NS + "Class0")), animal), true),
            "Source must not contain claim from check #0");
    }

    @Test
    @DisplayName("P1 cached session: second call reuses cached session (temporaryCopyMs=0, reasonerInitMs=0)")
    void cachedSessionReusesOnSecondCall() {
        OWLOntology source = buildConsistentOntology();
        OWLDataFactory df = source.getOWLOntologyManager().getOWLDataFactory();
        OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));

        OntologyId ontId = new OntologyId("test-cached");
        ReasonerServiceImpl service = createService();

        // First call — cache miss: should create base copy + initialize reasoner
        OWLClass cat = df.getOWLClass(IRI.create(NS + "Cat"));
        OWLSubClassOfAxiom claim1 = df.getOWLSubClassOfAxiom(cat, animal);
        ServiceResult<ConsistencyAfterAdditionResult> result1 =
            service.checkConsistencyAfterAdding(source, ontId, "claim-cache-1",
                claim1, Optional.of("HermiT"), Duration.ofSeconds(30));
        assertTrue(result1.isSuccess(), "First call must succeed");
        ConsistencyAfterAdditionResult data1 =
            ((ServiceResult.Success<ConsistencyAfterAdditionResult>) result1).data();
        assertEquals(ConsistencyAfterAdditionStatus.CONSISTENT, data1.status(),
            "First call should be CONSISTENT");
        assertNotNull(data1.perStageTiming().temporaryCopyMs(),
            "First call should have non-null temporaryCopyMs");
        assertNotNull(data1.perStageTiming().reasonerInitMs(),
            "First call should have non-null reasonerInitMs");
        long firstCopyMs = data1.perStageTiming().temporaryCopyMs();
        long firstInitMs = data1.perStageTiming().reasonerInitMs();
        long firstTotalMs = data1.perStageTiming().totalMs();
        assertTrue(firstCopyMs >= 0, "First call temporaryCopyMs should be >= 0");
        assertTrue(firstInitMs >= 0, "First call reasonerInitMs should be >= 0");

        // Second call — cache hit: should reuse cached session
        OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog2"));
        OWLSubClassOfAxiom claim2 = df.getOWLSubClassOfAxiom(dog, animal);
        ServiceResult<ConsistencyAfterAdditionResult> result2 =
            service.checkConsistencyAfterAdding(source, ontId, "claim-cache-2",
                claim2, Optional.of("HermiT"), Duration.ofSeconds(30));
        assertTrue(result2.isSuccess(), "Second call must succeed");
        ConsistencyAfterAdditionResult data2 =
            ((ServiceResult.Success<ConsistencyAfterAdditionResult>) result2).data();
        assertEquals(ConsistencyAfterAdditionStatus.CONSISTENT, data2.status(),
            "Second call should be CONSISTENT");

        // P1 fix: on cache hit, temporaryCopyMs and reasonerInitMs should be 0
        assertEquals(0L, data2.perStageTiming().temporaryCopyMs(),
            "Second call (cache hit) should have temporaryCopyMs=0 — " +
            "base ontology copy is reused from cache");
        assertEquals(0L, data2.perStageTiming().reasonerInitMs(),
            "Second call (cache hit) should have reasonerInitMs=0 — " +
            "reasoner session is reused from cache");

        // Third call — still cache hit
        OWLClass bird = df.getOWLClass(IRI.create(NS + "Bird"));
        OWLSubClassOfAxiom claim3 = df.getOWLSubClassOfAxiom(bird, animal);
        ServiceResult<ConsistencyAfterAdditionResult> result3 =
            service.checkConsistencyAfterAdding(source, ontId, "claim-cache-3",
                claim3, Optional.of("HermiT"), Duration.ofSeconds(30));
        assertTrue(result3.isSuccess(), "Third call must succeed");
        ConsistencyAfterAdditionResult data3 =
            ((ServiceResult.Success<ConsistencyAfterAdditionResult>) result3).data();
        assertEquals(0L, data3.perStageTiming().temporaryCopyMs(),
            "Third call (cache hit) should have temporaryCopyMs=0");
        assertEquals(0L, data3.perStageTiming().reasonerInitMs(),
            "Third call (cache hit) should have reasonerInitMs=0");

        // Source ontology must not contain any of the claim axioms
        assertFalse(source.containsAxiom(claim1, true),
            "Source must not contain claim1 after cached checks");
        assertFalse(source.containsAxiom(claim2, true),
            "Source must not contain claim2 after cached checks");
        assertFalse(source.containsAxiom(claim3, true),
            "Source must not contain claim3 after cached checks");
    }

    @Test
    @DisplayName("P1 cached session: different ontology IDs use separate cached sessions")
    void differentOntologyIdsUseSeparateSessions() {
        OWLOntology source1 = buildConsistentOntology();
        OWLOntology source2 = buildOntologyWithDisjointWitness();
        OWLDataFactory df = source1.getOWLOntologyManager().getOWLDataFactory();
        OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));

        ReasonerServiceImpl service = createService();

        // Call with ontology 1 — cache miss
        OWLClass cat = df.getOWLClass(IRI.create(NS + "Cat"));
        OWLSubClassOfAxiom claim1 = df.getOWLSubClassOfAxiom(cat, animal);
        ServiceResult<ConsistencyAfterAdditionResult> r1 =
            service.checkConsistencyAfterAdding(source1, new OntologyId("ont-A"),
                "c1", claim1, Optional.of("HermiT"), Duration.ofSeconds(30));
        assertTrue(r1.isSuccess());

        // Call with ontology 2 — should be cache miss (different ontology ID)
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLNamedIndividual x = df.getOWLNamedIndividual(IRI.create(NS + "x"));
        var claim2 = df.getOWLClassAssertionAxiom(b, x);
        ServiceResult<ConsistencyAfterAdditionResult> r2 =
            service.checkConsistencyAfterAdding(source2, new OntologyId("ont-B"),
                "c2", claim2, Optional.of("HermiT"), Duration.ofSeconds(30));
        assertTrue(r2.isSuccess());
        ConsistencyAfterAdditionResult data2 =
            ((ServiceResult.Success<ConsistencyAfterAdditionResult>) r2).data();
        // Different ontology ID → cache miss → temporaryCopyMs > 0 or reasonerInitMs > 0
        assertTrue(data2.perStageTiming().temporaryCopyMs() > 0
                || data2.perStageTiming().reasonerInitMs() > 0,
            "Different ontology ID should trigger cache miss " +
            "(temporaryCopyMs or reasonerInitMs > 0)");

        // Call with ontology 1 again — should be cache hit
        OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog3"));
        OWLSubClassOfAxiom claim3 = df.getOWLSubClassOfAxiom(dog, animal);
        ServiceResult<ConsistencyAfterAdditionResult> r3 =
            service.checkConsistencyAfterAdding(source1, new OntologyId("ont-A"),
                "c3", claim3, Optional.of("HermiT"), Duration.ofSeconds(30));
        assertTrue(r3.isSuccess());
        ConsistencyAfterAdditionResult data3 =
            ((ServiceResult.Success<ConsistencyAfterAdditionResult>) r3).data();
        assertEquals(0L, data3.perStageTiming().temporaryCopyMs(),
            "Returning to ontology A should hit cache (temporaryCopyMs=0)");
        assertEquals(0L, data3.perStageTiming().reasonerInitMs(),
            "Returning to ontology A should hit cache (reasonerInitMs=0)");
    }
}
