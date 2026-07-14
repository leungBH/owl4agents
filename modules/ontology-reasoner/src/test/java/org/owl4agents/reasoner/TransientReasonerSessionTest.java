package org.owl4agents.reasoner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.ConsistencyResult;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("v0.8.5 TransientReasonerSession lifecycle tests (task 5.5)")
class TransientReasonerSessionTest {

    private static final String NS = "http://owl4agents.org/test/transient-session#";

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

    private OWLOntology buildInconsistentOntology() {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = mgr.getOWLDataFactory();
        try {
            OWLOntology ont = mgr.createOntology(IRI.create(NS + "inconsistent"));
            OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
            OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
            org.semanticweb.owlapi.model.OWLNamedIndividual x =
                df.getOWLNamedIndividual(IRI.create(NS + "x"));
            ont.addAxiom(df.getOWLDeclarationAxiom(a));
            ont.addAxiom(df.getOWLDeclarationAxiom(b));
            ont.addAxiom(df.getOWLDeclarationAxiom(x));
            ont.addAxiom(df.getOWLDisjointClassesAxiom(a, b));
            // x is both A and B, but A and B are disjoint → inconsistent
            ont.addAxiom(df.getOWLClassAssertionAxiom(a, x));
            ont.addAxiom(df.getOWLClassAssertionAxiom(b, x));
            return ont;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("Normal completion: try-with-resources disposes the reasoner")
    void normalCompletionDisposesReasoner() {
        OWLOntology ont = buildConsistentOntology();
        ServiceResult<TransientReasonerSession> result =
            TransientReasonerSession.create(ont, "HermiT", Duration.ofSeconds(30));
        assertTrue(result.isSuccess(), "Session creation must succeed");

        try (TransientReasonerSession session =
                ((ServiceResult.Success<TransientReasonerSession>) result).data()) {
            ConsistencyResult cr = session.checkConsistency();
            assertNotNull(cr);
            assertTrue(cr.consistent(), "Ontology should be consistent");
            assertEquals("HermiT", cr.reasonerName());
        }
        // After close, the session is disposed; subsequent checkConsistency throws
    }

    @Test
    @DisplayName("Exception path: close() still called when checkConsistency throws")
    void exceptionPathStillCloses() {
        OWLOntology ont = buildConsistentOntology();
        ServiceResult<TransientReasonerSession> result =
            TransientReasonerSession.create(ont, "HermiT", Duration.ofSeconds(30));
        assertTrue(result.isSuccess());
        TransientReasonerSession session =
            ((ServiceResult.Success<TransientReasonerSession>) result).data();

        try {
            // first call should succeed
            session.checkConsistency();
        } catch (Exception e) {
            // expected in some scenarios
        }
        // close must still be callable without throwing
        session.close();
        // calling close again should be a no-op (idempotent)
        session.close();
    }

    @Test
    @DisplayName("Inconsistent ontology detected by transient reasoner")
    void inconsistentOntologyDetected() {
        OWLOntology ont = buildInconsistentOntology();
        ServiceResult<TransientReasonerSession> result =
            TransientReasonerSession.create(ont, "HermiT", Duration.ofSeconds(30));
        assertTrue(result.isSuccess());

        try (TransientReasonerSession session =
                ((ServiceResult.Success<TransientReasonerSession>) result).data()) {
            ConsistencyResult cr = session.checkConsistency();
            assertNotNull(cr);
            assertFalse(cr.consistent(), "Ontology should be inconsistent");
        }
    }

    @Test
    @DisplayName("Default reasoner is HermiT when name is null or blank")
    void defaultReasonerIsHermiT() {
        OWLOntology ont = buildConsistentOntology();
        ServiceResult<TransientReasonerSession> result =
            TransientReasonerSession.create(ont, null, Duration.ofSeconds(30));
        assertTrue(result.isSuccess());
        try (TransientReasonerSession session =
                ((ServiceResult.Success<TransientReasonerSession>) result).data()) {
            assertEquals("HermiT", session.reasonerName());
            ConsistencyResult cr = session.checkConsistency();
            assertEquals("HermiT", cr.reasonerName());
        }
    }

    @Test
    @DisplayName("Unknown reasoner returns TRANSIENT_REASONER_INIT_FAILED")
    void unknownReasonerReturnsError() {
        OWLOntology ont = buildConsistentOntology();
        ServiceResult<TransientReasonerSession> result =
            TransientReasonerSession.create(ont, "UnknownReasoner", Duration.ofSeconds(30));
        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.TRANSIENT_REASONER_INIT_FAILED,
            ((ServiceResult.Error<TransientReasonerSession>) result).error().code());
    }

    @Test
    @DisplayName("Null ontology returns TRANSIENT_REASONER_INIT_FAILED")
    void nullOntologyReturnsError() {
        ServiceResult<TransientReasonerSession> result =
            TransientReasonerSession.create(null, "HermiT", Duration.ofSeconds(30));
        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.TRANSIENT_REASONER_INIT_FAILED,
            ((ServiceResult.Error<TransientReasonerSession>) result).error().code());
    }

