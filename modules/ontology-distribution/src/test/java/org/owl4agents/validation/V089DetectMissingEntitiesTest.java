package org.owl4agents.validation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.model.MissingEntityResult;
import org.owl4agents.owlapi.EntitySignatureCacheManager;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.owlapi.OntologyImporter;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.ReasonerServiceImpl;
import org.owl4agents.storage.CatalogStore;
import org.owl4agents.storage.HomeDirectoryResolver;
import org.owl4agents.storage.WorkspaceInitializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * v0.9.0 Section 8.3 / task 8.3: Reserved predicate skipping integration test
 * for the v089-entity-declaration-fix change.
 *
 * <p>Verifies that {@link EvidenceGroundingService#detectMissingEntities(Claim)}
 * does NOT report a reserved structural predicate (e.g. {@code "subClassOf"})
 * or an OWL builtin IRI predicate (e.g.
 * {@code "http://www.w3.org/2002/07/owl#subClassOf"}) as a missing, matched,
 * ambiguous, or out-of-scope entity. This is the D3 fix: reserved predicates
 * are claim structural fields, not ontology entities, and SHALL NOT be searched.
 *
 * <p>Also verifies that the subject and object entities (Margherita, NamedPizza)
 * ARE found as matched, since both are declared classes in the pizza ontology.
 *
 * <p>Tagged {@code "integration"} so it only runs via
 * {@code .\gradlew.bat integrationTest}.
 */
@Tag("integration")
@DisabledIfSystemProperty(named = "skip.integration.test", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("v0.9.0 §8.3: detectMissingEntities skips reserved predicates (D3)")
class V089DetectMissingEntitiesTest {

    private static final String PIZZA_ONTOLOGY_ID = "pizza";
    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";

    private Path tempHome;
    private HomeDirectoryResolver homeResolver;
    private CatalogStore catalogStore;
    private OntologyImporter importer;
    private ReasonerServiceImpl reasonerService;
    private ClaimVerificationService verificationService;
    private EvidenceGroundingService evidenceGroundingService;
    private boolean pizzaAvailable;

    @BeforeAll
    void setup() throws Exception {
        tempHome = Files.createTempDirectory("v089-detect-missing-test");
        homeResolver = new HomeDirectoryResolver(tempHome);
        catalogStore = new CatalogStore(homeResolver);
        importer = new OntologyImporter(homeResolver, catalogStore);
        System.setProperty("OWL4AGENTS_HOME", tempHome.toString());

        WorkspaceInitializer initializer = new WorkspaceInitializer(homeResolver);
        ServiceResult<Void> initResult = initializer.initializeIdempotent(WorkspaceId.DEFAULT);
        assertTrue(initResult.isSuccess(),
            "Workspace initialization must succeed: "
                + (initResult instanceof ServiceResult.Error<?> e
                    ? e.error().code() + " " + e.error().message()
                    : "unknown"));

        String basePath = homeResolver.resolveHomeDirectory().resolve("workspaces").toString();
        OntologyCache ontologyCache = new OntologyCache(basePath, "default");
        EntitySignatureCacheManager escManager = new EntitySignatureCacheManager();
        ontologyCache.addReloadListener(escManager);
        reasonerService = new ReasonerServiceImpl(catalogStore, basePath, "default", ontologyCache, escManager);

        ConsistencyAnalysisService consistencyService =
            new ConsistencyAnalysisService(reasonerService.getLifecycleManager(), basePath, ontologyCache, escManager);
        SemanticDeepeningService deepeningService = new SemanticDeepeningService(basePath, ontologyCache);
        verificationService = new ClaimVerificationService(
            reasonerService, consistencyService, deepeningService, catalogStore, WorkspaceId.DEFAULT);
        evidenceGroundingService = new EvidenceGroundingService(reasonerService, consistencyService);

        Path pizzaFixture = findPizzaFixture();
        pizzaAvailable = pizzaFixture != null;
        if (pizzaAvailable) {
            importFixture(PIZZA_ONTOLOGY_ID, pizzaFixture);
        }
    }

    @AfterAll
    void cleanup() throws Exception {
        if (reasonerService != null && pizzaAvailable) {
            reasonerService.shutdown(new OntologyId(PIZZA_ONTOLOGY_ID));
        }
        if (tempHome == null) return;
        try {
            Files.walk(tempHome)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (Exception ignored) { }
                });
        } catch (Exception ignored) {
            // Best-effort cleanup.
        }
    }

    @Test
    @DisplayName("Reserved keyword 'subClassOf' predicate is NOT searched as a missing entity")
    void reservedPredicateSubClassOfIsNotSearched() {
        assumeTrue(pizzaAvailable,
            "Skipping: Pizza ontology fixture not found");

        Claim claim = new Claim(
            "test-dm-001",
            ClaimType.SUBCLASS,
            PIZZA_ONTOLOGY_ID,
            new ClaimEntity("class", PIZZA_NS + "Margherita"),
            "subClassOf",
            new ClaimEntity("class", PIZZA_NS + "NamedPizza"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

        ServiceResult<MissingEntityResult> result = evidenceGroundingService.detectMissingEntities(claim);
        assertTrue(result.isSuccess(),
            "detectMissingEntities must succeed: "
                + (result instanceof ServiceResult.Error<?> e
                    ? e.error().code() + " " + e.error().message()
                    : "unknown"));

        MissingEntityResult data =
            ((ServiceResult.Success<MissingEntityResult>) result).data();

        // "subClassOf" is a reserved structural keyword (D3) and must NOT appear
        // in any classification list — it is not an ontology entity.
        assertFalse(data.missing().stream().anyMatch(m -> "subClassOf".equals(m.searchTerm())),
            "'subClassOf' must NOT be reported as missing");
        assertFalse(data.matched().stream().anyMatch(m -> "subClassOf".equals(m.searchTerm())),
            "'subClassOf' must NOT be reported as matched");
        assertFalse(data.ambiguous().stream().anyMatch(m -> "subClassOf".equals(m.searchTerm())),
            "'subClassOf' must NOT be reported as ambiguous");
        assertFalse(data.outOfScope().stream().anyMatch(m -> "subClassOf".equals(m.searchTerm())),
            "'subClassOf' must NOT be reported as out_of_scope");

        // Subject and object entities ARE found (both are declared pizza classes).
        assertTrue(data.matched().stream().anyMatch(m -> (PIZZA_NS + "Margherita").equals(m.searchTerm())),
            "Margherita must be reported as matched");
        assertTrue(data.matched().stream().anyMatch(m -> (PIZZA_NS + "NamedPizza").equals(m.searchTerm())),
            "NamedPizza must be reported as matched");
    }

    @Test
    @DisplayName("OWL builtin IRI predicate (owl#subClassOf) is NOT searched as a missing entity")
    void owlBuiltinPredicateIriIsNotSearched() {
        assumeTrue(pizzaAvailable,
            "Skipping: Pizza ontology fixture not found");

        String owlPredicate = "http://www.w3.org/2002/07/owl#subClassOf";
        Claim claim = new Claim(
            "test-dm-002",
            ClaimType.SUBCLASS,
            PIZZA_ONTOLOGY_ID,
            new ClaimEntity("class", PIZZA_NS + "Margherita"),
            owlPredicate,
            new ClaimEntity("class", PIZZA_NS + "NamedPizza"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

        ServiceResult<MissingEntityResult> result = evidenceGroundingService.detectMissingEntities(claim);
        assertTrue(result.isSuccess(),
            "detectMissingEntities must succeed: "
                + (result instanceof ServiceResult.Error<?> e
                    ? e.error().code() + " " + e.error().message()
                    : "unknown"));

        MissingEntityResult data =
            ((ServiceResult.Success<MissingEntityResult>) result).data();

        // The OWL builtin IRI must NOT appear in any classification list.
        assertFalse(data.missing().stream().anyMatch(m -> owlPredicate.equals(m.searchTerm())),
            "OWL builtin predicate IRI must NOT be reported as missing");
        assertFalse(data.matched().stream().anyMatch(m -> owlPredicate.equals(m.searchTerm())),
            "OWL builtin predicate IRI must NOT be reported as matched");
        assertFalse(data.ambiguous().stream().anyMatch(m -> owlPredicate.equals(m.searchTerm())),
            "OWL builtin predicate IRI must NOT be reported as ambiguous");
        assertFalse(data.outOfScope().stream().anyMatch(m -> owlPredicate.equals(m.searchTerm())),
            "OWL builtin predicate IRI must NOT be reported as out_of_scope");
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private Path findPizzaFixture() {
        Path projectRoot = Path.of("").toAbsolutePath();
        Path[] candidates = {
            projectRoot.resolve("test/corpus/smoke/pizza.owl"),
            projectRoot.resolve("test/owl_files/pizza.owl"),
            projectRoot.resolve("test/corpus/pizza.owl"),
            projectRoot.resolve("test/corpus/pizza/pizza.owl"),
            projectRoot.resolve("../test/corpus/smoke/pizza.owl"),
            projectRoot.resolve("../test/owl_files/pizza.owl"),
            Path.of("D:\\owl4agents\\data\\workspaces\\default\\ontologies\\pizza\\canonical\\ontology.owl")
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) return candidate;
        }
        return null;
    }

    private void importFixture(String ontologyId, Path fixturePath) {
        var existing = catalogStore.findEntry(
            WorkspaceId.DEFAULT, new OntologyId(ontologyId));
        if (existing.isSuccess()) return;

        ServiceResult<Void> result = importer.importOntology(
            new OntologyId(ontologyId), fixturePath, WorkspaceId.DEFAULT);
        assertTrue(result.isSuccess(),
            "Import of " + ontologyId + " must succeed: "
                + (result instanceof ServiceResult.Error<?> e
                    ? e.error().code() + " " + e.error().message()
                    : "unknown"));
    }
}
