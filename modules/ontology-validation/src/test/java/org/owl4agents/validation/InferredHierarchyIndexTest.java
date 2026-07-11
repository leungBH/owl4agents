package org.owl4agents.validation;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;
import org.owl4agents.owlapi.EntitySignatureCacheManager;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.reasoner.ReasonerServiceImpl;

/**
 * v0.8.4 Section 7 tests: verify InferredHierarchyIndex in-memory cache
 * and OntologyReloadListener integration.
 *
 * <p>Task 7.6: first load vs subsequent in-memory query.
 */
class InferredHierarchyIndexTest {

    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";
    private static final String PIZZA = PIZZA_NS + "Pizza";
    private static final String CHEESEY_PIZZA = PIZZA_NS + "CheeseyPizza";

    private static final String WORKSPACE = System.getProperty("user.dir").contains("D:\\owl4agents")
        ? "D:\\owl4agents\\data\\workspaces" : "data/workspaces";

    private static final String ONTOLOGY_ID = "pizza";

    /**
     * Test that ReasonerServiceImpl correctly implements OntologyReloadListener.
     * Calling onOntologyReloaded and onAllOntologiesReloaded should not throw
     * and should clear the inferred hierarchy cache.
     */
    @Test
    @DisplayName("7.6: OntologyReloadListener clears inferred hierarchy cache without error")
    void reloadListenerClearsCacheWithoutError() {
        OntologyCache cache = new OntologyCache(WORKSPACE, "default");
        StubCatalogStore catalog = new StubCatalogStore();
        EntitySignatureCacheManager escManager = new EntitySignatureCacheManager();
        cache.addReloadListener(escManager);
        ReasonerServiceImpl reasonerService = new ReasonerServiceImpl(catalog, WORKSPACE, "default", cache, escManager);

        // These should not throw
        assertDoesNotThrow(() -> reasonerService.onOntologyReloaded(new OntologyId(ONTOLOGY_ID)));
        assertDoesNotThrow(() -> reasonerService.onAllOntologiesReloaded());
    }

    /**
     * Test that a SubClassOf entailment check works correctly (first load).
     * The checkEntailment call should succeed regardless of whether the
     * inferred hierarchy file exists.
     */
    @Test
    @DisplayName("7.6: checkEntailment works on first call (loads inferred hierarchy if file exists)")
    void checkEntailmentFirstCallSucceeds() {
        OntologyCache cache = new OntologyCache(WORKSPACE, "default");
        StubCatalogStore catalog = new StubCatalogStore();
        EntitySignatureCacheManager escManager = new EntitySignatureCacheManager();
        cache.addReloadListener(escManager);
        ReasonerServiceImpl reasonerService = new ReasonerServiceImpl(catalog, WORKSPACE, "default", cache, escManager);

        ServiceResult<EntailmentResult> result = reasonerService.checkEntailment(
            new OntologyId(ONTOLOGY_ID), "SubClassOf",
            Map.of("subclass", CHEESEY_PIZZA, "superclass", PIZZA),
            Optional.empty());

        assertTrue(result.isSuccess(), "checkEntailment should succeed on first call");
        EntailmentResult data = ((ServiceResult.Success<EntailmentResult>) result).data();
        assertEquals(EntailmentResult.ENTAILED, data.result(),
            "CheeseyPizza subClassOf Pizza should be ENTAILED");
    }

    /**
     * Test that a second checkEntailment call returns the same result
     * (uses in-memory cache for stored entailment lookups).
     */
    @Test
    @DisplayName("7.6: second checkEntailment call returns same result (in-memory cache)")
    void checkEntailmentSecondCallReturnsSameResult() {
        OntologyCache cache = new OntologyCache(WORKSPACE, "default");
        StubCatalogStore catalog = new StubCatalogStore();
        EntitySignatureCacheManager escManager = new EntitySignatureCacheManager();
        cache.addReloadListener(escManager);
        ReasonerServiceImpl reasonerService = new ReasonerServiceImpl(catalog, WORKSPACE, "default", cache, escManager);

        // First call
        ServiceResult<EntailmentResult> result1 = reasonerService.checkEntailment(
            new OntologyId(ONTOLOGY_ID), "SubClassOf",
            Map.of("subclass", CHEESEY_PIZZA, "superclass", PIZZA),
            Optional.empty());

        // Second call (should use cached data where applicable)
        ServiceResult<EntailmentResult> result2 = reasonerService.checkEntailment(
            new OntologyId(ONTOLOGY_ID), "SubClassOf",
            Map.of("subclass", CHEESEY_PIZZA, "superclass", PIZZA),
            Optional.empty());

        assertTrue(result1.isSuccess() && result2.isSuccess(),
            "Both calls should succeed");
        EntailmentResult data1 = ((ServiceResult.Success<EntailmentResult>) result1).data();
        EntailmentResult data2 = ((ServiceResult.Success<EntailmentResult>) result2).data();
        assertEquals(data1.result(), data2.result(),
            "First and second calls should return the same entailment result");
    }

    /**
     * Test that onOntologyReloaded clears the cache and allows re-loading.
     * After clearing, a new checkEntailment call should still work correctly.
     */
    @Test
    @DisplayName("7.6: after onOntologyReloaded, checkEntailment still works (re-load)")
    void checkEntailmentWorksAfterCacheClear() {
        OntologyCache cache = new OntologyCache(WORKSPACE, "default");
        StubCatalogStore catalog = new StubCatalogStore();
        EntitySignatureCacheManager escManager = new EntitySignatureCacheManager();
        cache.addReloadListener(escManager);
        ReasonerServiceImpl reasonerService = new ReasonerServiceImpl(catalog, WORKSPACE, "default", cache, escManager);

        // First call (populates cache)
        reasonerService.checkEntailment(
            new OntologyId(ONTOLOGY_ID), "SubClassOf",
            Map.of("subclass", CHEESEY_PIZZA, "superclass", PIZZA),
            Optional.empty());

        // Clear cache via reload listener
        reasonerService.onOntologyReloaded(new OntologyId(ONTOLOGY_ID));

        // Second call (should re-load if needed and still work)
        ServiceResult<EntailmentResult> result = reasonerService.checkEntailment(
            new OntologyId(ONTOLOGY_ID), "SubClassOf",
            Map.of("subclass", CHEESEY_PIZZA, "superclass", PIZZA),
            Optional.empty());

        assertTrue(result.isSuccess(), "checkEntailment should work after cache clear");
        EntailmentResult data = ((ServiceResult.Success<EntailmentResult>) result).data();
        assertEquals(EntailmentResult.ENTAILED, data.result(),
            "CheeseyPizza subClassOf Pizza should still be ENTAILED after cache clear");
    }
}
