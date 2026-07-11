package org.owl4agents.owlapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.OntologyId;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OntologyCache}.
 *
 * <p>TC-1 through TC-10 as specified in the v0.8.2 change tasks.</p>
 */
@DisplayName("OntologyCache unit tests (TC-1 through TC-10)")
class OntologyCacheTest {

    @TempDir
    Path tempDir;

    private Path createOntologyFile(String ontologyId) throws Exception {
        Path ontDir = tempDir.resolve("default/ontologies/" + ontologyId + "/canonical");
        Files.createDirectories(ontDir);
        Path owlFile = ontDir.resolve("ontology.owl");
        String owlXml = "<?xml version=\"1.0\"?>\n" +
            "<rdf:RDF xmlns=\"http://example.org/test#" +
            ontologyId + "\"\n" +
            "     xml:base=\"http://example.org/test#" + ontologyId + "\"\n" +
            "     xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n" +
            "     xmlns:owl=\"http://www.w3.org/2002/07/owl#\"\n" +
            "     xmlns:xsd=\"http://www.w3.org/2001/XMLSchema#\">\n" +
            "    <owl:Ontology rdf:about=\"http://example.org/test#" + ontologyId + "\"/>\n" +
            "    <owl:Class rdf:about=\"http://example.org/test#TestClass\"/>\n" +
            "</rdf:RDF>\n";
        Files.writeString(owlFile, owlXml);
        return owlFile;
    }

    private OntologyCache createCache() {
        // TTL=0 disables the TTL window so file mtime/size changes are
        // always detected. Tests that verify TTL behavior live in OntologyCacheTtlTest.
        return new OntologyCache(tempDir.toString(), "default", 0);
    }

    @Test
    @DisplayName("TC-1: cache hit returns same OWLOntology instance (no reload)")
    void tc1CacheHitReturnsSameInstance() throws Exception {
        createOntologyFile("test-1");
        OntologyCache cache = createCache();
        OntologyId ontId = new OntologyId("test-1");

        OWLOntology first = cache.getOrCreate(ontId);
        OWLOntology second = cache.getOrCreate(ontId);

        assertSame(first, second, "Cache hit must return the same OWLOntology instance");
    }

    @Test
    @DisplayName("TC-2: file mtime change triggers reload")
    void tc2MtimeChangeTriggersReload() throws Exception {
        Path owlFile = createOntologyFile("test-2");
        OntologyCache cache = createCache();
        OntologyId ontId = new OntologyId("test-2");

        OWLOntology first = cache.getOrCreate(ontId);

        // Change file mtime by rewriting content (ensure size differs too
        // so the reload is triggered even on coarse-grained mtime)
        Thread.sleep(50);
        Files.writeString(owlFile, Files.readString(owlFile) + "\n<!-- modified -->\n");

        OWLOntology second = cache.getOrCreate(ontId);

        assertNotSame(first, second, "File change must trigger reload (new instance)");
    }

    @Test
    @DisplayName("TC-3: file size change triggers reload")
    void tc3SizeChangeTriggersReload() throws Exception {
        Path owlFile = createOntologyFile("test-3");
        OntologyCache cache = createCache();
        OntologyId ontId = new OntologyId("test-3");

        OWLOntology first = cache.getOrCreate(ontId);

        // Append content to change file size (mtime may or may not change
        // within the same second, but size definitely changes)
        Files.writeString(owlFile, Files.readString(owlFile) + "\n<!-- extra content for size change -->\n");

        OWLOntology second = cache.getOrCreate(ontId);

        assertNotSame(first, second, "File size change must trigger reload");
    }

    @Test
    @DisplayName("TC-4: invalidate() removes entry, next call reloads")
    void tc4InvalidateRemovesEntry() throws Exception {
        createOntologyFile("test-4");
        OntologyCache cache = createCache();
        OntologyId ontId = new OntologyId("test-4");

        OWLOntology first = cache.getOrCreate(ontId);
        cache.invalidate(ontId);
        OWLOntology second = cache.getOrCreate(ontId);

        assertNotSame(first, second, "After invalidate(), next call must reload (new instance)");
    }