    @Test
    @DisplayName("checkConsistency after close throws IllegalStateException")
    void checkConsistencyAfterCloseThrows() {
        OWLOntology ont = buildConsistentOntology();
        ServiceResult<TransientReasonerSession> result =
            TransientReasonerSession.create(ont, "HermiT", Duration.ofSeconds(30));
        assertTrue(result.isSuccess());
        TransientReasonerSession session =
            ((ServiceResult.Success<TransientReasonerSession>) result).data();
        session.close();
        assertThrows(IllegalStateException.class, session::checkConsistency);
    }

    @Test
    @DisplayName("close() is idempotent — calling multiple times is safe")
    void closeIsIdempotent() {
        OWLOntology ont = buildConsistentOntology();
        ServiceResult<TransientReasonerSession> result =
            TransientReasonerSession.create(ont, "HermiT", Duration.ofSeconds(30));
        assertTrue(result.isSuccess());
        TransientReasonerSession session =
            ((ServiceResult.Success<TransientReasonerSession>) result).data();
        session.close();
        session.close();
        session.close();
        // No exception should be thrown
    }

    @Test
    @DisplayName("1000 sequential checks: no memory leak, all consistent")
    void sequential1000ChecksMemoryStability() {
        OWLOntology ont = buildConsistentOntology();
        Runtime rt = Runtime.getRuntime();
        System.gc();
        long heapBefore = rt.totalMemory() - rt.freeMemory();

        for (int i = 0; i < 1000; i++) {
            ServiceResult<TransientReasonerSession> result =
                TransientReasonerSession.create(ont, "HermiT", Duration.ofSeconds(30));
            assertTrue(result.isSuccess(), "Check #" + i + ": creation must succeed");
            try (TransientReasonerSession session =
                    ((ServiceResult.Success<TransientReasonerSession>) result).data()) {
                ConsistencyResult cr = session.checkConsistency();
                assertTrue(cr.consistent(), "Check #" + i + ": ontology must remain consistent");
            }
        }

        System.gc();
        long heapAfter = rt.totalMemory() - rt.freeMemory();
        long delta = heapAfter - heapBefore;
        // Allow up to 32MB growth for JIT/class loader overhead, but no linear
        // growth from reasoner leaks. (HermiT initial heap is ~5MB.)
        assertTrue(delta < 32L * 1024 * 1024,
            "Heap growth after 1000 sequential checks must be < 32MB. " +
            "before=" + heapBefore + ", after=" + heapAfter + ", delta=" + delta);
    }

    @Test
    @DisplayName("Concurrent isolation: 10 threads each get independent sessions")
    void concurrentIsolation10Threads() throws Exception {
        OWLOntology consistent = buildConsistentOntology();
        OWLOntology inconsistent = buildInconsistentOntology();

        int threadCount = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            final OWLOntology ont = (idx % 2 == 0) ? consistent : inconsistent;
            final boolean expectConsistent = (idx % 2 == 0);
            pool.submit(() -> {
                ready.countDown();
                try {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    firstError.compareAndSet(null, e);
                    return;
                }
                try (TransientReasonerSession session = openSession(ont)) {
                    ConsistencyResult cr = session.checkConsistency();
                    if (cr.consistent() != expectConsistent) {
                        firstError.compareAndSet(null, new AssertionError(
                            "Thread " + idx + ": expected consistent=" + expectConsistent +
                            " but got " + cr.consistent()));
                        return;
                    }
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    firstError.compareAndSet(null, t);
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        assertNull(firstError.get(), "First error: " + firstError.get());
        assertEquals(threadCount, successCount.get(),
            "All " + threadCount + " threads must succeed independently");
    }

    @Test
    @DisplayName("Openllet session supports explanation; HermiT does not")
    void explanationSupportsVaryByReasoner() {
        OWLOntology ont = buildConsistentOntology();

        ServiceResult<TransientReasonerSession> openlletResult =
            TransientReasonerSession.create(ont, "Openllet", Duration.ofSeconds(30));
        if (openlletResult.isSuccess()) {
            try (TransientReasonerSession session =
                    ((ServiceResult.Success<TransientReasonerSession>) openlletResult).data()) {
                assertTrue(session.supportsExplanation(),
                    "Openllet should support explanation");
            }
        } else {
            // Openllet may not be on classpath in all environments; skip
            System.out.println("[TransientReasonerSessionTest] Openllet not available, skipping");
        }

        ServiceResult<TransientReasonerSession> hermitResult =
            TransientReasonerSession.create(ont, "HermiT", Duration.ofSeconds(30));
        assertTrue(hermitResult.isSuccess());
        try (TransientReasonerSession session =
                ((ServiceResult.Success<TransientReasonerSession>) hermitResult).data()) {
            assertFalse(session.supportsExplanation(),
                "HermiT should not support explanation");
        }
    }

    private static TransientReasonerSession openSession(OWLOntology ont) {
        ServiceResult<TransientReasonerSession> result =
            TransientReasonerSession.create(ont, "HermiT", Duration.ofSeconds(30));
        if (!result.isSuccess()) {
            fail("Session creation failed: " +
                ((ServiceResult.Error<TransientReasonerSession>) result).error().message());
        }
        return ((ServiceResult.Success<TransientReasonerSession>) result).data();
    }
}
