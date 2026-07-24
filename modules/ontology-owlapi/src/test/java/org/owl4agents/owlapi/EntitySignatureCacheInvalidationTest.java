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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.9.0 D1 / task 6.3: Verifies per-ontology {@link EntitySignatureCache}
 * {@link EntitySignatureCache#invalidate()} method clears all entries from
 * this ontology's Caffeine cache, and that
 * {@link EntitySignatureCacheManager#onOntologyReloaded(OntologyId)}
 * invalidates only the reloaded ontology's cache (other ontologies remain
 * intact).
 */
@DisplayName("v0.9.0 D1 / task 6.3: EntitySignatureCache per-instance invalidation")
class EntitySignatureCacheInvalidationTest {

    private static final String TEST_NS = "http://example.org/inval-test#";
    // buildOntologyWithEntities() uses name "ontology" → class IRIs are
    // TEST_NS + "ontology#C<i>" and property IRIs are TEST_NS + "P<i>".
    private static final String ONT_CLASS_C0 = TEST_NS + "ontology#C0";
    private static final String ONT_PROP_P0 = TEST_NS + "P0";

    @Test
    @DisplayName("invalidate() drops all entries (size == 0)")
    void invalidateClearsCache() {
        OWLOntology ontology = buildOntologyWithEntities();
        EntitySignatureCache cache = EntitySignatureCache.build(ontology);

        // Verify entries exist before invalidation.
        assertTrue(cache.contains("class", ONT_CLASS_C0),
            "Class C0 must be found before invalidate");
        cache.cleanUp();
        assertTrue(cache.estimatedSize() > 0,
            "Cache must have entries before invalidate, but size was " + cache.estimatedSize());

        // Invalidate and verify.
        cache.invalidate();
        cache.cleanUp();
        assertEquals(0L, cache.estimatedSize(),
            "Cache size must be 0 after invalidate, but was " + cache.estimatedSize());
        assertFalse(cache.contains("class", ONT_CLASS_C0),
            "Class C0 must not be found after invalidate");
    }

    @Test
    @DisplayName("contains() returns false for every previously-present entry after invalidate")
    void containsReturnsFalseAfterInvalidation() {
        OWLOntology ontology = buildOntologyWithEntities();
        EntitySignatureCache cache = EntitySignatureCache.build(ontology);

        // Sanity check: entries present before invalidation.
        assertTrue(cache.contains("class", ONT_CLASS_C0));
        assertTrue(cache.contains("object_property", ONT_PROP_P0));

        cache.invalidate();

        assertFalse(cache.contains("class", ONT_CLASS_C0),
            "class entry must be absent after invalidate");
        assertFalse(cache.contains("object_property", ONT_PROP_P0),
            "objprop entry must be absent after invalidate");
    }

    @Test
    @DisplayName("invalidate() on freshly-built cache is a no-op (no exception)")
    void invalidateOnFreshCacheIsNoOp() {
        OWLOntology ontology = buildOntologyWithEntities();
        EntitySignatureCache cache = EntitySignatureCache.build(ontology);
        cache.invalidate();
        cache.invalidate(); // Double invalidate should not throw.
        cache.cleanUp();
        assertEquals(0L, cache.estimatedSize(),
            "Cache must remain size 0 after repeated invalidate");
    }

    @Test
    @DisplayName("onOntologyReloaded invalidates only the reloaded ontology's cache")
    void onOntologyReloadedInvalidatesOnlyTarget() {
        OWLOntology ontA = buildOntologyWithClasses("ont-a", 5);
        OWLOntology ontB = buildOntologyWithClasses("ont-b", 5);

        EntitySignatureCacheManager manager = new EntitySignatureCacheManager();
        OntologyId idA = new OntologyId("ont-a");
        OntologyId idB = new OntologyId("ont-b");

        EntitySignatureCache cacheA = manager.getOrCreate(idA, ontA);
        EntitySignatureCache cacheB = manager.getOrCreate(idB, ontB);
        assertNotNull(cacheA, "cacheA must be built");
        assertNotNull(cacheB, "cacheB must be built");

        // Verify both caches have entries.
        assertTrue(cacheA.contains("class", TEST_NS + "ont-a#C0"));
        assertTrue(cacheB.contains("class", TEST_NS + "ont-b#C0"));

        // Reload only ontA — cacheB must remain intact.
        manager.onOntologyReloaded(idA);

        // cacheA's entries are invalidated (the instance is removed from manager).
        assertFalse(cacheA.contains("class", TEST_NS + "ont-a#C0"),
            "cacheA entries must be invalidated after onOntologyReloaded(ontA)");

        // cacheB's entries must still be present.
        assertTrue(cacheB.contains("class", TEST_NS + "ont-b#C0"),
            "cacheB entries must remain intact after onOntologyReloaded(ontA)");
    }

    private OWLOntology buildOntologyWithEntities() {
        return buildOntologyWithClasses("ontology", 10, true);
    }

    private OWLOntology buildOntologyWithClasses(String name, int classCount) {
        return buildOntologyWithClasses(name, classCount, false);
    }

    private OWLOntology buildOntologyWithClasses(String name, int classCount, boolean withProperties) {
        try {
            OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
            OWLDataFactory df = mgr.getOWLDataFactory();
            OWLOntology ontology = mgr.createOntology(IRI.create(TEST_NS + name));
            for (int i = 0; i < classCount; i++) {
                ontology.add(df.getOWLDeclarationAxiom(
                    df.getOWLClass(IRI.create(TEST_NS + name + "#C" + i))));
            }
            if (withProperties) {
                for (int i = 0; i < 5; i++) {
                    ontology.add(df.getOWLDeclarationAxiom(
                        df.getOWLObjectProperty(IRI.create(TEST_NS + "P" + i))));
                }
            }
            return ontology;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
