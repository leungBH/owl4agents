package org.owl4agents.acceptance.v086;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.owl4agents.owlapi.EntitySignatureCache;
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
 * v0.8.6 Section 5 / task 5.17: Long-running stability test that verifies
 * the cache governance fixes (P0-5) keep memory bounded under sustained
 * load. Exercises the previously-unbounded
 * {@link EntitySignatureCache} (now a global Caffeine LRU capped at 50K
 * entries per v0.8.6 D4).
 *
 * <p><b>Scenario:</b> 1000 simulated "claims" run sequentially. Each
 * iteration inserts 50 synthetic entity IRIs into the global signature
 * cache (50K total = exactly the cap) and performs a lookup. After all
 * iterations:
 * <ul>
 *   <li>{@link EntitySignatureCache#estimatedSize()} must be &le; 50_000.</li>
 *   <li>Used heap must be &lt; 50% of -Xmx4g (= 2 GiB).</li>
 * </ul>
 *
 * <p>The {@link org.owl4agents.reasoner.ReasonerLifecycleManager} LRU
 * (capped at 4) and {@code ReasonerServiceImpl.sourceConsistencyCache}
 * (capped at 200) are covered by unit tests 5.13-5.16 and 5.8
 * respectively; this acceptance test focuses on the end-to-end heap
 * stability guarantee under sustained load.</p>
 *
 * <p>Tagged {@code "acceptance"} and {@code @Timeout(1800)} (30 min) per
 * the task spec. Skipped when {@code skip.stability.test=true} system
 * property is set (for CI environments where a 30-min test is
 * impractical) or when the pizza ontology fixture is absent.</p>
 */
@Tag("acceptance")
@DisabledIfSystemProperty(named = "skip.stability.test", matches = "true")
@DisplayName("v0.8.6 Section 5 / task 5.17: Long-running stability (cache governance)")
class LongRunningStabilityTest {

    private static final String PIZZA_PATH =
        "D:\\owl4agents\\data\\workspaces\\default\\ontologies\\pizza\\canonical\\ontology.owl";

    private static final int CLAIM_COUNT = 1000;
    private static final int SIGNATURE_INSERTS_PER_CLAIM = 50;
    private static final double HEAP_FRACTION_LIMIT = 0.50;

    @AfterEach
    void cleanup() {
        EntitySignatureCache.invalidateAll();
    }

    @Test
    @Timeout(value = 1800, unit = TimeUnit.SECONDS)
    @DisplayName("1000 sequential claims keep signature cache bounded and heap < 50% of -Xmx4g")
    void cachesStayBoundedUnderSustainedLoad() throws Exception {
        Path pizza = Paths.get(PIZZA_PATH);
        assumeTrue(Files.exists(pizza),
            "Skipping: pizza ontology fixture not found at " + PIZZA_PATH);

        OWLOntology ontology = loadPizza(pizza);
        assertTrue(ontology.getClassesInSignature().size() > 0,
            "Pizza ontology must contain classes");

        // Reset the global signature cache to a known-empty state.
        EntitySignatureCache.invalidateAll();

        // Build the EntitySignatureCache from pizza once (this populates the
        // global Caffeine cache with pizza's ~115 class IRIs).
        EntitySignatureCache.build(ontology);
        long pizzaEntityCount = EntitySignatureCache.estimatedSize();
        assertTrue(pizzaEntityCount > 0,
            "Pizza signature must populate the cache");

        // 1000 simulated claims: each inserts 50 synthetic IRIs into the
        // global signature cache (50K total = exactly the cache cap).
        // Caffeine's W-TinyLFU must evict to keep size <= 50_000.
        for (int i = 0; i < CLAIM_COUNT; i++) {
            // Insert 50 synthetic class IRIs per claim.
            for (int j = 0; j < SIGNATURE_INSERTS_PER_CLAIM; j++) {
                String iri = "http://example.org/synthetic#" + i + "_" + j;
                EntitySignatureCache.put("class", iri);
            }

            // Periodic lookup to exercise the read path (drives hit/miss stats
            // and forces Caffeine maintenance windows).
            String lookupIri = "http://example.org/synthetic#" + (i / 2) + "_0";
            EntitySignatureCache.get("class", lookupIri);

            // Periodic cleanUp to let Caffeine run eviction maintenance.
            // Without this, estimatedSize may temporarily report above the
            // cap because eviction is asynchronous.
            if (i % 100 == 0) {
                EntitySignatureCache.cleanUp();
            }
        }

        // === Final assertions ===

        // Force Caffeine maintenance before checking the cap.
        EntitySignatureCache.cleanUp();
        long finalSignatureSize = EntitySignatureCache.estimatedSize();
        assertTrue(finalSignatureSize <= 50_000,
            "EntitySignatureCache must be capped at 50_000 after " + CLAIM_COUNT
                + " claims (" + (CLAIM_COUNT * SIGNATURE_INSERTS_PER_CLAIM)
                + " total inserts), but was " + finalSignatureSize);

        // === Heap assertion: used heap must be < 50% of -Xmx4g (= 2 GiB) ===
        // This is the primary stability guarantee from v0.8.6 Section 5.
        Runtime rt = Runtime.getRuntime();
        long maxHeap = rt.maxMemory(); // -Xmx4g = ~4 GiB
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
