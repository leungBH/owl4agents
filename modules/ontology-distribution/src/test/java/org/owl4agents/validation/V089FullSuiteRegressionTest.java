package org.owl4agents.validation;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * v0.9.0 Section 8.1 / task 8.1: P0 regression integration test for the
 * v089-entity-declaration-fix change.
 *
 * <p>Verifies that the 100 Pizza claims from
 * {@code test/corpus/v089-full-suite/question-sets/pizza-112.jsonl} do NOT
 * return {@code out_of_scope} due to global cache eviction (the P0 regression).
 * The v089 fix introduces:
 * <ul>
 *   <li><b>D1</b>: per-ontology {@code EntitySignatureCache} so loading another
 *       ontology does not evict pizza entities from a global cache.</li>
 *   <li><b>D2</b>: precise invalidation scoped to a single ontology.</li>
 *   <li><b>D3</b>: reserved predicate skipping in {@code detectMissingEntities}.</li>
 *   <li><b>D4</b>: verdict vocabulary change from "verified" to "supported".</li>
 * </ul>
 *
 * <p><b>Core P0 assertion</b>: ZERO pizza-namespace claims return
 * {@code out_of_scope}. The 2 {@code external#Virus} claims (subject outside
 * the pizza namespace) legitimately expect {@code out_of_scope} and are
 * verified against their expected verdict.
 *
 * <p>Tagged {@code "integration"} so it only runs via
 * {@code .\gradlew.bat integrationTest}.
 */
