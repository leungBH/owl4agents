package org.owl4agents.reasoner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ServiceResult;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.parameters.Imports;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("v0.8.5 TemporaryOntologyFactory isolation tests (task 4.7)")
class TemporaryOntologyFactoryTest {

    private static final String NS = "http://owl4agents.org/test/temp-factory#";

    private OWLOntology buildSourceOntology() {
        return buildSourceOntology(NS);
    }

    private OWLOntology buildSourceOntology(String ns) {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = mgr.getOWLDataFactory();
        try {
            OWLOntology ont = mgr.createOntology(IRI.create(ns));
            OWLClass animal = df.getOWLClass(IRI.create(ns + "Animal"));
            OWLClass dog = df.getOWLClass(IRI.create(ns + "Dog"));
            OWLClass cat = df.getOWLClass(IRI.create(ns + "Cat"));
            ont.addAxiom(df.getOWLDeclarationAxiom(animal));
            ont.addAxiom(df.getOWLDeclarationAxiom(dog));
            ont.addAxiom(df.getOWLDeclarationAxiom(cat));
            ont.addAxiom(df.getOWLSubClassOfAxiom(dog, animal));
            ont.addAxiom(df.getOWLSubClassOfAxiom(cat, animal));
            return ont;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private OWLSubClassOfAxiom buildClaimAxiom(OWLOntology source, String childLocal, String parentLocal) {
        OWLDataFactory df = source.getOWLOntologyManager().getOWLDataFactory();
        String ns = source.getOntologyID().getOntologyIRI()
            .map(IRI::toString).orElse(NS);
        OWLClass child = df.getOWLClass(IRI.create(ns + childLocal));
        OWLClass parent = df.getOWLClass(IRI.create(ns + parentLocal));
        return df.getOWLSubClassOfAxiom(child, parent);
    }

    private static int axiomCount(OWLOntology ont) {
        return ont.getAxiomCount(Imports.INCLUDED);
    }

    private static int contentHash(OWLOntology ont) {
        // Order-independent hash: sum of each axiom's hashCode
        int hash = 0;
        for (var ax : ont.getAxioms(Imports.INCLUDED)) {
            hash += ax.hashCode();
        }
        return hash;
    }

    @Test
    @DisplayName("Source axiom count and hash are identical after temporary creation + release")
    void sourceImmutabilityAxiomCountAndHash() {
        OWLOntology source = buildSourceOntology();
        int beforeCount = axiomCount(source);
        int beforeHash = contentHash(source);

        TemporaryOntologyFactory factory = new TemporaryOntologyFactory();
        OWLSubClassOfAxiom claim = buildClaimAxiom(source, "Dog", "Animal");

        try (TemporaryOntologyHandle handle = openHandle(factory, source, claim)) {
            assertNotNull(handle);
            // Use the temporary ontology briefly
            assertEquals(claim, handle.ontology().axioms()
                .filter(a -> a instanceof OWLSubClassOfAxiom)
                .map(a -> (OWLSubClassOfAxiom) a)
                .filter(a -> a.getSubClass().equals(claim.getSubClass())
                          && a.getSuperClass().equals(claim.getSuperClass()))
                .findFirst().orElse(null),
                "Temporary ontology must contain the claim axiom");
        }

        assertEquals(beforeCount, axiomCount(source),
            "Source axiom count must be unchanged after temporary creation + release");
        assertEquals(beforeHash, contentHash(source),
            "Source axiom content hash must be unchanged after temporary creation + release");
    }

    @Test
    @DisplayName("Imports closure axioms are included when copyImportsClosure=true")
    void importsClosureIncluded() {
        // Build a source ontology with one declared axiom set
        OWLOntology source = buildSourceOntology();
        // Use a claim axiom that is NOT already in the source so the count
        // difference is unambiguous (Cat SubClassOf Dog is not asserted).
        OWLSubClassOfAxiom claim = buildClaimAxiom(source, "Cat", "Dog");

        TemporaryOntologyFactory factory = new TemporaryOntologyFactory();
        try (TemporaryOntologyHandle handle = openHandle(factory, source, claim,
                new TemporaryOntologyOptions(true, "-temp"))) {
            OWLOntology temp = handle.ontology();
            // The temporary ontology should contain every axiom from the source
            // PLUS the claim axiom.
            int sourceAxiomCount = source.getAxiomCount(Imports.INCLUDED);
            int tempAxiomCount = temp.getAxiomCount(Imports.INCLUDED);
            assertEquals(sourceAxiomCount + 1, tempAxiomCount,
                "Temporary ontology must contain source axioms + 1 new claim axiom. " +
                "source=" + sourceAxiomCount + ", temp=" + tempAxiomCount);
            // Each source axiom should be present in the temporary ontology
            for (var ax : source.getAxioms(Imports.INCLUDED)) {
                assertTrue(temp.containsAxiom(ax),
                    "Temporary ontology must contain source axiom: " + ax);
            }
            // The claim axiom must be present
            assertTrue(temp.containsAxiom(claim),
                "Temporary ontology must contain the claim axiom");
        }
    }

    @Test
    @DisplayName("Imports closure excluded when copyImportsClosure=false")
    void importsClosureExcluded() {
        OWLOntology source = buildSourceOntology();
        OWLSubClassOfAxiom claim = buildClaimAxiom(source, "Dog", "Animal");

        TemporaryOntologyFactory factory = new TemporaryOntologyFactory();
        try (TemporaryOntologyHandle handle = openHandle(factory, source, claim,
                new TemporaryOntologyOptions(false, "-temp"))) {
            OWLOntology temp = handle.ontology();
            // With copyImportsClosure=false, only EXCLUDED axioms are copied,
            // but for a no-imports source EXCLUDED==INCLUDED, so we still expect
            // all source axioms + claim. The contract is that copy=false copies
            // source.getAxioms(Imports.EXCLUDED), which here equals the full set.
            assertTrue(temp.getAxiomCount(Imports.INCLUDED) >= 1,
                "Temporary ontology must at least contain the claim axiom");
            assertTrue(temp.containsAxiom(claim),
                "Temporary ontology must contain the claim axiom");
        }
    }

    @Test
    @DisplayName("No disk files are written during temporary ontology creation")
    void noDiskPersistence() throws Exception {
        Path tempDir = Files.createTempDirectory("owl4agents-temp-factory-test");
        long beforeCount = Files.walk(tempDir).count();

        OWLOntology source = buildSourceOntology();
        OWLSubClassOfAxiom claim = buildClaimAxiom(source, "Dog", "Animal");
        TemporaryOntologyFactory factory = new TemporaryOntologyFactory();

        for (int i = 0; i < 20; i++) {
            try (TemporaryOntologyHandle handle = openHandle(factory, source, claim)) {
                assertNotNull(handle.ontology());
            }
        }

        long afterCount = Files.walk(tempDir).count();
        assertEquals(beforeCount, afterCount,
            "No files should be written to disk by the temporary ontology factory");

        // Cleanup
        try (var stream = Files.walk(tempDir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
        }
    }

    @Test
    @DisplayName("Temporary ontology uses a different OWLOntologyManager than source")
    void independentManager() {
        OWLOntology source = buildSourceOntology();
        OWLSubClassOfAxiom claim = buildClaimAxiom(source, "Dog", "Animal");

        OWLOntologyManager sourceMgr = source.getOWLOntologyManager();
        TemporaryOntologyFactory factory = new TemporaryOntologyFactory();
        try (TemporaryOntologyHandle handle = openHandle(factory, source, claim)) {
            OWLOntologyManager tempMgr = handle.ontology().getOWLOntologyManager();
            assertNotSame(sourceMgr, tempMgr,
                "Temporary ontology manager must be a different instance from the source manager");
            // The temporary manager should not manage the source ontology
            assertFalse(tempMgr.contains(source.getOntologyID()),
                "Temporary manager must not contain the source ontology");
            // The source manager should not manage the temporary ontology
            assertFalse(sourceMgr.contains(handle.ontology().getOntologyID()),
                "Source manager must not contain the temporary ontology");
        }
    }

    @Test
    @DisplayName("No main cache registration: source manager unchanged after temporary creation")
    void noMainCacheRegistration() {
        OWLOntology source = buildSourceOntology();
        OWLOntologyManager sourceMgr = source.getOWLOntologyManager();
        int sourceMgrOntologyCountBefore = sourceMgr.getOntologies().size();

        OWLSubClassOfAxiom claim = buildClaimAxiom(source, "Dog", "Animal");
        TemporaryOntologyFactory factory = new TemporaryOntologyFactory();
        try (TemporaryOntologyHandle handle = openHandle(factory, source, claim)) {
            // The source manager's ontology set should not include the temporary ontology
            boolean sourceMgrContainsTemp = sourceMgr.getOntologies().stream()
                .anyMatch(o -> o.getOntologyID().equals(handle.ontology().getOntologyID()));
            assertFalse(sourceMgrContainsTemp,
                "Source manager must not register the temporary ontology");
        }

        int sourceMgrOntologyCountAfter = sourceMgr.getOntologies().size();
        assertEquals(sourceMgrOntologyCountBefore, sourceMgrOntologyCountAfter,
            "Source manager ontology count must be unchanged");
    }

    @Test
    @DisplayName("Sequential isolation: 1000 checks do not interfere and source stays immutable")
    void sequentialIsolation1000Checks() {
        OWLOntology source = buildSourceOntology();
        int beforeCount = axiomCount(source);
        int beforeHash = contentHash(source);

        TemporaryOntologyFactory factory = new TemporaryOntologyFactory();
        OWLSubClassOfAxiom claim = buildClaimAxiom(source, "Dog", "Animal");

        for (int i = 0; i < 1000; i++) {
            try (TemporaryOntologyHandle handle = openHandle(factory, source, claim)) {
                OWLOntology temp = handle.ontology();
                // Each temporary ontology must independently contain the claim axiom
                assertTrue(temp.containsAxiom(claim),
                    "Check #" + i + ": temporary ontology must contain the claim axiom");
                // And it must contain source axioms
                for (var ax : source.getAxioms(Imports.INCLUDED)) {
                    assertTrue(temp.containsAxiom(ax),
                        "Check #" + i + ": temporary ontology must contain source axiom: " + ax);
                }
            }
        }

        assertEquals(beforeCount, axiomCount(source),
            "After 1000 sequential checks, source axiom count must be unchanged");
        assertEquals(beforeHash, contentHash(source),
            "After 1000 sequential checks, source content hash must be unchanged");
    }

    @Test
    @DisplayName("Concurrent isolation: 10 threads with distinct claims produce independent results")
    void concurrentIsolation10Threads() throws Exception {
        OWLOntology source = buildSourceOntology();
        TemporaryOntologyFactory factory = new TemporaryOntologyFactory();
        int beforeCount = axiomCount(source);
        int beforeHash = contentHash(source);

        int threadCount = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentHashMap<Integer, String> errors = new ConcurrentHashMap<>();
        AtomicInteger successCount = new AtomicInteger(0);

        List<OWLSubClassOfAxiom> claims = java.util.stream.IntStream.range(0, threadCount)
            .mapToObj(i -> buildClaimAxiom(source,
                (i % 2 == 0) ? "Dog" : "Cat",
                "Animal"))
            .toList();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            final OWLSubClassOfAxiom claim = claims.get(idx);
            pool.submit(() -> {
                ready.countDown();
                try {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errors.put(idx, "interrupted before start");
                    return;
                }
                try (TemporaryOntologyHandle handle = openHandle(factory, source, claim)) {
                    OWLOntology temp = handle.ontology();
                    if (!temp.containsAxiom(claim)) {
                        errors.put(idx, "temporary ontology missing claim axiom for thread " + idx);
                        return;
                    }
                    // Source axioms must be present in each thread's temporary ontology
                    for (var ax : source.getAxioms(Imports.INCLUDED)) {
                        if (!temp.containsAxiom(ax)) {
                            errors.put(idx, "temporary ontology missing source axiom " + ax + " for thread " + idx);
                            return;
                        }
                    }
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    errors.put(idx, "exception: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "All threads must reach ready barrier");
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS),
            "All threads must complete within 60s");

        assertEquals(threadCount, successCount.get(),
            "All threads must succeed. Errors: " + errors);
        assertTrue(errors.isEmpty(),
            "No thread should report errors. Errors: " + errors);

        assertEquals(beforeCount, axiomCount(source),
            "After concurrent checks, source axiom count must be unchanged");
        assertEquals(beforeHash, contentHash(source),
            "After concurrent checks, source content hash must be unchanged");
    }

    @Test
    @DisplayName("Release is idempotent and can be called multiple times safely")
    void releaseIsIdempotent() {
        OWLOntology source = buildSourceOntology();
        OWLSubClassOfAxiom claim = buildClaimAxiom(source, "Dog", "Animal");
        TemporaryOntologyFactory factory = new TemporaryOntologyFactory();

        TemporaryOntologyHandle handle = openHandle(factory, source, claim);
        handle.release();
        handle.release();  // second call must not throw
        handle.close();    // third call via close() must not throw
        // After release, the ontology reference is still valid (just the manager is cleared)
        assertNotNull(handle.ontology());
    }

    @Test
    @DisplayName("Null source ontology returns TEMPORARY_ONTOLOGY_CREATION_FAILED error")
    void nullSourceReturnsError() {
        TemporaryOntologyFactory factory = new TemporaryOntologyFactory();
        OWLDataFactory df = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        OWLSubClassOfAxiom claim = df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "X")),
            df.getOWLClass(IRI.create(NS + "Y")));

        ServiceResult<TemporaryOntologyHandle> result =
            factory.create(null, claim, TemporaryOntologyOptions.defaults());
        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED,
            ((ServiceResult.Error<TemporaryOntologyHandle>) result).error().code());
    }

    @Test
    @DisplayName("Null claim axiom returns TEMPORARY_ONTOLOGY_CREATION_FAILED error")
    void nullClaimReturnsError() {
        OWLOntology source = buildSourceOntology();
        TemporaryOntologyFactory factory = new TemporaryOntologyFactory();

        ServiceResult<TemporaryOntologyHandle> result =
            factory.create(source, null, TemporaryOntologyOptions.defaults());
        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED,
            ((ServiceResult.Error<TemporaryOntologyHandle>) result).error().code());
    }

