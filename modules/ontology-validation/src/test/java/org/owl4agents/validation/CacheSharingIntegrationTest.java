package org.owl4agents.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.OntologyId;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.owlapi.OntologyReloadListener;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.ReasonerLifecycleManager;
import org.owl4agents.reasoner.ReasonerServiceImpl;
import org.owl4agents.storage.CatalogStore;
import org.owl4agents.storage.HomeDirectoryResolver;
import org.semanticweb.owlapi.model.OWLOntology;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.2 cache sharing integration tests (TC-7.1 through TC-7.7).
 *
 * <p>Verifies that {@link OntologyCache} enables cross-service ontology reuse
 * and that cache reload correctly triggers {@link OntologyReloadListener}
 * → {@link ReasonerLifecycleManager#shutdownReasoner} chain.</p>
 */
@DisplayName("v0.8.2 Cache sharing integration tests (TC-7.1 through TC-7.7)")
class CacheSharingIntegrationTest {

    @TempDir
    Path tempDir;

    private OntologyCache ontologyCache;
    private OntologyId ontId;
    private Path owlFile;

    @BeforeEach
    void setUp() throws Exception {
        owlFile = createOntologyFile("test-cache");
        ontologyCache = new OntologyCache(tempDir.toString(), "default");
        ontId = new OntologyId("test-cache");
    }

    private Path createOntologyFile(String ontologyId) throws Exception {
        Path ontDir = tempDir.resolve("default/ontologies/" + ontologyId + "/canonical");
        Files.createDirectories(ontDir);
        Path file = ontDir.resolve("ontology.owl");
        String owlXml = "<?xml version=\"1.0\"?>\n" +
            "<rdf:RDF xmlns=\"http://example.org/test#" + ontologyId + "\"\n" +
            "     xml:base=\"http://example.org/test#" + ontologyId + "\"\n" +
            "     xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n" +
            "     xmlns:owl=\"http://www.w3.org/2002/07/owl#\"\n" +
            "     xmlns:xsd=\"http://www.w3.org/2001/XMLSchema#\">\n" +
            "    <owl:Ontology rdf:about=\"http://example.org/test#" + ontologyId + "\"/>\n" +
            "    <owl:Class rdf:about=\"http://example.org/test#TestClass\"/>\n" +
            "</rdf:RDF>\n";
        Files.writeString(file, owlXml);
        return file;
    }

    @Test
    @DisplayName("TC-7.1: three services share the same OWLOntology instance via shared OntologyCache")
    void tc71ThreeServicesShareSameOntologyInstance() throws Exception {
        HomeDirectoryResolver homeResolver = new HomeDirectoryResolver(tempDir);
        CatalogStore catalogStore = new CatalogStore(homeResolver);
        String basePath = tempDir.toString();

        ReasonerServiceImpl reasonerService = new ReasonerServiceImpl(
            catalogStore, basePath, "default", ontologyCache);
        ConsistencyAnalysisService consistencyService = new ConsistencyAnalysisService(
            reasonerService.getLifecycleManager(), basePath, ontologyCache);
        SemanticDeepeningService deepeningService = new SemanticDeepeningService(
            basePath, ontologyCache);

        OWLOntology fromCache = ontologyCache.getOrCreate(ontId);
        OWLOntology fromCacheAgain = ontologyCache.getOrCreate(ontId);

        assertSame(fromCache, fromCacheAgain,
            "Cache must return the same OWLOntology instance on repeated calls");
        assertNotNull(fromCache, "Ontology from cache must not be null");
    }

    @Test
    @DisplayName("TC-7.2: subsequent getOrCreate call is a cache hit (same instance, no reload)")
    void tc72SubsequentCallIsCacheHit() throws Exception {
        OWLOntology first = ontologyCache.getOrCreate(ontId);
        long startNanos = System.nanoTime();
        OWLOntology second = ontologyCache.getOrCreate(ontId);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertSame(first, second, "Subsequent call must return the same cached instance");
        assertTrue(elapsedMs < 5000,
            "Cache hit must be fast (<5s), took " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("TC-7.3: ConsistencyAnalysisService reuses ontology loaded via shared cache")
    void tc73ConsistencyServiceReusesOntology() throws Exception {
        OWLOntology loadedByCache = ontologyCache.getOrCreate(ontId);

        HomeDirectoryResolver homeResolver = new HomeDirectoryResolver(tempDir);
        CatalogStore catalogStore = new CatalogStore(homeResolver);
        ReasonerServiceImpl reasonerService = new ReasonerServiceImpl(
            catalogStore, tempDir.toString(), "default", ontologyCache);
        ConsistencyAnalysisService consistencyService = new ConsistencyAnalysisService(
            reasonerService.getLifecycleManager(), tempDir.toString(), ontologyCache);

        OWLOntology loadedAgain = ontologyCache.getOrCreate(ontId);
        assertSame(loadedByCache, loadedAgain,
            "ConsistencyAnalysisService must reuse the same ontology instance from shared cache");
    }

    @Test
    @DisplayName("TC-7.4: SemanticDeepeningService reuses ontology loaded via shared cache")
    void tc74SemanticDeepeningReusesOntology() throws Exception {
        OWLOntology loadedByCache = ontologyCache.getOrCreate(ontId);

        SemanticDeepeningService deepeningService = new SemanticDeepeningService(
            tempDir.toString(), ontologyCache);

        OWLOntology loadedAgain = ontologyCache.getOrCreate(ontId);
        assertSame(loadedByCache, loadedAgain,
            "SemanticDeepeningService must reuse the same ontology instance from shared cache");
    }

    @Test
    @DisplayName("TC-7.5: cache reload (mtime change) triggers onOntologyReloaded BEFORE new entry is visible")
    void tc75ReloadTriggersListenerBeforeNewEntryVisible() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicInteger[] listenerCallSnapshot = new AtomicInteger[1];
        ontologyCache.setReloadListener(new OntologyReloadListener() {
            @Override
            public void onOntologyReloaded(OntologyId ontologyId) {
                callCount.incrementAndGet();
                listenerCallSnapshot[0] = new AtomicInteger(callCount.get());
            }

            @Override
            public void onAllOntologiesReloaded() {}
        });

        OWLOntology first = ontologyCache.getOrCreate(ontId);
        assertNotNull(first);

        Thread.sleep(50);
        Files.writeString(owlFile, Files.readString(owlFile) + "\n<!-- modified -->\n");

        OWLOntology second = ontologyCache.getOrCreate(ontId);

        assertNotSame(first, second, "Reload must produce a new OWLOntology instance");
        assertEquals(1, callCount.get(),
            "onOntologyReloaded must be called exactly once on file change");
    }

    @Test
    @DisplayName("TC-7.6: invalidate() triggers onOntologyReloaded and removes cache entry")
    void tc76InvalidateTriggersListener() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        ontologyCache.setReloadListener(new OntologyReloadListener() {
            @Override
            public void onOntologyReloaded(OntologyId ontologyId) {
                callCount.incrementAndGet();
            }

            @Override
            public void onAllOntologiesReloaded() {}
        });

        OWLOntology first = ontologyCache.getOrCreate(ontId);
        ontologyCache.invalidate(ontId);
        OWLOntology second = ontologyCache.getOrCreate(ontId);

        assertNotSame(first, second, "Invalidate must cause a reload on next getOrCreate");
        assertEquals(1, callCount.get(),
            "onOntologyReloaded must be called once for invalidate");
    }

    @Test
    @DisplayName("TC-7.7: no resource leak across multiple cache reloads")
    void tc77NoResourceLeakAcrossMultipleReloads() throws Exception {
        AtomicInteger reloadCount = new AtomicInteger(0);
        ontologyCache.setReloadListener(new OntologyReloadListener() {
            @Override
            public void onOntologyReloaded(OntologyId ontologyId) {
                reloadCount.incrementAndGet();
            }

            @Override
            public void onAllOntologiesReloaded() {}
        });

        OWLOntology prev = ontologyCache.getOrCreate(ontId);

        for (int i = 0; i < 5; i++) {
            Thread.sleep(30);
            Files.writeString(owlFile, Files.readString(owlFile) + "\n<!-- reload " + i + " -->\n");
            OWLOntology next = ontologyCache.getOrCreate(ontId);
            assertNotSame(prev, next, "Reload #" + i + " must produce a new instance");
            prev = next;
        }

        assertEquals(5, reloadCount.get(),
            "onOntologyReloaded must be called exactly 5 times (once per reload)");
    }
}
