package org.owl4agents.owlapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.9.0 D1 / task 6.2: Verifies per-ontology {@link EntitySignatureCache}
 * Caffeine cache enforces its LRU cap. Each ontology's cache is sized as
 * {@code max(1000, classCount * 2)}. Building a cache from an ontology whose
 * total entity count (classes + properties + individuals) exceeds
 * {@code maxSize} must result in an estimated size at or below {@code maxSize}
 * once Caffeine's eviction maintenance has run.
 *
 * <p>Each test builds a fresh per-ontology cache instance, so there is no
 * cross-test interference (unlike the old global static cache which required
 * {@code invalidateAll()} before each test).</p>
 */
@DisplayName("v0.9.0 D1 / task 6.2: EntitySignatureCache per-instance LRU eviction")
class EntitySignatureCacheLruTest {

    private static final String TEST_NS = "http://example.org/lru-test#";

    @Test
    @DisplayName("Ontology with > maxSize entities keeps estimatedSize <= maxSize")
    void lruCapsAtMaxSize() {
        // classCount=400 → maxSize = max(1000, 800) = 1000.
        // Add 800 object properties → total = 1200 entities > 1000 maxSize.
        OWLOntology ontology = buildOntology(400, 800);
        EntitySignatureCache cache = EntitySignatureCache.build(ontology);

        // Force Caffeine to run eviction maintenance synchronously.
        cache.cleanUp();

        long size = cache.estimatedSize();
        assertTrue(size <= 1000,
            "Cache size must be <= 1000 after building 1200 entities into a 1000-capped cache, but was " + size);
    }

    @Test
    @DisplayName("Ontology with < maxSize entities keeps all entries (no premature eviction)")
    void belowMaxSizeKeepsAllEntries() {
        // classCount=50 → maxSize = max(1000, 100) = 1000.
        // Add 20 object properties → total = 70 entities << 1000 maxSize.
        OWLOntology ontology = buildOntology(50, 20);
        EntitySignatureCache cache = EntitySignatureCache.build(ontology);
        cache.cleanUp();

        long size = cache.estimatedSize();
        assertTrue(size >= 70,
            "Cache size must be >= 70 for 50 classes + 20 properties, but was " + size);
        assertTrue(size <= 1000,
            "Cache size must be <= 1000 (maxSize floor), but was " + size);
    }

    @Test
    @DisplayName("Large ontology (600 classes) gets maxSize = 1200 (classCount * 2)")
    void largeOntologyScalesMaxSize() {
        // classCount=600 → maxSize = max(1000, 1200) = 1200.
        // All 600 classes fit without eviction.
        OWLOntology ontology = buildOntology(600, 0);
        EntitySignatureCache cache = EntitySignatureCache.build(ontology);
        cache.cleanUp();

        long size = cache.estimatedSize();
        assertTrue(size >= 600,
            "Cache must retain all 600 classes, but size was " + size);
        assertTrue(size <= 1200,
            "Cache size must be <= 1200 (classCount*2), but was " + size);
    }

    private OWLOntology buildOntology(int classCount, int propCount) {
        try {
            OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
            OWLDataFactory df = mgr.getOWLDataFactory();
            OWLOntology ontology = mgr.createOntology(IRI.create(TEST_NS + "ontology"));
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