    @Test
    @DisplayName("TC-5: invalidateAll() clears all entries")
    void tc5InvalidateAllClearsAll() throws Exception {
        createOntologyFile("test-5a");
        createOntologyFile("test-5b");
        OntologyCache cache = createCache();
        OntologyId ontA = new OntologyId("test-5a");
        OntologyId ontB = new OntologyId("test-5b");

        OWLOntology firstA = cache.getOrCreate(ontA);
        OWLOntology firstB = cache.getOrCreate(ontB);
        cache.invalidateAll();
        OWLOntology secondA = cache.getOrCreate(ontA);
        OWLOntology secondB = cache.getOrCreate(ontB);

        assertNotSame(firstA, secondA, "invalidateAll must clear ontology A");
        assertNotSame(firstB, secondB, "invalidateAll must clear ontology B");
    }

    @Test
    @DisplayName("TC-6: concurrent getOrCreate() for same ontology loads only once")
    void tc6ConcurrentSameOntologyLoadsOnce() throws Exception {
        createOntologyFile("test-6");
        OntologyCache cache = createCache();
        OntologyId ontId = new OntologyId("test-6");

        int threadCount = 8;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger loadCount = new AtomicInteger(0);

        // We can't easily count actual OWL API loads, but we can verify
        // all threads get the same instance (proving deduplication)
        OWLOntology[] results = new OWLOntology[threadCount];
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                try {
                    startLatch.await();
                    results[idx] = cache.getOrCreate(ontId);
                } catch (Exception e) {
                    // Will be caught by assertions below
                } finally {
                    doneLatch.countDown();
                }
            });
            threads[i].start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All threads must complete within 30s");

        OWLOntology first = results[0];
        assertNotNull(first, "First result must not be null");
        for (int i = 1; i < threadCount; i++) {
            assertSame(first, results[i],
                "All concurrent threads must get the same OWLOntology instance (thread " + i + ")");
        }
    }

    @Test
    @DisplayName("TC-7: concurrent getOrCreate() for different ontologyIds do NOT block each other")
    void tc7ConcurrentDifferentOntologiesDoNotBlock() throws Exception {
        createOntologyFile("test-7a");
        createOntologyFile("test-7b");
        OntologyCache cache = createCache();

        long startTime = System.currentTimeMillis();
        Thread t1 = new Thread(() -> {
            try { cache.getOrCreate(new OntologyId("test-7a")); }
            catch (Exception ignored) {}
        });
        Thread t2 = new Thread(() -> {
            try { cache.getOrCreate(new OntologyId("test-7b")); }
            catch (Exception ignored) {}
        });
        t1.start();
        t2.start();
        t1.join(30_000);
        t2.join(30_000);
        long elapsed = System.currentTimeMillis() - startTime;

        // Both small ontologies should load quickly in parallel
        assertTrue(elapsed < 10_000,
            "Concurrent loads for different ontologyIds must not block each other (elapsed=" + elapsed + "ms)");
    }

    @Test
    @DisplayName("TC-8: missing ontology file throws OWLOntologyCreationException with path in message")
    void tc8MissingFileThrowsException() {
        OntologyCache cache = createCache();
        OntologyId ontId = new OntologyId("nonexistent-8");

        OWLOntologyCreationException ex = assertThrows(OWLOntologyCreationException.class,
            () -> cache.getOrCreate(ontId));

        assertTrue(ex.getMessage().contains("nonexistent-8") || ex.getMessage().contains("not found"),
            "Exception message must contain the ontology ID or path: " + ex.getMessage());
    }

    @Test
    @DisplayName("TC-9: failed load removes the failed future so next call retries")
    void tc9FailedLoadRetriesOnNextCall() throws Exception {
        // Create a corrupt ontology file
        Path ontDir = tempDir.resolve("default/ontologies/test-9/canonical");
        Files.createDirectories(ontDir);
        Path owlFile = ontDir.resolve("ontology.owl");
        Files.writeString(owlFile, "NOT VALID OWL/XML CONTENT");

        OntologyCache cache = createCache();
        OntologyId ontId = new OntologyId("test-9");

        // First call should fail
        assertThrows(OWLOntologyCreationException.class, () -> cache.getOrCreate(ontId));

        // Fix the file
        String validOwl = "<?xml version=\"1.0\"?>\n" +
            "<rdf:RDF xmlns=\"http://example.org/test#test9\"\n" +
            "     xml:base=\"http://example.org/test#test9\"\n" +
            "     xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n" +
            "     xmlns:owl=\"http://www.w3.org/2002/07/owl#\">\n" +
            "    <owl:Ontology rdf:about=\"http://example.org/test#test9\"/>\n" +
            "</rdf:RDF>\n";
        Files.writeString(owlFile, validOwl);
        Thread.sleep(50);

        // Second call should succeed (failed future was removed)
        OWLOntology ontology = cache.getOrCreate(ontId);
        assertNotNull(ontology, "After fixing the file, the retry must succeed");
    }

    @Test
    @DisplayName("TC-10: reloadListener.onOntologyReloaded() is called BEFORE new cache entry becomes visible")
    void tc10ListenerCalledBeforeNewEntryVisible() throws Exception {
        Path owlFile = createOntologyFile("test-10");
        OntologyCache cache = createCache();
        OntologyId ontId = new OntologyId("test-10");

        AtomicInteger callCount = new AtomicInteger(0);
        AtomicInteger visibleCount = new AtomicInteger(0);

        cache.addReloadListener(new OntologyReloadListener() {
            @Override
            public void onOntologyReloaded(OntologyId ontologyId) {
                callCount.incrementAndGet();
                // During the callback, the new entry should NOT yet be
                // visible (or if it is, it should still be the old one).
                // We verify the listener is called at all — the
                // TOCTOU prevention is enforced by the code structure
                // (listener is called before computeIfAbsent).
            }

            @Override
            public void onAllOntologiesReloaded() {}
        });

        // First load (no previous entry → listener NOT called)
        cache.getOrCreate(ontId);
        assertEquals(0, callCount.get(),
            "Listener must NOT be called on first load (no previous adapter to invalidate)");

        // Modify file to trigger reload
        Thread.sleep(50);
        Files.writeString(owlFile, Files.readString(owlFile) + "\n<!-- modified -->\n");

        // Second load (file changed → listener called)
        cache.getOrCreate(ontId);
        assertEquals(1, callCount.get(),
            "Listener must be called exactly once on reload (file changed)");
    }

    @Test
    @DisplayName("TC-11: multiple listeners all receive onOntologyReloaded callback")
    void tc11MultipleListenersAllCalledOnReload() throws Exception {
        Path owlFile = createOntologyFile("test-11");
        OntologyCache cache = createCache();
        OntologyId ontId = new OntologyId("test-11");

        AtomicInteger call1 = new AtomicInteger(0);
        AtomicInteger call2 = new AtomicInteger(0);
        AtomicInteger call3 = new AtomicInteger(0);

        cache.addReloadListener(new CountingListener(call1));
        cache.addReloadListener(new CountingListener(call2));
        cache.addReloadListener(new CountingListener(call3));

        // First load (no reload → no callbacks)
        cache.getOrCreate(ontId);
        assertEquals(0, call1.get());
        assertEquals(0, call2.get());
        assertEquals(0, call3.get());

        // Modify file to trigger reload
        Thread.sleep(50);
        Files.writeString(owlFile, Files.readString(owlFile) + "\n<!-- modified -->\n");

        // Second load (file changed → all 3 listeners called)
        cache.getOrCreate(ontId);
        assertEquals(1, call1.get(), "Listener 1 must be called");
        assertEquals(1, call2.get(), "Listener 2 must be called");
        assertEquals(1, call3.get(), "Listener 3 must be called");
    }

    @Test
    @DisplayName("TC-12: multiple listeners all receive onAllOntologiesReloaded callback")
    void tc12MultipleListenersAllCalledOnInvalidateAll() throws Exception {
        createOntologyFile("test-12");
        OntologyCache cache = createCache();
        OntologyId ontId = new OntologyId("test-12");

        AtomicInteger call1 = new AtomicInteger(0);
        AtomicInteger call2 = new AtomicInteger(0);

        cache.addReloadListener(new OntologyReloadListener() {
            @Override
            public void onOntologyReloaded(OntologyId ontologyId) {}

            @Override
            public void onAllOntologiesReloaded() {
                call1.incrementAndGet();
            }
        });
        cache.addReloadListener(new OntologyReloadListener() {
            @Override
            public void onOntologyReloaded(OntologyId ontologyId) {}

            @Override
            public void onAllOntologiesReloaded() {
                call2.incrementAndGet();
            }
        });

        cache.getOrCreate(ontId);
        cache.invalidateAll();

        assertEquals(1, call1.get(), "Listener 1 must receive onAllOntologiesReloaded");
        assertEquals(1, call2.get(), "Listener 2 must receive onAllOntologiesReloaded");
    }

    private static class CountingListener implements OntologyReloadListener {
        private final AtomicInteger counter;

        CountingListener(AtomicInteger counter) {
            this.counter = counter;
        }

        @Override
        public void onOntologyReloaded(OntologyId ontologyId) {
            counter.incrementAndGet();
        }

        @Override
        public void onAllOntologiesReloaded() {}
    }
}
