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
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.ReasonerServiceImpl;
import org.owl4agents.reasoner.ReasonerLifecycleManager;

/**
 * v0.8.4 Section 6 tests: verify AssertedAxiomIndex (EntitySignatureCache)
 * integration into checkAxiomEntailment and checkClassCompatibility.
 *
 * <p>Task 6.6: SubClassOf index hit and miss scenarios.
 * <p>Task 6.7: DisjointClasses index hit and miss scenarios
 * (in both checkAxiomEntailment and checkClassCompatibility paths).
 */
class AssertedAxiomIndexTest {

    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";
    private static final String PIZZA = PIZZA_NS + "Pizza";
    private static final String CHEESEY_PIZZA = PIZZA_NS + "CheeseyPizza";
    private static final String MARGHERITA = PIZZA_NS + "Margherita";
    private static final String PIZZA_TOPPING = PIZZA_NS + "PizzaTopping";
    private static final String FOOD = PIZZA_NS + "Food";

    private static final String WORKSPACE = System.getProperty("user.dir").contains("D:\\owl4agents")
        ? "D:\\owl4agents\\data\\workspaces" : "data/workspaces";

    private static final String ONTOLOGY_ID = "pizza";

    /**
     * Build a ClaimVerificationService with a real ReasonerServiceImpl and
     * EntitySignatureCacheManager enabled. This exercises the index lookup
     * path in both checkAxiomEntailment and checkClassCompatibility.
     */
    private ClaimVerificationService createServiceWithIndex() {
        OntologyCache cache = new OntologyCache(WORKSPACE, "default");
        StubCatalogStore catalog = new StubCatalogStore();
        EntitySignatureCacheManager escManager = new EntitySignatureCacheManager();
        cache.addReloadListener(escManager);
        ReasonerServiceImpl reasonerService = new ReasonerServiceImpl(catalog, WORKSPACE, "default", cache, escManager);
        ConsistencyAnalysisService consistencyService = new ConsistencyAnalysisService(
            reasonerService.getLifecycleManager(), WORKSPACE, cache, escManager);
        SemanticDeepeningService deepeningService = new SemanticDeepeningService(WORKSPACE, cache);
        return new ClaimVerificationService(
            reasonerService, consistencyService, deepeningService, catalog, new WorkspaceId("default"));
    }

    // ── Task 6.6: SubClassOf index hit and miss ──

    @Test
    @DisplayName("6.6 HIT: asserted SubClassOf (CheeseyPizza → Pizza) returns SUPPORTED via index")
    void subClassOfIndexHitReturnsSupported() {
        ClaimVerificationService svc = createServiceWithIndex();

        Claim claim = new Claim("idx-01", ClaimType.SUBCLASS, ONTOLOGY_ID,
            new ClaimEntity("class", CHEESEY_PIZZA), null,
            new ClaimEntity("class", PIZZA),
            Optional.empty(), Optional.empty(), Optional.empty());

        ServiceResult<ClaimVerificationResult> result = svc.verify(claim);
        assertTrue(result.isSuccess(), "SubClassOf with asserted relationship should succeed");

        ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
        assertEquals(Verdict.SUPPORTED, data.verdict(),
            "Asserted SubClassOf (CheeseyPizza → Pizza) should be SUPPORTED via index");
    }

    @Test
    @DisplayName("6.6 MISS: inferred-only SubClassOf (Margherita → Pizza) falls through to reasoner")
    void subClassOfIndexMissFallsThroughToReasoner() {
        ClaimVerificationService svc = createServiceWithIndex();

        // Margherita → CheeseyPizza → Pizza. The asserted index has
        // Margherita → {CheeseyPizza} but NOT Margherita → {Pizza}.
        // The index lookup misses, and the reasoner must handle it.
        Claim claim = new Claim("idx-02", ClaimType.SUBCLASS, ONTOLOGY_ID,
            new ClaimEntity("class", MARGHERITA), null,
            new ClaimEntity("class", PIZZA),
            Optional.empty(), Optional.empty(), Optional.empty());

        ServiceResult<ClaimVerificationResult> result = svc.verify(claim);
        assertTrue(result.isSuccess(), "SubClassOf with inferred relationship should succeed");

        ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
        assertEquals(Verdict.SUPPORTED, data.verdict(),
            "Inferred SubClassOf (Margherita → Pizza) should be SUPPORTED via reasoner fallback");
    }

    // ── Task 6.7: DisjointClasses index hit and miss in checkClassCompatibility ──