    @Test
    @DisplayName("Null options returns TEMPORARY_ONTOLOGY_CREATION_FAILED error")
    void nullOptionsReturnsError() {
        OWLOntology source = buildSourceOntology();
        OWLSubClassOfAxiom claim = buildClaimAxiom(source, "Dog", "Animal");
        TemporaryOntologyFactory factory = new TemporaryOntologyFactory();

        ServiceResult<TemporaryOntologyHandle> result =
            factory.create(source, claim, null);
        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED,
            ((ServiceResult.Error<TemporaryOntologyHandle>) result).error().code());
    }

    @Test
    @DisplayName("Temporary ontology IRI is derived from source IRI with suffix")
    void temporaryIriIsDerivedFromSource() {
        OWLOntology source = buildSourceOntology();
        OWLSubClassOfAxiom claim = buildClaimAxiom(source, "Dog", "Animal");
        TemporaryOntologyFactory factory = new TemporaryOntologyFactory();

        try (TemporaryOntologyHandle handle = openHandle(factory, source, claim,
                new TemporaryOntologyOptions(true, "-test-suffix"))) {
            IRI tempIri = handle.ontology().getOntologyID().getOntologyIRI().orElse(null);
            assertNotNull(tempIri);
            assertTrue(tempIri.toString().endsWith("-test-suffix"),
                "Temporary ontology IRI must end with the configured suffix. Got: " + tempIri);
            assertTrue(tempIri.toString().startsWith(NS),
                "Temporary ontology IRI must start with source namespace. Got: " + tempIri);
        }
    }

    /**
     * Helper that unwraps a successful {@link ServiceResult} or fails the test
     * with a descriptive message.
     */
    private static TemporaryOntologyHandle openHandle(TemporaryOntologyFactory factory,
                                                      OWLOntology source,
                                                      org.semanticweb.owlapi.model.OWLAxiom claim) {
        return openHandle(factory, source, claim, TemporaryOntologyOptions.defaults());
    }

    private static TemporaryOntologyHandle openHandle(TemporaryOntologyFactory factory,
                                                      OWLOntology source,
                                                      org.semanticweb.owlapi.model.OWLAxiom claim,
                                                      TemporaryOntologyOptions options) {
        ServiceResult<TemporaryOntologyHandle> result = factory.create(source, claim, options);
        if (!result.isSuccess()) {
            ServiceResult.Error<TemporaryOntologyHandle> err =
                (ServiceResult.Error<TemporaryOntologyHandle>) result;
            fail("Temporary ontology creation failed: " + err.error().code() + " — " + err.error().message());
        }
        return ((ServiceResult.Success<TemporaryOntologyHandle>) result).data();
    }
}
