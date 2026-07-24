package org.owl4agents.owlapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.OntologyId;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.9.0 D1 / task 7.1: Verifies per-ontology {@link EntitySignatureCache}
 * isolation — building caches for multiple ontologies simultaneously does not
 * cause cross-ontology eviction. This is the core regression test for the P0
 * fix: in v0.8.8, a global 50K-entry cache evicted Pizza's entries when
 * HPO+Mondo were loaded; with per-ontology caches, each ontology's entries
 * are isolated.
 */
@DisplayName("v0.9.0 D1 / task 7.1: Per-ontology cache isolation")
class EntitySignatureCachePerOntologyTest {

    private static final String NS_A = "http://example.org/ont-a#";
    private static final String NS_B = "http://example.org/ont-b#";
    private static final String NS_C = "http://example.org/ont-c#";

    @Test
    @DisplayName("Building 3 ontologies simultaneously — each cache retains its entities")
    void perOntologyCacheIsolatesEntries() {
        // Simulate pizza (small, 100 classes) + hpo (large, 500 classes) + mondo (large, 400 classes)
        OWLOntology ontA = buildOntology(NS_A, "ont-a", 100);
        OWLOntology ontB = buildOntology(NS_B, "ont-b", 500);
        OWLOntology ontC = buildOntology(NS_C, "ont-c", 400);

        EntitySignatureCacheManager manager = new EntitySignatureCacheManager();
        OntologyId idA = new OntologyId("ont-a");
        OntologyId idB = new OntologyId("ont-b");
        OntologyId idC = new OntologyId("ont-c");

        // Build all 3 caches.
        EntitySignatureCache cacheA = manager.getOrCreate(idA, ontA);
        EntitySignatureCache cacheB = manager.getOrCreate(idB, ontB);
        EntitySignatureCache cacheC = manager.getOrCreate(idC, ontC);
        assertNotNull(cacheA, "cacheA must be built");
        assertNotNull(cacheB, "cacheB must be built");
        assertNotNull(cacheC, "cacheC must be built");

        // Verify ontA's entities are still found AFTER building ontB and ontC.
        // In v0.8.8, the global cache would have evicted ontA's entries.
        assertTrue(cacheA.contains("class", NS_A + "C0"),
            "ont-a entity C0 must be found after building ont-b and ont-c caches");
        assertTrue(cacheA.contains("class", NS_A + "C99"),
            "ont-a entity C99 must be found after building ont-b and ont-c caches");

        // Verify each cache has its own entities.
        assertTrue(cacheB.contains("class", NS_B + "C0"),
            "ont-b entity C0 must be found");
        assertTrue(cacheC.contains("class", NS_C + "C0"),
            "ont-c entity C0 must be found");

        // Verify cross-ontology: ontA's cache should NOT contain ontB's entities.
        assertTrue(!cacheA.contains("class", NS_B + "C0"),
            "ont-a cache must NOT contain ont-b entities (per-ontology isolation)");
    }

    @Test
    @DisplayName("Per-ontology maxSize scales with classCount")
    void maxSizeScalesWithClassCount() {
        // Small ontology: 50 classes → maxSize = max(1000, 100) = 1000
        OWLOntology small = buildOntology("http://example.org/small#", "small", 50);
        EntitySignatureCache smallCache = EntitySignatureCache.build(small);
        smallCache.cleanUp();
        assertTrue(smallCache.estimatedSize() <= 1000,
            "Small ontology (50 classes) cache must be <= 1000, but was " + smallCache.estimatedSize());

        // Large ontology: 600 classes → maxSize = max(1000, 1200) = 1200
        OWLOntology large = buildOntology("http://example.org/large#", "large", 600);
        EntitySignatureCache largeCache = EntitySignatureCache.build(large);
        largeCache.cleanUp();
        assertTrue(largeCache.estimatedSize() >= 600,
            "Large ontology (600 classes) must retain all 600 classes, but was " + largeCache.estimatedSize());
        assertTrue(largeCache.estimatedSize() <= 1200,
            "Large ontology cache must be <= 1200 (classCount*2), but was " + largeCache.estimatedSize());
    }

    private OWLOntology buildOntology(String ns, String name, int classCount) {
        try {
            OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
            OWLDataFactory df = mgr.getOWLDataFactory();
            OWLOntology ontology = mgr.createOntology(IRI.create(ns + name));
            for (int i = 0; i < classCount; i++) {
                OWLDeclarationAxiom decl = df.getOWLDeclarationAxiom(
                    df.getOWLClass(IRI.create(ns + "C" + i)));
                ontology.add(decl);
            }
            return ontology;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
