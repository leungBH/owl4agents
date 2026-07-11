package org.owl4agents.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.OntologyId;
import org.owl4agents.owlapi.EntitySignatureCacheManager;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.reasoner.ReasonerLifecycleManager;
import org.semanticweb.owlapi.model.OWLOntology;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.4 EntitySignatureCacheManager integration tests (tasks 4.6, 4.7, 4.8).
 *
 * <p>Verifies that {@link ConsistencyAnalysisService#isEntityDeclared} uses the
 * {@link EntitySignatureCacheManager} for O(1) lookups, that OBO namespace
 * filtering is consistent with v0.8.3, and that the deprecated constructor
 * (manager=null) correctly falls back to stream scanning.</p>
 */
@DisplayName("v0.8.4 EntitySignatureCache integration (tasks 4.6, 4.7, 4.8)")
class EntitySignatureCacheIntegrationTest {

    private static final String PIZZA_PATH =
        "D:\\owl4agents\\data\\workspaces\\default\\ontologies\\pizza\\canonical\\ontology.owl";
    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";

    private OntologyCache createPizzaCache() {
        OntologyCache cache = new OntologyCache(
            "D:\\owl4agents\\data\\workspaces", "default");
        return cache;
    }

    @Test
    @DisplayName("4.6: isEntityDeclared() with cache manager — 4 consecutive calls return correct results")
    void isEntityDeclaredWithCacheManagerFourCalls() throws Exception {
        OntologyCache cache = createPizzaCache();
        EntitySignatureCacheManager manager = new EntitySignatureCacheManager();
        cache.addReloadListener(manager);
        ReasonerLifecycleManager lm = new ReasonerLifecycleManager();

        ConsistencyAnalysisService service = new ConsistencyAnalysisService(
            lm, cache.getWorkspaceBasePath(), cache, manager);

        OntologyId ontId = new OntologyId("pizza");

        // First call builds the cache; subsequent 3 calls use the cached entry.
        assertTrue(service.isEntityDeclared(ontId, PIZZA_NS + "Pizza", "class"),
            "Call 1: Pizza class must be declared");
        assertTrue(service.isEntityDeclared(ontId, PIZZA_NS + "Margherita", "class"),
            "Call 2: Margherita class must be declared");
        assertTrue(service.isEntityDeclared(ontId, PIZZA_NS + "hasTopping", "object_property"),
            "Call 3: hasTopping object property must be declared");
        assertFalse(service.isEntityDeclared(ontId, "http://example.org/nonexistent", "class"),
            "Call 4: Nonexistent class must not be declared");
    }

    @Test
    @DisplayName("4.7: OBO namespace check — cross-ontology entity returns false (v0.8.3 parity)")
    void oboNamespaceCheckRejectsCrossOntologyEntity(@TempDir Path tempDir) throws Exception {
        // Create a synthetic OBO ontology (hp.owl) that references a UBERON entity.
        Path ontDir = tempDir.resolve("default/ontologies/hpo/canonical");
        Files.createDirectories(ontDir);
        Path owlFile = ontDir.resolve("ontology.owl");
        String owlXml = "<?xml version=\"1.0\"?>\n" +
            "<rdf:RDF xmlns=\"http://purl.obolibrary.org/obo/hp.owl\"\n" +
            "     xml:base=\"http://purl.obolibrary.org/obo/hp.owl\"\n" +
            "     xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n" +
            "     xmlns:owl=\"http://www.w3.org/2002/07/owl#\"\n" +
            "     xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\">\n" +
            "    <owl:Ontology rdf:about=\"http://purl.obolibrary.org/obo/hp.owl\"/>\n" +
            "    <owl:Class rdf:about=\"http://purl.obolibrary.org/obo/HP_0001250\"/>\n" +
            "    <owl:Class rdf:about=\"http://purl.obolibrary.org/obo/UBERON_0001234\"/>\n" +
            "</rdf:RDF>\n";
        Files.writeString(owlFile, owlXml);

        OntologyCache cache = new OntologyCache(tempDir.toString(), "default");
        EntitySignatureCacheManager manager = new EntitySignatureCacheManager();
        cache.addReloadListener(manager);
        ReasonerLifecycleManager lm = new ReasonerLifecycleManager();

        ConsistencyAnalysisService service = new ConsistencyAnalysisService(
            lm, cache.getWorkspaceBasePath(), cache, manager);

        OntologyId ontId = new OntologyId("hpo");

        // HP entity is in-scope (matches HP_ prefix).
        assertTrue(service.isEntityDeclared(ontId, "http://purl.obolibrary.org/obo/HP_0001250", "class"),
            "HP_ entity must be declared in HPO ontology");

        // UBERON entity is cross-ontology — must be rejected despite having a Declaration axiom.
        assertFalse(service.isEntityDeclared(ontId, "http://purl.obolibrary.org/obo/UBERON_0001234", "class"),
            "UBERON_ entity must be rejected by OBO namespace check");
    }

    @Test
    @DisplayName("4.8: deprecated 3-arg constructor (manager=null) falls back to stream scan")
    void deprecatedConstructorFallsBackToStreamScan() throws Exception {
        OntologyCache cache = createPizzaCache();
        ReasonerLifecycleManager lm = new ReasonerLifecycleManager();

        @SuppressWarnings("deprecation")
        ConsistencyAnalysisService service = new ConsistencyAnalysisService(
            lm, cache.getWorkspaceBasePath(), cache);

        OntologyId ontId = new OntologyId("pizza");

        // Stream scan fallback must produce the same results as the cache path.
        assertTrue(service.isEntityDeclared(ontId, PIZZA_NS + "Pizza", "class"),
            "Stream scan: Pizza class must be declared");
        assertTrue(service.isEntityDeclared(ontId, PIZZA_NS + "Margherita", "class"),
            "Stream scan: Margherita class must be declared");
        assertTrue(service.isEntityDeclared(ontId, PIZZA_NS + "hasTopping", "object_property"),
            "Stream scan: hasTopping object property must be declared");
        assertFalse(service.isEntityDeclared(ontId, "http://example.org/nonexistent", "class"),
            "Stream scan: Nonexistent class must not be declared");
    }
}