@Tag("integration")
@DisabledIfSystemProperty(named = "skip.integration.test", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("v0.9.0 §8.1: P0 regression — Pizza claims do not return out_of_scope due to cache eviction")
class V089FullSuiteRegressionTest {

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
        tempHome = Files.createTempDirectory("v089-full-suite-test");
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

    static Stream<Arguments> pizzaClaimsProvider() {
        Path jsonlPath = resolvePizzaSuiteFixture();
        if (jsonlPath == null) {
            // Return a single SKIP marker so JUnit doesn't throw
            // PreconditionViolationException for empty @MethodSource.
            return Stream.of(Arguments.of("SKIP", "subclass", "class", "",
                null, "class", "", "unknown", false));
        }
        Gson gson = new Gson();
        List<Arguments> args = new ArrayList<>();
        try (Stream<String> lines = Files.lines(jsonlPath)) {
            lines.filter(line -> !line.isBlank()).forEach(line -> {
                JsonObject obj = gson.fromJson(line, JsonObject.class);
                if (obj == null) return;
                String expectedVerdict = obj.has("expectedVerdict") && !obj.get("expectedVerdict").isJsonNull()
                    ? obj.get("expectedVerdict").getAsString() : "unknown";
                JsonArray claims = obj.getAsJsonArray("claims");
                if (claims == null || claims.isEmpty()) return;
                JsonObject claim = claims.get(0).getAsJsonObject();
                String id = claim.has("id") && !claim.get("id").isJsonNull()
                    ? claim.get("id").getAsString() : "unknown";
                String type = claim.has("type") && !claim.get("type").isJsonNull()
                    ? claim.get("type").getAsString() : "subclass";
                JsonObject subjectObj = claim.getAsJsonObject("subject");
                String subjectKind = subjectObj.has("kind") && !subjectObj.get("kind").isJsonNull()
                    ? subjectObj.get("kind").getAsString() : "class";
                String subjectIri = subjectObj.get("iri").getAsString();
                String predicate = claim.has("predicate") && !claim.get("predicate").isJsonNull()
                    ? claim.get("predicate").getAsString() : null;
                JsonObject objectObj = claim.getAsJsonObject("object");
                String objectKind = objectObj.has("kind") && !objectObj.get("kind").isJsonNull()
                    ? objectObj.get("kind").getAsString() : "class";
                String objectIri = objectObj.get("iri").getAsString();
                // P0 regression check only applies to pizza-namespace claims
                // whose expected verdict is NOT out_of_scope. Deliberately fake
                // IRIs in the pizza namespace (e.g. UncertainTopping, DragonPizza)
                // legitimately expect out_of_scope and must not trigger the
                // P0 assertion.
                boolean isPizzaNamespaceRegressionCheck =
                    subjectIri.startsWith(PIZZA_NS) && objectIri.startsWith(PIZZA_NS)
                    && !"out_of_scope".equals(expectedVerdict);
                args.add(Arguments.of(id, type, subjectKind, subjectIri, predicate,
                    objectKind, objectIri, expectedVerdict, isPizzaNamespaceRegressionCheck));
            });
        } catch (IOException e) {
            return Stream.of(Arguments.of("SKIP", "subclass", "class", "",
                null, "class", "", "unknown", false));
        }
        if (args.isEmpty()) {
            return Stream.of(Arguments.of("SKIP", "subclass", "class", "",
                null, "class", "", "unknown", false));
        }
        return args.stream();
    }

    @ParameterizedTest(name = "{0}: verdict matches {7}")
    @MethodSource("pizzaClaimsProvider")
    @DisplayName("Pizza claim from full suite does not return out_of_scope (P0 regression)")
    void pizzaClaimVerdictMatchesExpected(String claimId, String claimType, String subjectKind,
                                           String subjectIri, String predicate, String objectKind,
                                           String objectIri, String expectedVerdict,
                                           boolean isPizzaNamespaceRegressionCheck) {
        // Skip if fixture was not found (SKIP marker from @MethodSource).
        assumeTrue(!"SKIP".equals(claimId),
            "Skipping: pizza-112.jsonl fixture not found or unreadable");
        assumeTrue(pizzaAvailable,
            "Skipping " + claimId + ": Pizza ontology fixture not found");

        ClaimType type = ClaimType.fromJsonName(claimType);
        assertNotNull(type, claimId + ": unrecognized claim type " + claimType);

        Claim claim = new Claim(
            claimId,
            type,
            PIZZA_ONTOLOGY_ID,
            new ClaimEntity(subjectKind, subjectIri),
            predicate,
            new ClaimEntity(objectKind, objectIri),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

        ServiceResult<ClaimVerificationResult> result = verificationService.verify(claim);
        assertTrue(result.isSuccess(),
            claimId + " verification must succeed: "
                + (result instanceof ServiceResult.Error<?> e
                    ? e.error().code() + " " + e.error().message()
                    : "unknown"));

        ClaimVerificationResult data =
            ((ServiceResult.Success<ClaimVerificationResult>) result).data();
        assertNotNull(data, claimId + " result must not be null");

        Verdict actual = data.verdict();
        Verdict expected = Verdict.fromJsonName(expectedVerdict);
        // Treat null verdict (verification unsupported for this predicate, e.g.
        // inverseOf) as UNKNOWN when expected is UNKNOWN. This is a pre-existing
        // limitation, not a P0 regression.
        if (actual == null && expected == Verdict.UNKNOWN) {
            actual = Verdict.UNKNOWN;
        }
        assertEquals(expected, actual,
            claimId + ": expected verdict " + expectedVerdict
                + " but got " + (actual != null ? actual.jsonName() : "null"));

        // Core P0 regression assertion: pizza-namespace claims whose expected
        // verdict is NOT out_of_scope MUST NOT return out_of_scope due to global
        // cache eviction. Deliberately fake pizza-namespace IRIs (e.g.
        // UncertainTopping, DragonPizza) and external#Virus legitimately expect
        // out_of_scope and are excluded from this check via
        // isPizzaNamespaceRegressionCheck=false.
        if (isPizzaNamespaceRegressionCheck) {
            assertNotEquals(Verdict.OUT_OF_SCOPE, actual,
                claimId + ": pizza-namespace claim must NOT return out_of_scope (P0 regression)");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private static Path resolvePizzaSuiteFixture() {
        Path projectRoot = Path.of("").toAbsolutePath();
        Path[] candidates = {
            projectRoot.resolve("test/corpus/v089-full-suite/question-sets/pizza-112.jsonl"),
            projectRoot.resolve("../test/corpus/v089-full-suite/question-sets/pizza-112.jsonl"),
            // Absolute fallback for when working dir is not project root
            Path.of("D:/owl4agents/test/corpus/v089-full-suite/question-sets/pizza-112.jsonl")
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) return candidate;
        }
        return null;
    }

    private Path findPizzaFixture() {
        Path projectRoot = Path.of("").toAbsolutePath();
        Path[] candidates = {
            projectRoot.resolve("test/corpus/smoke/pizza.owl"),
            projectRoot.resolve("test/owl_files/pizza.owl"),
            projectRoot.resolve("test/corpus/pizza.owl"),
            projectRoot.resolve("test/corpus/pizza/pizza.owl"),
            Path.of(corpusFixturesPath).resolve("smoke/pizza.owl"),
            Path.of(corpusFixturesPath).resolve("pizza.owl"),
            // Absolute fallback: the canonical pizza ontology in the workspace
            Path.of("D:/owl4agents/data/workspaces/default/ontologies/pizza/canonical/ontology.owl")
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
