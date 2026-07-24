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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * v0.9.0 Section 8.2 / task 8.2: Multi-ontology load integration test for the
 * v089-entity-declaration-fix change.
 *
 * <p>Verifies that pizza entities pass {@code isEntityDeclared} after the hpo
 * and mondo ontologies are also loaded. This is the core D1 fix: per-ontology
 * {@code EntitySignatureCache} prevents global cache eviction from evicting
 * pizza entities when another ontology (hpo/mondo) is loaded.
 *
 * <p><b>Fixture availability</b>: HPO and Mondo ontologies are large OBO
 * ontologies that are typically NOT available in the test environment. The test
 * uses {@link assumeTrue} guards so it skips gracefully (passes as SKIP) when
 * the fixtures are missing. When all three fixtures are present, the test
 * imports hpo and mondo into the temp workspace and asserts pizza entities
 * remain declared.
 *
 * <p>Tagged {@code "integration"} so it only runs via
 * {@code .\gradlew.bat integrationTest}.
 */
@Tag("integration")
@DisabledIfSystemProperty(named = "skip.integration.test", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("v0.9.0 §8.2: Multi-ontology load — pizza entities remain declared after hpo+mondo load")
class V089MultiOntologyLoadTest {

    private static final String PIZZA_ONTOLOGY_ID = "pizza";
    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";
    // Look for HPO/Mondo fixtures in the v089-full-suite corpus first, then
    // fall back to a developer-local canonical workspace path.
    private static final Path[] HPO_FIXTURE_CANDIDATES = {
        Path.of("D:\\owl4agents\\test\\corpus\\v089-full-suite\\ontologies\\hp.owl"),
        Path.of("test/corpus/v089-full-suite/ontologies/hp.owl"),
        Path.of("D:\\owl4agents\\data\\workspaces\\default\\ontologies\\hpo\\canonical\\ontology.owl")
    };
    private static final Path[] MONDO_FIXTURE_CANDIDATES = {
        Path.of("D:\\owl4agents\\test\\corpus\\v089-full-suite\\ontologies\\mondo.owl"),
        Path.of("test/corpus/v089-full-suite/ontologies/mondo.owl"),
        Path.of("D:\\owl4agents\\data\\workspaces\\default\\ontologies\\mondo\\canonical\\ontology.owl")
    };

    private Path tempHome;
    private HomeDirectoryResolver homeResolver;
    private CatalogStore catalogStore;
    private OntologyImporter importer;
    private ReasonerServiceImpl reasonerService;
    private ConsistencyAnalysisService consistencyService;
    private boolean pizzaAvailable;
    private boolean hpoAvailable;
    private boolean mondoAvailable;
    private Path hpoFixture;
    private Path mondoFixture;

    @BeforeAll
    void setup() throws Exception {
        tempHome = Files.createTempDirectory("v089-multi-ont-test");
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

        consistencyService =
            new ConsistencyAnalysisService(reasonerService.getLifecycleManager(), basePath, ontologyCache, escManager);

        Path pizzaFixture = findPizzaFixture();
        pizzaAvailable = pizzaFixture != null;
        if (pizzaAvailable) {
            importFixture(PIZZA_ONTOLOGY_ID, pizzaFixture);
        }

        hpoFixture = resolveFixture(HPO_FIXTURE_CANDIDATES);
        mondoFixture = resolveFixture(MONDO_FIXTURE_CANDIDATES);
        hpoAvailable = pizzaAvailable && hpoFixture != null;
        mondoAvailable = hpoAvailable && mondoFixture != null;
    }

    @AfterAll
    void cleanup() throws Exception {
        if (reasonerService != null && pizzaAvailable) {
            try { reasonerService.shutdown(new OntologyId(PIZZA_ONTOLOGY_ID)); } catch (Exception ignored) { }
        }
        if (hpoAvailable) {
            try { reasonerService.shutdown(new OntologyId("hpo")); } catch (Exception ignored) { }
        }
        if (mondoAvailable) {
            try { reasonerService.shutdown(new OntologyId("mondo")); } catch (Exception ignored) { }
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
    @DisplayName("Pizza entities remain declared after hpo is also loaded (D1 per-ontology cache)")
    void pizzaEntitiesRemainDeclaredAfterHpoLoad() {
        assumeTrue(pizzaAvailable,
            "Skipping: Pizza ontology fixture not found");
        assumeTrue(hpoAvailable,
            "Skipping: HPO ontology fixture not found — test requires pizza + hpo");

        // Import hpo into the temp workspace.
        importFixture("hpo", hpoFixture);

        // After loading hpo, pizza entities must still be declared.
        // This is the core D1 fix: per-ontology EntitySignatureCache prevents
        // global cache eviction from evicting pizza entities when hpo loads.
        assertPizzaEntitiesDeclared("after hpo load");
    }

    @Test
    @DisplayName("Pizza entities remain declared after hpo+mondo are also loaded (D1 per-ontology cache, full)")
    void pizzaEntitiesRemainDeclaredAfterHpoMondoLoad() {
        assumeTrue(pizzaAvailable,
            "Skipping: Pizza ontology fixture not found");
        assumeTrue(mondoAvailable,
            "Skipping: Mondo ontology fixture not found — test requires pizza + hpo + mondo");

        // Import hpo and mondo into the temp workspace.
        importFixture("hpo", hpoFixture);
        importFixture("mondo", mondoFixture);

        // After loading hpo and mondo, pizza entities must still be declared.
        assertPizzaEntitiesDeclared("after hpo+mondo load");
    }

    private void assertPizzaEntitiesDeclared(String context) {
        boolean pizzaDeclared = consistencyService.isEntityDeclared(
            new OntologyId(PIZZA_ONTOLOGY_ID), PIZZA_NS + "Pizza", "class");
        assertTrue(pizzaDeclared,
            "Pizza class must remain declared " + context + " (D1 per-ontology cache)");

        boolean margheritaDeclared = consistencyService.isEntityDeclared(
            new OntologyId(PIZZA_ONTOLOGY_ID), PIZZA_NS + "Margherita", "class");
        assertTrue(margheritaDeclared,
            "Margherita class must remain declared " + context + " (D1 per-ontology cache)");

        boolean namedPizzaDeclared = consistencyService.isEntityDeclared(
            new OntologyId(PIZZA_ONTOLOGY_ID), PIZZA_NS + "NamedPizza", "class");
        assertTrue(namedPizzaDeclared,
            "NamedPizza class must remain declared " + context + " (D1 per-ontology cache)");
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private static Path resolveFixture(Path[] candidates) {
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) return candidate;
        }
        return null;
    }

    private Path findPizzaFixture() {
        Path projectRoot = Path.of("").toAbsolutePath();
        Path[] candidates = {
            projectRoot.resolve("test/corpus/v089-full-suite/ontologies/pizza.owl"),
            projectRoot.resolve("test/corpus/smoke/pizza.owl"),
            projectRoot.resolve("test/owl_files/pizza.owl"),
            projectRoot.resolve("test/corpus/pizza.owl"),
            projectRoot.resolve("test/corpus/pizza/pizza.owl"),
            projectRoot.resolve("../test/corpus/smoke/pizza.owl"),
            projectRoot.resolve("../test/owl_files/pizza.owl"),
            Path.of("D:\\owl4agents\\test\\corpus\\v089-full-suite\\ontologies\\pizza.owl"),
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
