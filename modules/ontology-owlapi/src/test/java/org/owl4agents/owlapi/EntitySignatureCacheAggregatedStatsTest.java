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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.9.0 D2 / task 7.3: Verifies {@link EntitySignatureCacheManager#aggregatedStats()}
 * correctly sums hitCount, missCount, evictionCount, and loadCount across all
 * per-ontology cache instances.
 */
@DisplayName("v0.9.0 D2 / task 7.3: Aggregated stats across per-ontology caches")
class EntitySignatureCacheAggregatedStatsTest {

    private static final String NS = "http://example.org/agg-test#";

    @Test
    @DisplayName("aggregatedStats() sums hitCount/missCount across 2 instances")
    void aggregatedStatsSumsAcrossInstances() {
        OWLOntology ontA = buildOntology("ont-a", 10);
        OWLOntology ontB = buildOntology("ont-b", 10);

        EntitySignatureCacheManager manager = new EntitySignatureCacheManager();
        EntitySignatureCache cacheA = manager.getOrCreate(new OntologyId("ont-a"), ontA);
        EntitySignatureCache cacheB = manager.getOrCreate(new OntologyId("ont-b"), ontB);

        // Generate hits and misses on cacheA.
        for (int i = 0; i < 10; i++) {
            cacheA.contains("class", NS + "ont-a#C" + i); // hit
        }
        for (int i = 0; i < 5; i++) {
            cacheA.contains("class", NS + "ont-a#Missing" + i); // miss
        }

        // Generate hits and misses on cacheB.
        for (int i = 0; i < 8; i++) {
            cacheB.contains("class", NS + "ont-b#C" + i); // hit
        }
        for (int i = 0; i < 3; i++) {
            cacheB.contains("class", NS + "ont-b#Missing" + i); // miss
        }

        cacheA.cleanUp();
        cacheB.cleanUp();

        long perA_Hits = cacheA.stats().hitCount();
        long perB_Hits = cacheB.stats().hitCount();
        long perA_Miss = cacheA.stats().missCount();
        long perB_Miss = cacheB.stats().missCount();

        long aggHits = manager.aggregatedStats().hitCount();
        long aggMiss = manager.aggregatedStats().missCount();

        assertEquals(perA_Hits + perB_Hits, aggHits,
            "aggregated hitCount must equal sum of per-ontology hitCounts");
        assertEquals(perA_Miss + perB_Miss, aggMiss,
            "aggregated missCount must equal sum of per-ontology missCounts");
        assertTrue(aggHits >= 18,
            "aggregated hitCount must be >= 18 (10+8), but was " + aggHits);
        assertTrue(aggMiss >= 8,
            "aggregated missCount must be >= 8 (5+3), but was " + aggMiss);
    }

    @Test
    @DisplayName("aggregatedStats() on empty manager returns zero stats")
    void aggregatedStatsOnEmptyManager() {
        EntitySignatureCacheManager manager = new EntitySignatureCacheManager();
        assertEquals(0L, manager.aggregatedStats().hitCount(),
            "Empty manager aggregated hitCount must be 0");
        assertEquals(0L, manager.aggregatedStats().missCount(),
            "Empty manager aggregated missCount must be 0");
        assertEquals(0L, manager.aggregatedStats().evictionCount(),
            "Empty manager aggregated evictionCount must be 0");
    }

    private OWLOntology buildOntology(String name, int classCount) {
        try {
            OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
            OWLDataFactory df = mgr.getOWLDataFactory();
            OWLOntology ontology = mgr.createOntology(IRI.create(NS + name));
            for (int i = 0; i < classCount; i++) {
                ontology.add(df.getOWLDeclarationAxiom(
                    df.getOWLClass(IRI.create(NS + name + "#C" + i))));
            }
            return ontology;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
