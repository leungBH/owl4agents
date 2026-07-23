package org.owl4agents.validation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * v0.8.8 Section 8.2 / task 8.2: Integration test for the A2 fix.
 *
 * <p>Verifies that the 11 A2 claims on the Pizza ontology — 5 subclass
 * (pizza-sc-005~009), 3 equivalent_classes (pizza-ec-005~007), and 3
 * disjoint_classes (pizza-dc-005~007) — return {@code contradicted} under
 * v0.8.8. The A2 fix relies on:
 * <ul>
 *   <li><b>D2</b>: Stage 4 performs a class satisfiability check after the
 *       exact consistency check returns CONSISTENT. Adding the claim axiom
 *       makes a class unsatisfiable (without making the whole ontology
 *       inconsistent, since Pizza has no individual witness for the class),
 *       so the verdict is upgraded from UNKNOWN to CONTRADICTED.</li>
 *   <li><b>D3</b>: The 3-state verdict mapping (INCONSISTENT→CONTRADICTED,
 *       CONSISTENT+unsat→CONTRADICTED, CONSISTENT+sat→UNKNOWN).</li>
 * </ul>
 *
 * <p><b>Fixture</b>: The Pizza ontology is a small OWL 2 DL ontology (~100
 * classes) committed to the repository at {@code test/corpus/smoke/pizza.owl}.
 * The test loads it from there (or from {@code test/owl_files/pizza.owl} as
 * a fallback) and uses {@link Assumptions#assumeTrue(boolean)} to skip if
 * the fixture is missing.
 *
 * <p>Tagged {@code "integration"} so it only runs via
 * {@code .\gradlew.bat integrationTest}.
 *
 * <p>Acceptance contract: {@code test/contracts/v088-acceptance/a2-class-level-contradictions.json}
 */
@Tag("integration")
@DisabledIfSystemProperty(named = "skip.integration.test", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("v0.8.8 §8.2: A2 fix — 11 Pizza class-level claims return contradicted")
class V088A2FixIntegrationTest {

    private static final String PIZZA_ONTOLOGY_ID = "pizza";
    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";

    private Path tempHome;
    private HomeDirectoryResolver homeResolver;
    private CatalogStore catalogStore;
    private OntologyImporter importer;
    private ReasonerServiceImpl reasonerService;
    private ClaimVerificationService verificationService;
    private String corpusFixturesPath;
    private boolean pizzaAvailable;

    @BeforeAll
    void setup() throws Exception {
        tempHome = Files.createTempDirectory("v088-a2-integration-test");
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

    static Stream<Arguments> a2SubclassClaims() {
        return Stream.of(
            Arguments.of("pizza-sc-005", PIZZA_NS + "Margherita", PIZZA_NS + "PizzaTopping"),
            Arguments.of("pizza-sc-006", PIZZA_NS + "CheeseTopping", PIZZA_NS + "MeatTopping"),
            Arguments.of("pizza-sc-007", PIZZA_NS + "DeepPanBase", PIZZA_NS + "ThinAndCrispyBase"),
            Arguments.of("pizza-sc-008", PIZZA_NS + "VegetarianPizza", PIZZA_NS + "NonVegetarianPizza"),
            Arguments.of("pizza-sc-009", PIZZA_NS + "Margherita", PIZZA_NS + "SpicyPizza")
        );
    }

    static Stream<Arguments> a2EquivalentClassesClaims() {
        return Stream.of(
            Arguments.of("pizza-ec-005", PIZZA_NS + "Pizza", PIZZA_NS + "PizzaTopping"),
            Arguments.of("pizza-ec-006", PIZZA_NS + "Hot", PIZZA_NS + "Mild"),
            Arguments.of("pizza-ec-007", PIZZA_NS + "VegetarianPizza", PIZZA_NS + "NonVegetarianPizza")
        );
    }

    static Stream<Arguments> a2DisjointClassesClaims() {
        return Stream.of(
            Arguments.of("pizza-dc-005", PIZZA_NS + "Margherita", PIZZA_NS + "NamedPizza"),
            Arguments.of("pizza-dc-006", PIZZA_NS + "MozzarellaTopping", PIZZA_NS + "CheeseTopping"),
            Arguments.of("pizza-dc-007", PIZZA_NS + "Pizza", PIZZA_NS + "Food")
        );
    }

    @ParameterizedTest(name = "{0}: subclass → contradicted")
    @MethodSource("a2SubclassClaims")
    @DisplayName("A2 subclass claim returns contradicted via satisfiability check")
    void a2SubclassClaimReturnsContradicted(String claimId, String subjectIri, String objectIri) {
        assumeTrue(pizzaAvailable,
            "Skipping " + claimId + ": Pizza ontology fixture not found");

        Claim claim = new Claim(
            claimId,
            ClaimType.SUBCLASS,
            PIZZA_ONTOLOGY_ID,
            new ClaimEntity("class", subjectIri),
            "http://www.w3.org/2000/01/rdf-schema#subClassOf",
            new ClaimEntity("class", objectIri),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

        assertContradictedWithSatisfiabilityEvidence(claim);
    }

    @ParameterizedTest(name = "{0}: equivalent_classes → contradicted")
    @MethodSource("a2EquivalentClassesClaims")
    @DisplayName("A2 equivalent_classes claim returns contradicted via satisfiability check")
    void a2EquivalentClassesClaimReturnsContradicted(String claimId, String subjectIri, String objectIri) {
        assumeTrue(pizzaAvailable,
            "Skipping " + claimId + ": Pizza ontology fixture not found");

        Claim claim = new Claim(
            claimId,
            ClaimType.EQUIVALENT_CLASSES,
            PIZZA_ONTOLOGY_ID,
            new ClaimEntity("class", subjectIri),
            null,
            new ClaimEntity("class", objectIri),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

        assertContradictedWithSatisfiabilityEvidence(claim);
    }

    @ParameterizedTest(name = "{0}: disjoint_classes → contradicted")
    @MethodSource("a2DisjointClassesClaims")
    @DisplayName("A2 disjoint_classes claim returns contradicted via satisfiability check")
    void a2DisjointClassesClaimReturnsContradicted(String claimId, String subjectIri, String objectIri) {
        assumeTrue(pizzaAvailable,
            "Skipping " + claimId + ": Pizza ontology fixture not found");

        Claim claim = new Claim(
            claimId,
            ClaimType.DISJOINT_CLASSES,
            PIZZA_ONTOLOGY_ID,
            new ClaimEntity("class", subjectIri),
            null,
            new ClaimEntity("class", objectIri),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

        assertContradictedWithSatisfiabilityEvidence(claim);
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private void assertContradictedWithSatisfiabilityEvidence(Claim claim) {
        ServiceResult<ClaimVerificationResult> result = verificationService.verify(claim);
        assertTrue(result.isSuccess(),
            claim.claimId() + " verification must succeed: "
                + (result instanceof ServiceResult.Error<?> e
                    ? e.error().code() + " " + e.error().message()
                    : "unknown"));

        ClaimVerificationResult data =
            ((ServiceResult.Success<ClaimVerificationResult>) result).data();
        assertNotNull(data, claim.claimId() + " result must not be null");

        assertEquals(Verdict.CONTRADICTED, data.verdict(),
            claim.claimId() + " must return contradicted (A2 fix: D2 satisfiability check)");

        boolean hasSatEvidence = data.evidence().stream()
            .anyMatch(e -> "satisfiability-check".equals(e.source()));
        assertTrue(hasSatEvidence,
            claim.claimId() + " evidence must include a SATISFIABILITY_CHECK entry");

        assertFalse(data.errorCode().isPresent(),
            claim.claimId() + " must not have an error code (got: " + data.errorCode() + ")");
    }

    private Path findPizzaFixture() {
        // Try multiple candidate locations for the Pizza ontology.
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
