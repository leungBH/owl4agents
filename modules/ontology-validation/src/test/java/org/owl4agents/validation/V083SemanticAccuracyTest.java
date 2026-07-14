package org.owl4agents.validation;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.model.ClaimVerificationResult;
import org.owl4agents.core.model.EntailmentResult;
import org.owl4agents.core.model.Verdict;
import org.owl4agents.owlapi.EntitySignatureCacheManager;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.ReasonerLifecycleManager;
import org.owl4agents.reasoner.ReasonerServiceImpl;

/**
 * v0.8.3 semantic accuracy fixes: R1-R7 integration tests.
 *
 * R1: OOS pre-check uses Imports.EXCLUDED + Declaration axiom dual check
 * R2: Disjoint proxy signature check (defense-in-depth)
 * R4: EquivalentClasses complex expression extraction via getSignature()
 * R5: Individual-level DisjointClasses dispatch to DifferentIndividuals
 * R6: Property hierarchy dispatch to SubObjectPropertyOf
 * R7: ObjectPropertyDomain complex domain extraction + getObjectPropertyDomains()
 */
@DisplayName("v0.8.3 Semantic Accuracy Fixes (R1-R7)")
class V083SemanticAccuracyTest {

    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";
    private static final String PIZZA = PIZZA_NS + "Pizza";
    private static final String CHEESEY_PIZZA = PIZZA_NS + "CheeseyPizza";
    private static final String FRANCE = PIZZA_NS + "France";
    private static final String GERMANY = PIZZA_NS + "Germany";
    private static final String HAS_BASE = PIZZA_NS + "hasBase";
    private static final String HAS_INGREDIENT = PIZZA_NS + "hasIngredient";
    private static final String HAS_TOPPING = PIZZA_NS + "hasTopping";
    private static final String IS_BASE_OF = PIZZA_NS + "isBaseOf";
    private static final String PIZZA_BASE = PIZZA_NS + "PizzaBase";
    private static final String EXTERNAL_IRI = "http://purl.obolibrary.org/obo/UBERON_0000948";

    private static final String WORKSPACE = System.getProperty("user.dir").contains("D:\\owl4agents")
        ? "D:\\owl4agents\\data\\workspaces" : "data/workspaces";

    private static final String ONTOLOGY_ID = "pizza";

