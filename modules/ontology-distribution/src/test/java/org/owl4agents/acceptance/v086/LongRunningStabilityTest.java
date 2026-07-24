package org.owl4agents.acceptance.v086;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.owl4agents.core.OntologyId;
import org.owl4agents.owlapi.EntitySignatureCache;
import org.owl4agents.owlapi.EntitySignatureCacheManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * v0.8.6 Section 5 / task 5.17 (rewritten v0.9.0 D1 / task 6.4): Long-running
 * stability test that verifies the per-ontology cache governance keeps memory
 * bounded under sustained read load.
 *
 * <p><b>v0.9.0 D1 changes:</b> The old test inserted synthetic IRIs into the
 * global static cache via {@code EntitySignatureCache.put()}. The per-ontology
 * API no longer exposes {@code put()}/{@code get()} — entries are populated
 * only via {@link EntitySignatureCache#build(OWLOntology)}. This rewritten test
 * builds a per-ontology pizza cache via
 * {@link EntitySignatureCacheManager#getOrCreate} and exercises the read path
 * ({@code contains()}) under sustained load.</p>
 *
 * <p><b>Scenario:</b> 1000 simulated "claims" run sequentially. Each
 * iteration performs a {@code contains()} lookup (exercising the read path
 * and driving hit/miss stats). After all iterations:
 * <ul>
 *   <li>Per-ontology {@link EntitySignatureCache#estimatedSize()} must be
 *       bounded by {@code max(1000, classCount * 2)}.</li>
 *   <li>Used heap must be &lt; 50% of -Xmx4g (= 2 GiB).</li>
 * </ul>
 *
 * <p>Tagged {@code "acceptance"} and {@code @Timeout(1800)} (30 min) per
 * the task spec. Skipped when {@code skip.stability.test=true} system
 * property is set or when the pizza ontology fixture is absent.</p>
 */
@Tag("acceptance")
@DisabledIfSystemProperty(named = "skip.stability.test", matches = "true")
@DisplayName("v0.8.6 Section 5 / task 5.17 (v0.9.0 D1): Long-running stability (per-ontology cache)")
class LongRunningStabilityTest {

    private static final String PIZZA_PATH =
        "D:\\owl4agents\\data\\workspaces\\default\\ontologies\\pizza\\canonical\\ontology.owl";
    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";

    private static final int CLAIM_COUNT = 1000;
    private static final double HEAP_FRACTION_LIMIT = 0.50;

    private EntitySignatureCacheManager manager;

    @AfterEach
    void cleanup() {
        if (manager != null) {
            manager.onAllOntologiesReloaded();
        }
    }

    @Test
    @Timeout(value = 1800, unit = TimeUnit.SECONDS)
    @DisplayName("1000 sequential claims keep per-ontology cache bounded and heap < 50% of -Xmx4g")
    void cachesStayBoundedUnderSustainedLoad() throws Exception {
        Path pizza = Paths.get(PIZZA_PATH);
        assumeTrue(Files.exists(pizza),
            "Skipping: pizza ontology fixture not found at " + PIZZA_PATH);

        OWLOntology ontology = loadPizza(pizza);
        int classCount = ontology.getClassesInSignature().size();
        assertTrue(classCount > 0,
            "Pizza ontology must contain classes");

        // Build the per-ontology EntitySignatureCache via the manager.
        manager = new EntitySignatureCacheManager();
        OntologyId ontId = new OntologyId("pizza");
        EntitySignatureCache cache = manager.getOrCreate(ontId, ontology);
        assertTrue(cache != null, "Per-ontology cache must be built successfully");

        // Verify pizza entities are in the cache.
        assertTrue(cache.contains("class", PIZZA_NS + "Pizza"),
            "Pizza class must be found in the per-ontology cache");

        long initialSize = cache.estimatedSize();
        assertTrue(initialSize > 0,
            "Pizza signature must populate the per-ontology cache");

        // 1000 simulated claims: each performs a contains() lookup to
        // exercise the read path under sustained load.
        for (int i = 0; i < CLAIM_COUNT; i++) {
            // Alternate between known entities (hits) and unknown entities (misses).
            String knownIri = PIZZA_NS + "Pizza";
            String unknownIri = "http://example.org/synthetic#" + i;
            cache.contains("class", knownIri);
            cache.contains("class", unknownIri);

            // Periodic cleanUp to let Caffeine run maintenance.
            if (i % 100 == 0) {
                cache.cleanUp();
            }
        }

        // === Final assertions ===

        // Force Caffeine maintenance before checking the size.
        cache.cleanUp();
        long finalSize = cache.estimatedSize();

        // maxSize = max(1000, classCount * 2). Pizza has ~115 classes → maxSize = 1000.
        // The cache should only contain pizza's entities (no synthetic inserts).
        long expectedMaxSize = Math.max(1000, classCount * 2);
        assertTrue(finalSize <= expectedMaxSize,
            "Per-ontology cache must be bounded by max(1000, classCount*2) = " + expectedMaxSize
                + " after " + CLAIM_COUNT + " claims, but was " + finalSize);

        // Verify aggregatedStats works.
        assertTrue(manager.aggregatedStats().requestCount() > 0,
            "aggregatedStats() must report requests from the sustained load");

        // === Heap assertion: used heap must be < 50% of -Xmx4g (= 2 GiB) ===
        Runtime rt = Runtime.getRuntime();
        long maxHeap = rt.maxMemory();
        long usedHeap = rt.totalMemory() - rt.freeMemory();
        double heapFraction = (double) usedHeap / maxHeap;

        assertTrue(heapFraction < HEAP_FRACTION_LIMIT,
            "Used heap (" + (usedHeap / 1024 / 1024) + " MiB) must be < "
                + (HEAP_FRACTION_LIMIT * 100) + "% of max heap ("
                + (maxHeap / 1024 / 1024) + " MiB) after " + CLAIM_COUNT
                + " claims, but was " + (heapFraction * 100) + "%");
    }

    private OWLOntology loadPizza(Path path) throws Exception {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        try (InputStream is = Files.newInputStream(path)) {
            return mgr.loadOntologyFromOntologyDocument(is);
        }
    }
}
