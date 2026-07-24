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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.9.0 D2 / task 7.9: Verifies
 * {@link EntitySignatureCacheManager#onAllOntologiesReloaded()} calls
 * {@code invalidate()} on each per-ontology instance and clears the manager
 * map. All caches must be empty after the call.
 */
@DisplayName("v0.9.0 D2 / task 7.9: onAllOntologiesReloaded clears all caches")
class AllOntologiesReloadClearsAllCachesTest {

    private static final String NS = "http://example.org/all-reload-test#";

    @Test
    @DisplayName("onAllOntologiesReloaded() invalidates all per-ontology caches")
    void onAllOntologiesReloadedClearsAll() {
        OWLOntology ontA = buildOntology("ont-a", 10);
        OWLOntology ontB = buildOntology("ont-b", 10);

        EntitySignatureCacheManager manager = new EntitySignatureCacheManager();
        EntitySignatureCache cacheA = manager.getOrCreate(new OntologyId("ont-a"), ontA);
        EntitySignatureCache cacheB = manager.getOrCreate(new OntologyId("ont-b"), ontB);

        // Verify both caches have entries.
        assertTrue(cacheA.contains("class", NS + "ont-a#C0"));
        assertTrue(cacheB.contains("class", NS + "ont-b#C0"));

        // Reload all — all caches must be invalidated.
        manager.onAllOntologiesReloaded();

        // Both caches should now be empty (invalidate was called).
        assertFalse(cacheA.contains("class", NS + "ont-a#C0"),
            "cacheA must be invalidated after onAllOntologiesReloaded");
        assertFalse(cacheB.contains("class", NS + "ont-b#C0"),
            "cacheB must be invalidated after onAllOntologiesReloaded");

        // The manager map should be cleared — rebuilding should create new instances.
        EntitySignatureCache cacheA2 = manager.getOrCreate(new OntologyId("ont-a"), ontA);
        assertTrue(cacheA2 != cacheA,
            "After onAllOntologiesReloaded, getOrCreate must return a new instance (map was cleared)");
        assertTrue(cacheA2.contains("class", NS + "ont-a#C0"),
            "Newly rebuilt cacheA must contain entities");
    }

    @Test
    @DisplayName("onAllOntologiesReloaded() on empty manager is a no-op")
    void onAllOntologiesReloadedOnEmptyManager() {
        EntitySignatureCacheManager manager = new EntitySignatureCacheManager();
        manager.onAllOntologiesReloaded(); // Should not throw.
        // Manager is still usable.
        OWLOntology ontology = buildOntology("ont-a", 5);
        EntitySignatureCache cache = manager.getOrCreate(new OntologyId("ont-a"), ontology);
        assertTrue(cache != null, "Manager must be usable after empty onAllOntologiesReloaded");
        assertTrue(cache.contains("class", NS + "ont-a#C0"));
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