    @Test
    @DisplayName("6.7 HIT (checkClassCompatibility): asserted DisjointClasses (Pizza vs PizzaTopping) returns SUPPORTED")
    void disjointClassesIndexHitInCheckClassCompatibility() {
        ClaimVerificationService svc = createServiceWithIndex();

        Claim claim = new Claim("idx-03", ClaimType.DISJOINT_CLASSES, ONTOLOGY_ID,
            new ClaimEntity("class", PIZZA), null,
            new ClaimEntity("class", PIZZA_TOPPING),
            Optional.empty(), Optional.empty(), Optional.empty());

        ServiceResult<ClaimVerificationResult> result = svc.verify(claim);
        assertTrue(result.isSuccess(), "DisjointClasses claim should succeed");

        ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
        assertEquals(Verdict.SUPPORTED, data.verdict(),
            "Asserted DisjointClasses (Pizza vs PizzaTopping) should be SUPPORTED via index");
    }

    @Test
    @DisplayName("6.7 MISS (checkClassCompatibility): non-disjoint pair (Pizza vs Food) returns CONTRADICTED or UNKNOWN")
    void disjointClassesIndexMissInCheckClassCompatibility() {
        ClaimVerificationService svc = createServiceWithIndex();

        // Pizza and Food are NOT disjoint (Food is a superclass of Pizza).
        // The index lookup misses, and the compatibility check falls through
        // to the reasoner which should find them compatible (not disjoint).
        Claim claim = new Claim("idx-04", ClaimType.DISJOINT_CLASSES, ONTOLOGY_ID,
            new ClaimEntity("class", PIZZA), null,
            new ClaimEntity("class", FOOD),
            Optional.empty(), Optional.empty(), Optional.empty());

        ServiceResult<ClaimVerificationResult> result = svc.verify(claim);
        assertTrue(result.isSuccess(), "Non-disjoint claim should still produce a result");

        ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
        assertNotEquals(Verdict.SUPPORTED, data.verdict(),
            "Non-disjoint pair (Pizza vs Food) should NOT be SUPPORTED");
    }

    // ── Task 6.7: DisjointClasses index hit and miss in checkAxiomEntailment ──

    @Test
    @DisplayName("6.7 HIT (checkAxiomEntailment): asserted DisjointClasses returns ENTAILED via index")
    void disjointClassesIndexHitInCheckAxiomEntailment() {
        OntologyCache cache = new OntologyCache(WORKSPACE, "default");
        StubCatalogStore catalog = new StubCatalogStore();
        EntitySignatureCacheManager escManager = new EntitySignatureCacheManager();
        cache.addReloadListener(escManager);
        ReasonerServiceImpl reasonerService = new ReasonerServiceImpl(catalog, WORKSPACE, "default", cache, escManager);

        ServiceResult<EntailmentResult> result = reasonerService.checkEntailment(
            new OntologyId(ONTOLOGY_ID), "DisjointClasses",
            Map.of("class1", PIZZA, "class2", PIZZA_TOPPING),
            Optional.empty());

        assertTrue(result.isSuccess(), "checkEntailment for DisjointClasses should succeed");
        EntailmentResult data = ((ServiceResult.Success<EntailmentResult>) result).data();
        assertEquals(EntailmentResult.ENTAILED, data.result(),
            "Asserted DisjointClasses (Pizza vs PizzaTopping) should be ENTAILED");
    }

    @Test
    @DisplayName("6.7 MISS (checkAxiomEntailment): non-disjoint pair returns NOT_ENTAILED")
    void disjointClassesIndexMissInCheckAxiomEntailment() {
        OntologyCache cache = new OntologyCache(WORKSPACE, "default");
        StubCatalogStore catalog = new StubCatalogStore();
        EntitySignatureCacheManager escManager = new EntitySignatureCacheManager();
        cache.addReloadListener(escManager);
        ReasonerServiceImpl reasonerService = new ReasonerServiceImpl(catalog, WORKSPACE, "default", cache, escManager);

        ServiceResult<EntailmentResult> result = reasonerService.checkEntailment(
            new OntologyId(ONTOLOGY_ID), "DisjointClasses",
            Map.of("class1", PIZZA, "class2", FOOD),
            Optional.empty());

        assertTrue(result.isSuccess(), "checkEntailment for non-disjoint should succeed");
        EntailmentResult data = ((ServiceResult.Success<EntailmentResult>) result).data();
        assertEquals(EntailmentResult.NOT_ENTAILED, data.result(),
            "Non-disjoint pair (Pizza vs Food) should be NOT_ENTAILED");
    }
}
