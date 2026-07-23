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
 * v0.8.8 Section 8.1 / task 8.1: Integration test for the A1 fix.
 *
 * <p>Verifies that the 6 A1 claims (hpo-ex-004/005/006, mondo-ex-004/005/006)
 * — all {@code disjoint_classes} claims with {@code reasoner=elk} — return
 * {@code contradicted} under v0.8.8. The A1 fix combines:
 * <ul>
 *   <li><b>D1</b>: Stage 4 forces a full DL profile reasoner (HermiT for
 *       HPO {@literal <=}20K classes, Openllet for Mondo {@literal >}20K classes)
 *       even when the claim specifies {@code reasoner=elk}.</li>
 *   <li><b>D2</b>: Stage 4 performs a class satisfiability check after the
 *       exact consistency check returns CONSISTENT. When adding the
 *       {@code DisjointClasses(C, D)} axiom makes C or D unsatisfiable,
 *       the verdict is upgraded from UNKNOWN to CONTRADICTED.</li>
 * </ul>
 *
 * <p><b>Fixture requirements</b>: This test requires the real HPO and Mondo
 * ontology files in {@code test/owl_files/}. These files are NOT committed
 * to the repository (they are in {@code .gitignore} as sensitive biomedical
 * ontologies). The test uses {@link Assumptions#assumeTrue(boolean)} to skip
 * when the fixtures are unavailable, so it does not fail in CI.
 *
 * <p>Expected fixture locations:
 * <ul>
 *   <li>HPO: {@code test/owl_files/hpo.owl} (or {@code .ttl})</li>
 *   <li>Mondo: {@code test/owl_files/mondo.owl} (or {@code .ttl})</li>
 * </ul>
 *
 * <p>Tagged {@code "integration"} so it only runs via
 * {@code .\gradlew.bat integrationTest} (excluded from the default
 * {@code test} task per root {@code build.gradle.kts}).
 *
 * <p>Acceptance contract: {@code test/contracts/v088-acceptance/a1-disjoint-classes-elk.json}
 */
@Tag("integration")
@DisabledIfSystemProperty(named = "skip.integration.test", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("v0.8.8 §8.1: A1 fix — 6 disjoint_classes claims with reasoner=elk return contradicted")
class V088A1FixIntegrationTest {

    private static final String HPO_ONTOLOGY_ID = "hpo";
    private static final String MONDO_ONTOLOGY_ID = "mondo";

    private static final String HPO_NS = "http://purl.obolibrary.org/obo/";
    private static final String MONDO_NS = "http://purl.obolibrary.org/obo/";

    private Path tempHome;
    private HomeDirectoryResolver homeResolver;
    private CatalogStore catalogStore;
    private OntologyImporter importer;
    private ReasonerServiceImpl reasonerService;
    private ClaimVerificationService verificationService;
    private String corpusFixturesPath;
    private boolean hpoAvailable;
    private boolean mondoAvailable;

    @BeforeAll
    void setup() throws Exception {
        tempHome = Files.createTempDirectory("v088-a1-integration-test");
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

        // Detect available biomedical ontology fixtures (not committed to repo).
        hpoAvailable = findOntologyFixture(HPO_ONTOLOGY_ID) != null;
        mondoAvailable = findOntologyFixture(MONDO_ONTOLOGY_ID) != null;

        if (hpoAvailable) {
            importFixture(HPO_ONTOLOGY_ID, findOntologyFixture(HPO_ONTOLOGY_ID));
        }
        if (mondoAvailable) {
            importFixture(MONDO_ONTOLOGY_ID, findOntologyFixture(MONDO_ONTOLOGY_ID));
        }
    }

    @AfterAll
    void cleanup() throws Exception {
        if (reasonerService != null) {
            if (hpoAvailable) reasonerService.shutdown(new OntologyId(HPO_ONTOLOGY_ID));
            if (mondoAvailable) reasonerService.shutdown(new OntologyId(MONDO_ONTOLOGY_ID));
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

    static Stream<Arguments> a1Claims() {
        return Stream.of(
            Arguments.of("hpo-ex-004", HPO_ONTOLOGY_ID,
                HPO_NS + "HP_0000002", HPO_NS + "HP_0001507"),
            Arguments.of("hpo-ex-005", HPO_ONTOLOGY_ID,
                HPO_NS + "HP_0000003", HPO_NS + "HP_0000107"),
            Arguments.of("hpo-ex-006", HPO_ONTOLOGY_ID,
                HPO_NS + "HP_0000005", HPO_NS + "HP_0000001"),
            Arguments.of("mondo-ex-004", MONDO_ONTOLOGY_ID,
                MONDO_NS + "MONDO_0000004", MONDO_NS + "MONDO_0002816"),
            Arguments.of("mondo-ex-005", MONDO_ONTOLOGY_ID,
                MONDO_NS + "MONDO_0000005", MONDO_NS + "MONDO_0004907"),
            Arguments.of("mondo-ex-006", MONDO_ONTOLOGY_ID,
                MONDO_NS + "MONDO_0000009", MONDO_NS + "MONDO_0002243")
        );
    }

    @ParameterizedTest(name = "{0}: disjoint_classes with reasoner=elk → contradicted")
    @MethodSource("a1Claims")
    @DisplayName("A1 claim returns contradicted via Stage 4 DL reasoner override + satisfiability check")
    void a1ClaimReturnsContradicted(String claimId, String ontologyId,
                                     String subjectIri, String objectIri) {
        // Skip if the required ontology fixture is not available locally.
        boolean available = HPO_ONTOLOGY_ID.equals(ontologyId) ? hpoAvailable : mondoAvailable;
        assumeTrue(available,
            "Skipping " + claimId + ": ontology '" + ontologyId
                + "' fixture not found in test/owl_files/");

        Claim claim = new Claim(
            claimId,
            ClaimType.DISJOINT_CLASSES,
            ontologyId,
            new ClaimEntity("class", subjectIri),
            null,
            new ClaimEntity("class", objectIri),
            Optional.of("elk"),
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

        assertEquals(Verdict.CONTRADICTED, data.verdict(),
            claimId + " must return contradicted (A1 fix: D1 DL override + D2 satisfiability check)");

        boolean hasSatEvidence = data.evidence().stream()
            .anyMatch(e -> "satisfiability-check".equals(e.source()));
        assertTrue(hasSatEvidence,
            claimId + " evidence must include a SATISFIABILITY_CHECK entry");

        // Ensure no error code leaked through (the verdict path must be COMPLETED).
        assertFalse(data.errorCode().isPresent(),
            claimId + " must not have an error code (got: " + data.errorCode() + ")");
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * Locate a biomedical ontology fixture under {@code test/owl_files/}.
     * Returns the first matching {@code .owl} / {@code .ttl} / {@code .rdf} file,
     * or {@code null} if no fixture is found.
     */
    private Path findOntologyFixture(String ontologyId) {
        Path projectRoot = Path.of("").toAbsolutePath();
        Path owlFilesDir = projectRoot.resolve("test/owl_files");
        String[] candidates = {
            ontologyId + ".owl",
            ontologyId + ".ttl",
            ontologyId + ".rdf",
            ontologyId + ".xml",
            ontologyId + ".ofn"
        };
        for (String name : candidates) {
            Path candidate = owlFilesDir.resolve(name);
            if (Files.exists(candidate)) return candidate;
        }
        // Also try the corpus fixtures path as a fallback.
        Path corpusRoot = Path.of(corpusFixturesPath);
        for (String name : candidates) {
            Path candidate = corpusRoot.resolve(name);
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
