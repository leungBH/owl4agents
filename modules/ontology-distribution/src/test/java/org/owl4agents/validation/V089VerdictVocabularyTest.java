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
import org.owl4agents.core.model.AggregateAnswerStatus;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.model.ClaimVerificationResult;
import org.owl4agents.core.model.Verdict;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * v0.9.0 Section 8.4 / task 8.4: Verdict vocabulary integration test for the
 * v089-entity-declaration-fix change.
 *
 * <p>Verifies the D4 breaking change: the verdict vocabulary uses
 * {@code "supported"} (not {@code "verified"}). Specifically:
 * <ul>
 *   <li>{@link AggregateAnswerStatus#VERIFIED} jsonName is {@code "supported"}.</li>
 *   <li>{@link Verdict#SUPPORTED} jsonName is {@code "supported"} (not
 *       {@code "verified"}).</li>
 *   <li>A supported claim's verification result verdict jsonName does NOT
 *       contain {@code "verified"}.</li>
 * </ul>
 *
 * <p>The enum-level assertions do not require the Pizza ontology. The
 * claim-level assertion uses the Pizza ontology to verify a SUPPORTED claim
 * (Margherita subClassOf NamedPizza) end-to-end.
 *
 * <p>Tagged {@code "integration"} so it only runs via
 * {@code .\gradlew.bat integrationTest}.
 */
@Tag("integration")
@DisabledIfSystemProperty(named = "skip.integration.test", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("v0.9.0 §8.4: Verdict vocabulary uses 'supported' (not 'verified') — D4")
class V089VerdictVocabularyTest {

    private static final String PIZZA_ONTOLOGY_ID = "pizza";
    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";

    private Path tempHome;
    private HomeDirectoryResolver homeResolver;
    private CatalogStore catalogStore;
    private OntologyImporter importer;
    private ReasonerServiceImpl reasonerService;
    private ClaimVerificationService verificationService;
    private boolean pizzaAvailable;

    @BeforeAll
    void setup() throws Exception {
        tempHome = Files.createTempDirectory("v089-verdict-vocab-test");
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
    @DisplayName("AggregateAnswerStatus.VERIFIED jsonName is 'supported' (D4 breaking change)")
    void aggregateVerifiedJsonNameIsSupported() {
        assertEquals("supported", AggregateAnswerStatus.VERIFIED.jsonName(),
            "D4: VERIFIED jsonName must be 'supported' (was 'verified' in v0.8.x)");
        assertNotEquals("verified", AggregateAnswerStatus.VERIFIED.jsonName(),
            "D4: VERIFIED jsonName must NOT be 'verified'");
    }

    @Test
    @DisplayName("Verdict.SUPPORTED jsonName is 'supported' (not 'verified')")
    void verdictSupportedJsonNameIsSupported() {
        assertEquals("supported", Verdict.SUPPORTED.jsonName(),
            "Verdict.SUPPORTED jsonName must be 'supported'");
        assertNotEquals("verified", Verdict.SUPPORTED.jsonName(),
            "Verdict.SUPPORTED jsonName must NOT be 'verified'");
        assertFalse(Verdict.SUPPORTED.jsonName().contains("verified"),
            "Verdict.SUPPORTED jsonName must not contain 'verified'");
    }

    @Test
    @DisplayName("Supported claim verification result verdict jsonName is 'supported'")
    void supportedClaimVerdictJsonNameIsSupported() {
        assumeTrue(pizzaAvailable,
            "Skipping: Pizza ontology fixture not found");

        // Margherita subClassOf NamedPizza is an asserted axiom in the pizza
        // ontology → entailed → SUPPORTED verdict.
        Claim claim = new Claim(
            "test-vv-001",
            ClaimType.SUBCLASS,
            PIZZA_ONTOLOGY_ID,
            new ClaimEntity("class", PIZZA_NS + "Margherita"),
            "subClassOf",
            new ClaimEntity("class", PIZZA_NS + "NamedPizza"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

        ServiceResult<ClaimVerificationResult> result = verificationService.verify(claim);
        assertTrue(result.isSuccess(),
            "Verification must succeed: "
                + (result instanceof ServiceResult.Error<?> e
                    ? e.error().code() + " " + e.error().message()
                    : "unknown"));

        ClaimVerificationResult cvr =
            ((ServiceResult.Success<ClaimVerificationResult>) result).data();
        assertNotNull(cvr, "result must not be null");

        assertEquals(Verdict.SUPPORTED, cvr.verdict(),
            "Margherita subClassOf NamedPizza must be SUPPORTED");
        assertEquals("supported", cvr.verdict().jsonName(),
            "verdict jsonName must be 'supported'");
        assertNotEquals("verified", cvr.verdict().jsonName(),
            "verdict jsonName must NOT be 'verified'");
        assertFalse(cvr.verdict().jsonName().contains("verified"),
            "verdict jsonName must not contain 'verified'");
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
