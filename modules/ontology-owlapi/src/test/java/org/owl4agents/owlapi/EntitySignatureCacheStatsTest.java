package org.owl4agents.owlapi;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.9.0 D1 / task 6.1: Verifies per-ontology {@link EntitySignatureCache}
 * Caffeine {@code recordStats()} instrumentation reports accurate hit rate
 * and eviction count. Each test builds a fresh per-ontology cache instance
 * via {@link EntitySignatureCache#build(OWLOntology)}, so stats are isolated
 * per test (no cross-test contamination from a shared static cache).
 *
 * <p>Scenario: build a cache from a small ontology, then call
 * {@code contains()} for existing entities (hits) and non-existent entities
 * (misses). A second scenario builds a cache from an ontology whose total
 * entity count exceeds {@code maxSize} and asserts {@code evictionCount > 0}.</p>
 */
@DisplayName("v0.9.0 D1 / task 6.1: EntitySignatureCache per-instance stats")
class EntitySignatureCacheStatsTest {

    private static final String TEST_NS = "http://example.org/stats-test#";

    @Test
    @DisplayName("contains() hits and misses produce correct hitCount/missCount")
    void hitRateForKnownAndUnknownEntities() {
        OWLOntology ontology = buildOntologyWithClasses(10);
        EntitySignatureCache cache = EntitySignatureCache.build(ontology);

        // 10 hits — query entities that were built into the cache
        for (int i = 0; i < 10; i++) {
            cache.contains("class", TEST_NS + "C" + i);
        }

        // 10 misses — query entities that do not exist
        for (int i = 0; i < 10; i++) {
            cache.contains("class", TEST_NS + "Missing" + i);
        }

        cache.cleanUp();
        CacheStats stats = cache.stats();
        assertTrue(stats.hitCount() >= 10,
            "hitCount must be >= 10, but was " + stats.hitCount());
        assertTrue(stats.missCount() >= 10,
            "missCount must be >= 10, but was " + stats.missCount());
        assertEquals(20, stats.requestCount(),
            "requestCount must be 20, but was " + stats.requestCount());
    }

    @Test
    @DisplayName("Building ontology with > maxSize entities triggers eviction")
    void evictionCountNonZeroWhenEntitiesExceedMaxSize() {
        // classCount=400 → maxSize = max(1000, 800) = 1000.
        // Add 800 object properties → total = 1200 entities > 1000 maxSize.
        OWLOntology ontology = buildOntologyWithClassesAndProperties(400, 800);
        EntitySignatureCache cache = EntitySignatureCache.build(ontology);

        cache.cleanUp();
        CacheStats stats = cache.stats();
        assertTrue(stats.evictionCount() > 0,
            "evictionCount must be > 0 after inserting 1200 entities into a 1000-capped cache, but was "
                + stats.evictionCount());
        assertTrue(cache.estimatedSize() <= 1000,
            "estimatedSize must be <= 1000 after eviction, but was " + cache.estimatedSize());
    }

    /** Build an ontology with {@code classCount} declared classes. */
    private OWLOntology buildOntologyWithClasses(int classCount) {
        try {
            OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
            OWLDataFactory df = mgr.getOWLDataFactory();
            OWLOntology ontology = mgr.createOntology(IRI.create(TEST_NS + "ontology"));
            for (int i = 0; i < classCount; i++) {
                OWLDeclarationAxiom decl = df.getOWLDeclarationAxiom(
                    df.getOWLClass(IRI.create(TEST_NS + "C" + i)));
                ontology.add(decl);
            }
            return ontology;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Build an ontology with {@code classCount} classes and {@code propCount} object properties. */
    private OWLOntology buildOntologyWithClassesAndProperties(int classCount, int propCount) {
        try {
            OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
            OWLDataFactory df = mgr.getOWLDataFactory();
            OWLOntology ontology = mgr.createOntology(IRI.create(TEST_NS + "ontology-big"));
            for (int i = 0; i < classCount; i++) {
                ontology.add(df.getOWLDeclarationAxiom(
                    df.getOWLClass(IRI.create(TEST_NS + "C" + i))));
            }
            for (int i = 0; i < propCount; i++) {
                ontology.add(df.getOWLDeclarationAxiom(
                    df.getOWLObjectProperty(IRI.create(TEST_NS + "P" + i))));
            }
            return ontology;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
