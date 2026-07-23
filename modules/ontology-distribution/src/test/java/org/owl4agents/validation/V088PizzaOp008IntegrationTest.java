package org.owl4agents.validation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.model.ClaimVerificationResult;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * v0.8.8 Section 8.4 / task 8.4: Integration test for the pizza-op-008 fix (D4).
 *
 * <p>Verifies that the pizza-op-008 claim — an {@code object_property_assertion}
 * claim with {@code predicate=subPropertyOf} (isIngredientOf subPropertyOf
 * isBaseOf) — does NOT return {@code CLAIM_CONSISTENCY_CHECK_FAILED} error.
 *
 * <p>Per design D4, the root cause of the v0.8.7 error was investigated and
 * fixed. The satisfiability check (D2) is NOT applied to
 * {@code object_property_assertion} claims because OWL object properties do
 * not have a satisfiability concept — the verdict is determined by the Stage 4
 * exact consistency check alone.
 *
 * <p><b>Fixture</b>: The Pizza ontology at {@code test/corpus/smoke/pizza.owl}.
 * Uses {@link Assumptions#assumeTrue(boolean)} to skip if the fixture is
 * missing.
 *
 * <p>Tagged {@code "integration"} so it only runs via
 * {@code .\gradlew.bat integrationTest}.
 *
 * <p>Acceptance contract: {@code test/contracts/v088-acceptance/pizza-op-008.json}
 */
@Tag("integration")
@DisabledIfSystemProperty(named = "skip.integration.test", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("v0.8.8 §8.4: pizza-op-008 — object_property_assertion does not return CLAIM_CONSISTENCY_CHECK_FAILED")
class V088PizzaOp008IntegrationTest {

    private static final String PIZZA_ONTOLOGY_ID = "pizza";
    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";

    private Path tempHome;
    private HomeDirectoryResolver homeResolver;
    private CatalogStore catalogStore;
    private OntologyImporter importer;
    private ReasonerServiceImpl reasonerService;
    private ClaimVerificationService verificationService;
    private String corpusFixturesPath;

    @BeforeAll
    void setup() throws Exception {
        tempHome = Files.createTempDirectory("v088-pizza-op-008-test");
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

        corpusFixturesPath = System.getProperty("corpus.fixtures", "../test/corpus");

        Path pizzaFixture = findPizzaFixture();
        assumeTrue(pizzaFixture != null,
            "Skipping pizza-op-008 test: Pizza ontology fixture not found");

        importFixture(PIZZA_ONTOLOGY_ID, pizzaFixture);
    }

    @AfterAll
    void cleanup() throws Exception {
        if (reasonerService != null) {
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
    @DisplayName("pizza-op-008: object_property_assertion (subPropertyOf) does not return CLAIM_CONSISTENCY_CHECK_FAILED")
    void pizzaOp008DoesNotReturnConsistencyCheckFailed() {
        Claim claim = new Claim(
            "pizza-op-008-c1",
            ClaimType.OBJECT_PROPERTY_ASSERTION,
            PIZZA_ONTOLOGY_ID,
            new ClaimEntity("object_property", PIZZA_NS + "isIngredientOf"),
            "subPropertyOf",
            new ClaimEntity("object_property", PIZZA_NS + "isBaseOf"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

        ServiceResult<ClaimVerificationResult> result = verificationService.verify(claim);
        assertTrue(result.isSuccess(),
            "pizza-op-008 verification must succeed: "
                + (result instanceof ServiceResult.Error<?> e
                    ? e.error().code() + " " + e.error().message()
                    : "unknown"));

        ClaimVerificationResult data =
            ((ServiceResult.Success<ClaimVerificationResult>) result).data();
        assertNotNull(data, "pizza-op-008 result must not be null");

        // Primary assertion: must NOT return CLAIM_CONSISTENCY_CHECK_FAILED.
        assertFalse(
            data.errorCode().isPresent()
                && data.errorCode().get() == ErrorCode.CLAIM_CONSISTENCY_CHECK_FAILED,
            "pizza-op-008 must NOT return CLAIM_CONSISTENCY_CHECK_FAILED (got: "
                + data.errorCode() + ")");

        // The verdict must be non-null (COMPLETED, not ERROR/TIMEOUT).
        assertNotNull(data.verdict(),
            "pizza-op-008 must have a non-null verdict (executionStatus="
                + data.executionStatus() + ", errorCode=" + data.errorCode() + ")");
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private Path findPizzaFixture() {
        Path projectRoot = Path.of("").toAbsolutePath();
        Path[] candidates = {
            projectRoot.resolve("test/corpus/smoke/pizza.owl"),
            projectRoot.resolve("test/owl_files/pizza.owl"),
            projectRoot.resolve("test/corpus/pizza.owl"),
            projectRoot.resolve("test/corpus/pizza/pizza.owl"),
            Path.of(corpusFixturesPath).resolve("smoke/pizza.owl"),
            Path.of(corpusFixturesPath).resolve("pizza.owl")
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