    /** Build a service backed by the real ReasonerServiceImpl (exercises R4/R7 code paths). */
    private ClaimVerificationService createServiceWithRealReasoner() {
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

    /** Build a service with a stub reasoner (exercises R5/R6 dispatch logic). */
    private ClaimVerificationService createServiceWithStub(StubReasonerService stub) {
        return new ClaimVerificationService(
            stub,
            new ConsistencyAnalysisService(new ReasonerLifecycleManager(), WORKSPACE),
            new SemanticDeepeningService(WORKSPACE),
            new StubCatalogStore(),
            new WorkspaceId("default"));
    }

    private ClaimVerificationResult verify(ClaimVerificationService svc, Claim claim) {
        ServiceResult<ClaimVerificationResult> result = svc.verify(claim);
        assertTrue(result.isSuccess(), "verify() should succeed for " + claim.claimId());
        return ((ServiceResult.Success<ClaimVerificationResult>) result).data();
    }

    // ── R1: OOS pre-check ──

    @Nested
    @DisplayName("R1: OOS pre-check (Imports.EXCLUDED + Declaration axiom)")
    class R1OosPrecheckTests {

        @Test
        @DisplayName("TC-R1-01: external IRI (UBERON) subject → OUT_OF_SCOPE")
        void externalIriSubjectReturnsOutOfScope() {
            StubReasonerService stub = new StubReasonerService().withRealOntology(WORKSPACE);
            ClaimVerificationService svc = createServiceWithStub(stub);

            Claim claim = new Claim("r1-01", ClaimType.SUBCLASS, ONTOLOGY_ID,
                new ClaimEntity("class", EXTERNAL_IRI),
                "http://www.w3.org/2000/01/rdf-schema#subClassOf",
                new ClaimEntity("class", PIZZA),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = verify(svc, claim);
            assertEquals(Verdict.OUT_OF_SCOPE, data.verdict(),
                "External IRI must be out_of_scope");
        }

        @Test
        @DisplayName("TC-R1-02: pizza entity subject → NOT OUT_OF_SCOPE")
        void pizzaEntitySubjectNotOutOfScope() {
            StubReasonerService stub = new StubReasonerService().withRealOntology(WORKSPACE);
            stub.withEntailmentResult(EntailmentResult.ENTAILED);
            ClaimVerificationService svc = createServiceWithStub(stub);

            Claim claim = new Claim("r1-02", ClaimType.SUBCLASS, ONTOLOGY_ID,
                new ClaimEntity("class", CHEESEY_PIZZA),
                "http://www.w3.org/2000/01/rdf-schema#subClassOf",
                new ClaimEntity("class", PIZZA),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = verify(svc, claim);
            assertNotEquals(Verdict.OUT_OF_SCOPE, data.verdict(),
                "Pizza entity must not be out_of_scope");
        }

        @Test
        @DisplayName("TC-R1-03: external IRI object → OUT_OF_SCOPE")
        void externalIriObjectReturnsOutOfScope() {
            StubReasonerService stub = new StubReasonerService().withRealOntology(WORKSPACE);
            ClaimVerificationService svc = createServiceWithStub(stub);

            Claim claim = new Claim("r1-03", ClaimType.SUBCLASS, ONTOLOGY_ID,
                new ClaimEntity("class", PIZZA),
                "http://www.w3.org/2000/01/rdf-schema#subClassOf",
                new ClaimEntity("class", EXTERNAL_IRI),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = verify(svc, claim);
            assertEquals(Verdict.OUT_OF_SCOPE, data.verdict());
        }

        @Test
        @DisplayName("TC-R1-06: pizza individual (America) → NOT OUT_OF_SCOPE (stream scan, v0.8.5 P0 fix)")
        void pizzaIndividualNotOutOfScopeStreamScan() {
            StubReasonerService stub = new StubReasonerService().withRealOntology(WORKSPACE);
            ClaimVerificationService svc = createServiceWithStub(stub);

            Claim claim = new Claim("r1-06", ClaimType.DISJOINT_CLASSES, ONTOLOGY_ID,
                new ClaimEntity("individual", PIZZA_NS + "America"),
                "differentFrom",
                new ClaimEntity("individual", PIZZA_NS + "England"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = verify(svc, claim);
            assertNotEquals(Verdict.OUT_OF_SCOPE, data.verdict(),
                "Pizza individual (America) must not be out_of_scope on stream scan path "
                + "(signature-based check, no Declaration axiom required)");
        }
    }

    // ── R1: OOS pre-check (production path via EntitySignatureCache) ──

    @Nested
    @DisplayName("R1: OOS pre-check (production path — EntitySignatureCache)")
    class R1OosPrecheckProductionTests {

        @Test
        @DisplayName("TC-R1-PROD-01: external IRI subject → OUT_OF_SCOPE (via EntitySignatureCache)")
        void externalIriSubjectReturnsOutOfScopeProduction() {
            ClaimVerificationService svc = createServiceWithRealReasoner();

            Claim claim = new Claim("r1-prod-01", ClaimType.SUBCLASS, ONTOLOGY_ID,
                new ClaimEntity("class", EXTERNAL_IRI),
                "http://www.w3.org/2000/01/rdf-schema#subClassOf",
                new ClaimEntity("class", PIZZA),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = verify(svc, claim);
            assertEquals(Verdict.OUT_OF_SCOPE, data.verdict(),
                "External IRI must be out_of_scope via EntitySignatureCache path");
        }

        @Test
        @DisplayName("TC-R1-PROD-02: pizza class subject → NOT OUT_OF_SCOPE (via EntitySignatureCache)")
        void pizzaClassSubjectNotOutOfScopeProduction() {
            ClaimVerificationService svc = createServiceWithRealReasoner();

            Claim claim = new Claim("r1-prod-02", ClaimType.SUBCLASS, ONTOLOGY_ID,
                new ClaimEntity("class", CHEESEY_PIZZA),
                "http://www.w3.org/2000/01/rdf-schema#subClassOf",
                new ClaimEntity("class", PIZZA),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = verify(svc, claim);
            assertNotEquals(Verdict.OUT_OF_SCOPE, data.verdict(),
                "Pizza class must not be out_of_scope via EntitySignatureCache path");
        }

        @Test
        @DisplayName("TC-R1-PROD-03: external IRI object → OUT_OF_SCOPE (via EntitySignatureCache)")
        void externalIriObjectReturnsOutOfScopeProduction() {
            ClaimVerificationService svc = createServiceWithRealReasoner();

            Claim claim = new Claim("r1-prod-03", ClaimType.SUBCLASS, ONTOLOGY_ID,
                new ClaimEntity("class", PIZZA),
                "http://www.w3.org/2000/01/rdf-schema#subClassOf",
                new ClaimEntity("class", EXTERNAL_IRI),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = verify(svc, claim);
            assertEquals(Verdict.OUT_OF_SCOPE, data.verdict(),
                "External IRI as object must be out_of_scope via EntitySignatureCache path");
        }

        @Test
        @DisplayName("TC-R1-PROD-04: pizza object_property (hasBase) → NOT OUT_OF_SCOPE")
        void pizzaObjectPropertyNotOutOfScopeProduction() {
            ClaimVerificationService svc = createServiceWithRealReasoner();

            Claim claim = new Claim("r1-prod-04", ClaimType.OBJECT_PROPERTY_ASSERTION, ONTOLOGY_ID,
                new ClaimEntity("object_property", HAS_BASE),
                "subPropertyOf",
                new ClaimEntity("object_property", HAS_INGREDIENT),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = verify(svc, claim);
            assertNotEquals(Verdict.OUT_OF_SCOPE, data.verdict(),
                "hasBase must not be out_of_scope via EntitySignatureCache path");
        }

        @Test
        @DisplayName("TC-R1-PROD-05: pizza individual (America) → NOT OUT_OF_SCOPE")
        void pizzaIndividualNotOutOfScopeProduction() {
            ClaimVerificationService svc = createServiceWithRealReasoner();

            Claim claim = new Claim("r1-prod-05", ClaimType.DISJOINT_CLASSES, ONTOLOGY_ID,
                new ClaimEntity("individual", PIZZA_NS + "America"),
                "differentFrom",
                new ClaimEntity("individual", PIZZA_NS + "England"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = verify(svc, claim);
            assertNotEquals(Verdict.OUT_OF_SCOPE, data.verdict(),
                "Pizza individual (America) must not be out_of_scope via EntitySignatureCache path");
        }
    }

    // ── R2: Disjoint proxy signature check ──

    @Nested
    @DisplayName("R2: Disjoint proxy signature check")
    class R2DisjointProxyTests {

        @Test
        @DisplayName("TC-R2-01: disjoint_classes with external entity → OUT_OF_SCOPE (R1 catches before R2)")
        void disjointClassesWithExternalEntityReturnsOutOfScope() {
            StubReasonerService stub = new StubReasonerService().withRealOntology(WORKSPACE);
            ClaimVerificationService svc = createServiceWithStub(stub);

            Claim claim = new Claim("r2-01", ClaimType.DISJOINT_CLASSES, ONTOLOGY_ID,
                new ClaimEntity("class", EXTERNAL_IRI),
                "disjointWith",
                new ClaimEntity("class", PIZZA),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = verify(svc, claim);
            assertEquals(Verdict.OUT_OF_SCOPE, data.verdict(),
                "External entity in disjoint_classes must be out_of_scope, not contradicted");
        }

        @Test
        @DisplayName("TC-R2-02: disjoint_classes with two pizza classes does NOT return false CONTRADICTED")
        void disjointClassesWithPizzaClassesNotFalseContradicted() {
            StubReasonerService stub = new StubReasonerService().withRealOntology(WORKSPACE);
            stub.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            ClaimVerificationService svc = createServiceWithStub(stub);

            Claim claim = new Claim("r2-02", ClaimType.DISJOINT_CLASSES, ONTOLOGY_ID,
                new ClaimEntity("class", CHEESEY_PIZZA),
                "disjointWith",
                new ClaimEntity("class", PIZZA),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = verify(svc, claim);
            assertNotEquals(Verdict.CONTRADICTED, data.verdict(),
                "Non-disjoint pizza classes must not be falsely contradicted");
        }
    }

    // ── R4: EquivalentClasses complex expression ──

    @Nested
    @DisplayName("R4: EquivalentClasses complex expression extraction")
    class R4EquivalentClassesTests {

        @Test
        @DisplayName("TC-R4-01: CheeseyPizza equivalentTo Pizza (not entailed in exact flow) → not SUPPORTED")
        void cheeseyPizzaEquivalentPizzaReturnsNotSupported() {
            // v0.8.5 exact-consistency: EquivalentClasses(CheeseyPizza, Pizza)
            // is NOT entailed — CheeseyPizza is a PROPER SUBCLASS of Pizza
            // (CheeseyPizza ≡ Pizza ∩ ∃hasTopping.CheeseTopping). The old R4
            // structural proxy extracted named classes from complex expressions
            // and returned SUPPORTED; the new exact flow correctly rejects this.
            ClaimVerificationService svc = createServiceWithRealReasoner();

            Claim claim = new Claim("r4-01", ClaimType.EQUIVALENT_CLASSES, ONTOLOGY_ID,
                new ClaimEntity("class", CHEESEY_PIZZA), null,
                new ClaimEntity("class", PIZZA),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = verify(svc, claim);
            assertNotEquals(Verdict.SUPPORTED, data.verdict(),
                "CheeseyPizza is a proper subclass of Pizza, not equivalent — exact consistency check must not return SUPPORTED");
        }

        @Test
        @DisplayName("TC-R4-02: Non-existent class equivalent → UNKNOWN (not SUPPORTED)")
        void nonExistentClassEquivalentReturnsUnknown() {
            ClaimVerificationService svc = createServiceWithRealReasoner();

            Claim claim = new Claim("r4-02", ClaimType.EQUIVALENT_CLASSES, ONTOLOGY_ID,
                new ClaimEntity("class", CHEESEY_PIZZA), null,
                new ClaimEntity("class", PIZZA_NS + "NonExistentClass"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = verify(svc, claim);
            assertNotEquals(Verdict.SUPPORTED, data.verdict(),
                "Non-existent equivalent class must not be supported");
        }
    }

    // ── R5: Individual-level DisjointClasses dispatch ──

    @Nested
    @DisplayName("R5: Individual-level DisjointClasses dispatch")
    class R5IndividualDisjointTests {

        @Test
        @DisplayName("TC-R5-01: France disjoint Germany (kind=individual, ENTAILED) → SUPPORTED")
        void individualDispatchWithEntailedReturnsSupported() {
            StubReasonerService stub = new StubReasonerService().withRealOntology(WORKSPACE);
            stub.withEntailmentResult("DifferentIndividuals", EntailmentResult.ENTAILED);
            ClaimVerificationService svc = createServiceWithStub(stub);

            Claim claim = new Claim("r5-01", ClaimType.DISJOINT_CLASSES, ONTOLOGY_ID,
                new ClaimEntity("individual", FRANCE),
                "differentFrom",
                new ClaimEntity("individual", GERMANY),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = verify(svc, claim);
            assertEquals(Verdict.SUPPORTED, data.verdict(),
                "DifferentIndividuals(France, Germany) ENTAILED → SUPPORTED");
        }

        @Test
        @DisplayName("TC-R5-02: same individual (France disjoint France) → CONTRADICTED")
        void sameIndividualReturnsContradicted() {
            StubReasonerService stub = new StubReasonerService().withRealOntology(WORKSPACE);
            ClaimVerificationService svc = createServiceWithStub(stub);

            Claim claim = new Claim("r5-02", ClaimType.DISJOINT_CLASSES, ONTOLOGY_ID,
                new ClaimEntity("individual", FRANCE),
                "differentFrom",
                new ClaimEntity("individual", FRANCE),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = verify(svc, claim);
            assertEquals(Verdict.CONTRADICTED, data.verdict(),
                "Same individual disjoint claim must be contradicted");
        }

        @Test
        @DisplayName("TC-R5-03: individual dispatch calls checkEntailment with DifferentIndividuals")
        void individualDispatchCallsDifferentIndividuals() {
            StubReasonerService stub = new StubReasonerService().withRealOntology(WORKSPACE);
            stub.withEntailmentResult("DifferentIndividuals", EntailmentResult.ENTAILED);
            ClaimVerificationService svc = createServiceWithStub(stub);

            Claim claim = new Claim("r5-03", ClaimType.DISJOINT_CLASSES, ONTOLOGY_ID,
                new ClaimEntity("individual", FRANCE),
                "differentFrom",
                new ClaimEntity("individual", GERMANY),
                Optional.empty(), Optional.empty(), Optional.empty());

            verify(svc, claim);
            assertTrue(stub.getCallLog().stream().anyMatch(s -> s.contains("DifferentIndividuals")),
                "verify() must dispatch to checkEntailment(\"DifferentIndividuals\") for kind=individual");
        }
    }

    // ── R6: Property hierarchy dispatch ──

    @Nested
    @DisplayName("R6: Property hierarchy dispatch (ObjectPropertyAssertion)")
    class R6PropertyHierarchyTests {

        @Test
        @DisplayName("TC-R6-01: hasBase subPropertyOf hasIngredient (kind=object_property, ENTAILED) → SUPPORTED")
        void propertyHierarchyDispatchWithEntailedReturnsSupported() {
            StubReasonerService stub = new StubReasonerService().withRealOntology(WORKSPACE);
            stub.withEntailmentResult("SubObjectPropertyOf", EntailmentResult.ENTAILED);
            ClaimVerificationService svc = createServiceWithStub(stub);

            Claim claim = new Claim("r6-01", ClaimType.OBJECT_PROPERTY_ASSERTION, ONTOLOGY_ID,
                new ClaimEntity("object_property", HAS_BASE),
                "subPropertyOf",
                new ClaimEntity("object_property", HAS_INGREDIENT),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = verify(svc, claim);
            assertEquals(Verdict.SUPPORTED, data.verdict(),
                "SubObjectPropertyOf(hasBase, hasIngredient) ENTAILED → SUPPORTED");
        }

        @Test
        @DisplayName("TC-R6-02: dispatch calls checkEntailment with SubObjectPropertyOf")
        void propertyHierarchyDispatchCallsSubObjectPropertyOf() {
            StubReasonerService stub = new StubReasonerService().withRealOntology(WORKSPACE);
            stub.withEntailmentResult("SubObjectPropertyOf", EntailmentResult.ENTAILED);
            ClaimVerificationService svc = createServiceWithStub(stub);

            Claim claim = new Claim("r6-02", ClaimType.OBJECT_PROPERTY_ASSERTION, ONTOLOGY_ID,
                new ClaimEntity("object_property", HAS_BASE),
                "subPropertyOf",
                new ClaimEntity("object_property", HAS_INGREDIENT),
                Optional.empty(), Optional.empty(), Optional.empty());

            verify(svc, claim);
            assertTrue(stub.getCallLog().stream().anyMatch(s -> s.contains("SubObjectPropertyOf")),
                "verify() must dispatch to checkEntailment(\"SubObjectPropertyOf\") for kind=object_property + predicate=subPropertyOf");
        }

        @Test
        @DisplayName("TC-R6-03: NOT_ENTAILED without reverse counter → UNKNOWN")
        void propertyHierarchyNotEntailedWithoutCounterReturnsUnknown() {
            // v0.8.5: Use a non-asserted property pair (hasTopping, isBaseOf)
            // so the stub's asserted fast-path does NOT override the configured
            // NOT_ENTAILED. hasBase subPropertyOf hasIngredient IS asserted in
            // pizza.owl, which would cause the fast-path to return ENTAILED.
            StubReasonerService stub = new StubReasonerService().withRealOntology(WORKSPACE);
            stub.withEntailmentResult("SubObjectPropertyOf", EntailmentResult.NOT_ENTAILED);
            ClaimVerificationService svc = createServiceWithStub(stub);

            Claim claim = new Claim("r6-03", ClaimType.OBJECT_PROPERTY_ASSERTION, ONTOLOGY_ID,
                new ClaimEntity("object_property", HAS_TOPPING),
                "subPropertyOf",
                new ClaimEntity("object_property", IS_BASE_OF),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = verify(svc, claim);
            assertNotEquals(Verdict.SUPPORTED, data.verdict(),
                "NOT_ENTAILED must not be SUPPORTED");
        }
    }

    // ── R7: ObjectPropertyDomain complex domain ──

    @Nested
    @DisplayName("R7: ObjectPropertyDomain complex domain extraction")
    class R7ObjectPropertyDomainTests {

        @Test
        @DisplayName("TC-R7-01: isBaseOf domain PizzaBase → SUPPORTED")
        void isBaseOfDomainPizzaBaseReturnsSupported() {
            ClaimVerificationService svc = createServiceWithRealReasoner();

            Claim claim = new Claim("r7-01", ClaimType.OBJECT_PROPERTY_DOMAIN, ONTOLOGY_ID,
                new ClaimEntity("object_property", IS_BASE_OF), null,
                new ClaimEntity("class", PIZZA_BASE),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = verify(svc, claim);
            assertEquals(Verdict.SUPPORTED, data.verdict(),
                "isBaseOf domain PizzaBase must be supported");
        }

        @Test
        @DisplayName("TC-R7-02: non-existent property domain → UNKNOWN (not SUPPORTED)")
        void nonExistentPropertyDomainReturnsUnknown() {
            ClaimVerificationService svc = createServiceWithRealReasoner();

            Claim claim = new Claim("r7-02", ClaimType.OBJECT_PROPERTY_DOMAIN, ONTOLOGY_ID,
                new ClaimEntity("object_property", PIZZA_NS + "nonExistentProperty"), null,
                new ClaimEntity("class", PIZZA_BASE),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = verify(svc, claim);
            assertNotEquals(Verdict.SUPPORTED, data.verdict(),
                "Non-existent property domain must not be supported");
        }
    }
}
