package org.owl4agents.owlapi;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.OntologyId;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Task 7.6 unit test: the core v0.8.8 P0 regression — pizza claims returned
 * {@code out_of_scope} because HPO/Mondo loading evicted pizza entries from
 * the global static cache. With v0.9.0 D1 per-ontology caches, pizza's cache
 * MUST remain intact when HPO and Mondo caches are built.
 *
 * <p>This test builds three per-ontology caches (pizza, hpo-synthetic,
 * mondo-synthetic) via {@link EntitySignatureCacheManager} and verifies that
 * pizza entities are still found via {@code contains()} after the other
 * ontologies' caches are built.</p>
 *
 * <p>If the pizza ontology fixture is missing, the test is skipped via
 * {@link assumeTrue}.</p>
 */
@DisplayName("v0.9.0 D1 / task 7.6: scope precheck survives multi-ontology load")
class ScopePrecheckMultiOntologyTest {

    private static final String PIZZA_PATH =
        "D:\\owl4agents\\data\\workspaces\\default\\ontologies\\pizza\\canonical\\ontology.owl";
    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";

    @Test
    @DisplayName("Pizza entity found after HPO+Mondo caches built (per-ontology isolation)")
    void pizzaEntitySurvivesMultiOntologyLoad() throws Exception {
        Path pizzaFile = Paths.get(PIZZA_PATH);
        assumeTrue(Files.exists(pizzaFile),
            "Skipping: pizza ontology fixture not found at " + PIZZA_PATH);

        // Load pizza ontology
        OWLOntology pizzaOnt = loadOntology(pizzaFile);
        int pizzaClassCount = pizzaOnt.getClassesInSignature().size();
        assertTrue(pizzaClassCount > 0, "Pizza ontology must have classes");

        // Build synthetic HPO and Mondo ontologies (small, just enough to
        // exercise the per-ontology cache paths).
        OWLOntology hpoOnt = buildSyntheticOboOntology(
            "http://purl.obolibrary.org/obo/hp.owl",
            "http://purl.obolibrary.org/obo/HP_0001250",
            "http://purl.obolibrary.org/obo/HP_0000118");
        OWLOntology mondoOnt = buildSyntheticOboOntology(
            "http://purl.obolibrary.org/obo/mondo.owl",
            "http://purl.obolibrary.org/obo/MONDO_0000001",
            "http://purl.obolibrary.org/obo/MONDO_0004992");

        EntitySignatureCacheManager manager = new EntitySignatureCacheManager();
        OntologyId pizzaId = new OntologyId("pizza");
        OntologyId hpoId = new OntologyId("hpo");
        OntologyId mondoId = new OntologyId("mondo");

        // Step 1: Build pizza cache and verify pizza entities present.
        EntitySignatureCache pizzaCache = manager.getOrCreate(pizzaId, pizzaOnt);
        assertNotNull(pizzaCache, "Pizza cache must be built");
        assertTrue(pizzaCache.contains("class", PIZZA_NS + "Pizza"),
            "Pizza class must be found before HPO/Mondo load");
        assertTrue(pizzaCache.contains("class", PIZZA_NS + "Margherita"),
            "Margherita class must be found before HPO/Mondo load");
        assertTrue(pizzaCache.contains("object_property", PIZZA_NS + "hasTopping"),
            "hasTopping object property must be found before HPO/Mondo load");

        long pizzaSizeBefore = pizzaCache.estimatedSize();

        // Step 2: Build HPO and Mondo caches (this is what previously evicted
        // pizza entries from the global static cache).
        EntitySignatureCache hpoCache = manager.getOrCreate(hpoId, hpoOnt);
        EntitySignatureCache mondoCache = manager.getOrCreate(mondoId, mondoOnt);
        assertNotNull(hpoCache, "HPO cache must be built");
        assertNotNull(mondoCache, "Mondo cache must be built");

        // Step 3: Verify HPO and Mondo caches work independently.
        assertTrue(hpoCache.contains("class", "http://purl.obolibrary.org/obo/HP_0001250"),
            "HPO class HP_0001250 must be found in HPO cache");
        assertTrue(mondoCache.contains("class", "http://purl.obolibrary.org/obo/MONDO_0000001"),
            "Mondo class MONDO_0000001 must be found in Mondo cache");

        // Step 4: CRITICAL — verify pizza entities are STILL found after
        // HPO+Mondo load. Under v0.8.8 global static cache, these would have
        // been evicted (the P0 regression).
        assertTrue(pizzaCache.contains("class", PIZZA_NS + "Pizza"),
            "REGRESSION: Pizza class must STILL be found after HPO+Mondo load");
        assertTrue(pizzaCache.contains("class", PIZZA_NS + "Margherita"),
            "REGRESSION: Margherita class must STILL be found after HPO+Mondo load");
        assertTrue(pizzaCache.contains("object_property", PIZZA_NS + "hasTopping"),
            "REGRESSION: hasTopping must STILL be found after HPO+Mondo load");

        // Step 5: Pizza cache size must not have shrunk due to other ontologies.
        long pizzaSizeAfter = pizzaCache.estimatedSize();
        assertTrue(pizzaSizeAfter >= pizzaSizeBefore,
            "Pizza cache size must not shrink due to HPO/Mondo load. before="
                + pizzaSizeBefore + ", after=" + pizzaSizeAfter);

        // Step 6: Verify the same entity IRI is correctly scoped per ontology.
        // HPO entity should NOT be found in pizza cache (different ontology).
        assertFalse(pizzaCache.contains("class", "http://purl.obolibrary.org/obo/HP_0001250"),
            "HPO entity must NOT be found in pizza cache (per-ontology isolation)");
        assertFalse(hpoCache.contains("class", PIZZA_NS + "Pizza"),
            "Pizza entity must NOT be found in HPO cache (per-ontology isolation)");
    }

    @Test
    @DisplayName("Per-ontology maxSize scales with classCount independently")
    void maxSizeScalesPerOntology() throws Exception {
        Path pizzaFile = Paths.get(PIZZA_PATH);
        assumeTrue(Files.exists(pizzaFile),
            "Skipping: pizza ontology fixture not found at " + PIZZA_PATH);

        OWLOntology pizzaOnt = loadOntology(pizzaFile);
        int pizzaClassCount = pizzaOnt.getClassesInSignature().size();

        // Build a synthetic ontology with many classes (>1000) to verify
        // maxSize scales to max(1000, classCount * 2).
        OWLOntology bigOnt = buildBigOntology(1500);

        EntitySignatureCacheManager manager = new EntitySignatureCacheManager();
        EntitySignatureCache pizzaCache = manager.getOrCreate(new OntologyId("pizza"), pizzaOnt);
        EntitySignatureCache bigCache = manager.getOrCreate(new OntologyId("big"), bigOnt);

        // Pizza: maxSize = max(1000, ~115*2) = 1000 (floor)
        // Big:   maxSize = max(1000, 1500*2) = 3000
        pizzaCache.cleanUp();
        bigCache.cleanUp();

        long pizzaSize = pizzaCache.estimatedSize();
        long bigSize = bigCache.estimatedSize();

        assertTrue(pizzaSize > 0 && pizzaSize <= Math.max(1000, pizzaClassCount * 2),
            "Pizza cache size " + pizzaSize + " must be <= max(1000, " + pizzaClassCount + "*2)");
        assertTrue(bigSize > 1000,
            "Big ontology cache (" + bigSize + " entries) must hold > 1000 entries "
                + "(maxSize = max(1000, 1500*2) = 3000)");
    }

    // ── Helpers ──

    private OWLOntology loadOntology(Path path) throws Exception {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        try (InputStream is = Files.newInputStream(path)) {
            return mgr.loadOntologyFromOntologyDocument(is);
        }
    }

    private OWLOntology buildSyntheticOboOntology(String ontIri, String... classIris) throws Exception {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.IRI iri = org.semanticweb.owlapi.model.IRI.create(ontIri);
        OWLOntology ont = mgr.createOntology(iri);
        org.semanticweb.owlapi.model.OWLDataFactory df = mgr.getOWLDataFactory();
        for (String classIri : classIris) {
            org.semanticweb.owlapi.model.OWLClass cls = df.getOWLClass(
                org.semanticweb.owlapi.model.IRI.create(classIri));
            ont.add(df.getOWLDeclarationAxiom(cls));
        }
        return ont;
    }

    private OWLOntology buildBigOntology(int classCount) throws Exception {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLOntology ont = mgr.createOntology(
            org.semanticweb.owlapi.model.IRI.create("http://example.org/big.owl"));
        org.semanticweb.owlapi.model.OWLDataFactory df = mgr.getOWLDataFactory();
        for (int i = 0; i < classCount; i++) {
            org.semanticweb.owlapi.model.OWLClass cls = df.getOWLClass(
                org.semanticweb.owlapi.model.IRI.create("http://example.org/big#C" + i));
            ont.add(df.getOWLDeclarationAxiom(cls));
        }
        return ont;
    }
}
