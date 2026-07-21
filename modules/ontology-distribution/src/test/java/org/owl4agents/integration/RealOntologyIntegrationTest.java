package org.owl4agents.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.owl4agents.core.GraphScope;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.ConsistencyResult;
import org.owl4agents.core.model.EntityCounts;
import org.owl4agents.core.model.OntologySummary;
import org.owl4agents.core.model.SelectResult;
import org.owl4agents.owlapi.OntologyImporter;
import org.owl4agents.owlapi.OntologySummaryExtractor;
import org.owl4agents.query.JenaModelConverter;
import org.owl4agents.query.SparqlExecutor;
import org.owl4agents.reasoner.ReasonerServiceImpl;
import org.owl4agents.storage.CatalogStore;
import org.owl4agents.storage.HomeDirectoryResolver;
import org.owl4agents.storage.WorkspaceInitializer;

import org.apache.jena.rdf.model.Model;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real ontology integration test: loads actual OWL ontology files from
 * {@code test/corpus/} and exercises the full service stack (import,
 * summary, consistency check, classification, SPARQL) end-to-end.
 *
 * <p>Unlike unit tests that use synthetic mini-ontologies, this test
 * uses real-world ontology corpora:</p>
 * <ul>
 *   <li><b>Pizza</b> ({@code smoke/pizza.owl}) — small OWL 2 DL ontology
 *       (~100 classes), the canonical Protégé tutorial ontology.</li>
 *   <li><b>BFO</b> ({@code smoke/bfo.owl}) — medium Basic Formal
 *       Ontology, the upper-level ontology used across OBO Foundry.</li>
 *   <li><b>LUBM</b> ({@code benchmarks/lubm/univ-bench.owl}) — Lehigh
 *       University Benchmark ontology for university domain.</li>
 *   <li><b>OWL2Bench EL</b> ({@code benchmarks/owl2bench/UNIV-BENCH-OWL2EL.owl})
 *       — OWL 2 EL profile benchmark derived from LUBM.</li>
 * </ul>
 *
 * <p>For each ontology, the test verifies:</p>
 * <ol>
 *   <li>The ontology file loads successfully via OWL API.</li>
 *   <li>The ontology has a non-zero axiom count.</li>
 *   <li>{@link OntologySummaryExtractor} produces a summary with entity
 *       counts.</li>
 *   <li>{@link ReasonerServiceImpl#checkConsistency} completes (the
 *       result may be consistent or inconsistent; both are valid).</li>
 *   <li>{@link ReasonerServiceImpl#classify} completes (the inferred
 *       hierarchy is returned without error).</li>
 *   <li>A SPARQL SELECT query returns successfully via
 *       {@link SparqlExecutor}.</li>
 * </ol>
 *
 * <p>Tagged {@code "integration"} so it can be included/excluded via
 * JUnit tag filtering. Excluded from the default {@code test} task
 * (see root {@code build.gradle.kts}); run explicitly via
 * {@code ./gradlew integrationTest}.</p>
 *
 * <p>Skipped when:</p>
 * <ul>
 *   <li>{@code skip.integration.test=true} system property is set.</li>
 *   <li>The corpus fixtures directory is not found (resolved via the
 *       {@code corpus.fixtures} system property).</li>
 *   <li>An individual ontology fixture file is missing (per-ontology
 *       skip via {@code assumeTrue}).</li>
 * </ul>
 */
@Tag("integration")
@DisabledIfSystemProperty(named = "skip.integration.test", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Real ontology integration test (full service stack on real corpora)")
class RealOntologyIntegrationTest {

    private Path tempHome;
    private HomeDirectoryResolver homeResolver;
    private CatalogStore catalogStore;
    private OntologyImporter importer;
    private OntologySummaryExtractor summaryExtractor;
    private String corpusFixturesPath;

    /**
     * Set up a fresh temporary home directory and workspace for the
     * integration test. Each test run uses an isolated workspace so
     * that imported ontologies do not leak between runs.
     */
    @BeforeAll
    void setup() throws Exception {
        tempHome = Files.createTempDirectory("owl4agents-integration-test");
        homeResolver = new HomeDirectoryResolver(tempHome);
        catalogStore = new CatalogStore(homeResolver);
        importer = new OntologyImporter(homeResolver, catalogStore);
        summaryExtractor = new OntologySummaryExtractor();

        corpusFixturesPath = System.getProperty("corpus.fixtures",
            "../test/corpus");

        // Initialize the default workspace before any imports.
        WorkspaceInitializer initializer =
            new WorkspaceInitializer(homeResolver);
        ServiceResult<Void> initResult =
            initializer.initializeIdempotent(WorkspaceId.DEFAULT);
        assertTrue(initResult.isSuccess(),
            "Workspace initialization must succeed: "
                + (initResult instanceof ServiceResult.Error<?> e
                    ? e.error().code() + " " + e.error().message()
                    : "unknown"));
    }

    @AfterAll
    void cleanup() throws Exception {
        if (tempHome == null) return;
        // Best-effort cleanup of the temporary home directory.
        try {
            Files.walk(tempHome)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (Exception ignored) { }
                });
        } catch (Exception ignored) {
            // Best-effort cleanup; do not fail the test on cleanup errors.
        }
    }

    /**
     * Provide the set of ontology fixtures to test. Each fixture is a
     * tuple of (ontologyId, relativePath, displayName) where
     * {@code relativePath} is resolved against the corpus fixtures
     * directory.
     *
     * <p>The fixtures cover a range of ontology sizes and OWL profiles:</p>
     * <ul>
     *   <li>Pizza — small OWL 2 DL (~100 classes)</li>
     *   <li>BFO — medium upper ontology</li>
     *   <li>LUBM — benchmark ontology for university domain</li>
     *   <li>OWL2Bench EL — OWL 2 EL profile benchmark</li>
     * </ul>
     */
    static Stream<Arguments> ontologyFixtures() {
        return Stream.of(
            Arguments.of("pizza", "smoke/pizza.owl", "Pizza"),
            Arguments.of("bfo", "smoke/bfo.owl", "BFO"),
            Arguments.of("lubm", "benchmarks/lubm/univ-bench.owl", "LUBM"),
            Arguments.of("owl2el",
                "benchmarks/owl2bench/UNIV-BENCH-OWL2EL.owl",
                "OWL2Bench EL")
        );
    }

    @ParameterizedTest(name = "{2}: load and verify non-empty")
    @MethodSource("ontologyFixtures")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("loads successfully and has non-zero axiom count")
    void testOntologyLoadsAndIsNonEmpty(String ontologyId, String relativePath,
            String displayName) throws Exception {
        Path fixturePath = resolveFixture(relativePath);
        assumeTrue(Files.exists(fixturePath),
            "Skipping: fixture not found: " + fixturePath);

        // Load with OWL API to verify the file is a valid ontology.
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = mgr.loadOntologyFromOntologyDocument(
            fixturePath.toFile());

        long axiomCount = ontology.getAxiomCount();
        assertTrue(axiomCount > 0,
            displayName + " ontology must have at least one axiom, got "
                + axiomCount);
        assertFalse(ontology.getClassesInSignature().isEmpty(),
            displayName + " ontology must have at least one class in signature");
    }

    @ParameterizedTest(name = "{2}: summary extraction")
    @MethodSource("ontologyFixtures")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("ontology_summary returns non-empty entity counts")
    void testOntologySummary(String ontologyId, String relativePath,
            String displayName) throws Exception {
        Path fixturePath = resolveFixture(relativePath);
        assumeTrue(Files.exists(fixturePath),
            "Skipping: fixture not found: " + fixturePath);

        // Import the ontology into the catalog (idempotent).
        importFixture(ontologyId, fixturePath, displayName);

        // Extract summary directly from the loaded ontology.
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = mgr.loadOntologyFromOntologyDocument(
            fixturePath.toFile());
        OntologySummary summary = summaryExtractor.buildSummary(
            new OntologyId(ontologyId), ontology);

        assertNotNull(summary, displayName + " summary must not be null");
        assertNotNull(summary.entityCounts(),
            displayName + " summary must have entity counts");
        EntityCounts counts = summary.entityCounts();
        // At least one count should be non-zero for a real ontology.
        assertTrue(
            counts.classes() > 0
                || counts.objectProperties() > 0
                || counts.dataProperties() > 0
                || counts.individuals() > 0,
            displayName + " must have at least one non-zero entity count");
    }

    @ParameterizedTest(name = "{2}: consistency check (reasoner=auto)")
    @MethodSource("ontologyFixtures")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("checkConsistency completes with reasoner=auto")
    void testConsistencyCheck(String ontologyId, String relativePath,
            String displayName) {
        Path fixturePath = resolveFixture(relativePath);
        assumeTrue(Files.exists(fixturePath),
            "Skipping: fixture not found: " + fixturePath);

        importFixture(ontologyId, fixturePath, displayName);

        ReasonerServiceImpl reasonerService = createReasonerService();
        try {
            ServiceResult<ConsistencyResult> result =
                reasonerService.checkConsistency(
                    new OntologyId(ontologyId), Optional.empty());
            assertTrue(result.isSuccess(),
                displayName + " consistency check must succeed: "
                    + (result instanceof ServiceResult.Error<?> e
                        ? e.error().code() + " " + e.error().message()
                        : "unknown"));
            ConsistencyResult data =
                ((ServiceResult.Success<ConsistencyResult>) result).data();
            assertNotNull(data, displayName + " consistency result must not be null");
        } finally {
            reasonerService.shutdown(new OntologyId(ontologyId));
        }
    }

    @ParameterizedTest(name = "{2}: classification (reasoner=auto)")
    @MethodSource("ontologyFixtures")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("classify completes with reasoner=auto")
    void testClassification(String ontologyId, String relativePath,
            String displayName) {
        Path fixturePath = resolveFixture(relativePath);
        assumeTrue(Files.exists(fixturePath),
            "Skipping: fixture not found: " + fixturePath);

        importFixture(ontologyId, fixturePath, displayName);

        ReasonerServiceImpl reasonerService = createReasonerService();
        try {
            ServiceResult<org.owl4agents.core.model.ClassificationResult> result =
                reasonerService.classify(
                    new OntologyId(ontologyId), Optional.empty());
            assertTrue(result.isSuccess(),
                displayName + " classification must succeed: "
                    + (result instanceof ServiceResult.Error<?> e
                        ? e.error().code() + " " + e.error().message()
                        : "unknown"));
            org.owl4agents.core.model.ClassificationResult data =
                ((ServiceResult.Success<org.owl4agents.core.model.ClassificationResult>) result).data();
            assertNotNull(data,
                displayName + " classification result must not be null");
        } finally {
            reasonerService.shutdown(new OntologyId(ontologyId));
        }
    }

    @ParameterizedTest(name = "{2}: SPARQL SELECT query")
    @MethodSource("ontologyFixtures")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("SPARQL SELECT query executes without error")
    void testSparqlSelect(String ontologyId, String relativePath,
            String displayName) {
        Path fixturePath = resolveFixture(relativePath);
        assumeTrue(Files.exists(fixturePath),
            "Skipping: fixture not found: " + fixturePath);

        // Convert the ontology file to a Jena model for SPARQL access.
        JenaModelConverter converter = new JenaModelConverter();
        ServiceResult<Model> modelResult = converter.convertFromPath(
            new OntologyId(ontologyId), fixturePath);
        assertTrue(modelResult.isSuccess(),
            displayName + " Jena model conversion must succeed: "
                + (modelResult instanceof ServiceResult.Error<?> e
                    ? e.error().code() + " " + e.error().message()
                    : "unknown"));
        Model jenaModel =
            ((ServiceResult.Success<Model>) modelResult).data();
        assertNotNull(jenaModel, displayName + " Jena model must not be null");

        // Execute a simple SELECT query: list all classes.
        SparqlExecutor executor = new SparqlExecutor(30_000, 100);
        String sparql = "SELECT ?class WHERE { "
            + "?class <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
            + "<http://www.w3.org/2002/07/owl#Class> "
            + "} LIMIT 10";
        ServiceResult<SelectResult> result = executor.executeSelect(
            new OntologyId(ontologyId), sparql, jenaModel,
            GraphScope.EXPLICIT);
        assertTrue(result.isSuccess(),
            displayName + " SPARQL SELECT must succeed: "
                + (result instanceof ServiceResult.Error<?> e
                    ? e.error().code() + " " + e.error().message()
                    : "unknown"));
        SelectResult data =
            ((ServiceResult.Success<SelectResult>) result).data();
        assertNotNull(data, displayName + " SELECT result must not be null");
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private Path resolveFixture(String relativePath) {
        Path basePath = Path.of(corpusFixturesPath);
        Path fixturePath = basePath.resolve(relativePath);
        if (Files.exists(fixturePath)) return fixturePath;
        // Fall back to project-relative path (matches V01AcceptanceSuite).
        return Path.of("test/corpus").resolve(relativePath);
    }

    /**
     * Import a fixture into the catalog (idempotent). If the ontology
     * is already imported, this is a no-op.
     */
    private void importFixture(String ontologyId, Path fixturePath,
            String displayName) {
        var existing = catalogStore.findEntry(
            WorkspaceId.DEFAULT, new OntologyId(ontologyId));
        if (existing.isSuccess()) return;

        ServiceResult<Void> result = importer.importOntology(
            new OntologyId(ontologyId), fixturePath, WorkspaceId.DEFAULT);
        assertTrue(result.isSuccess(),
            displayName + " import must succeed: "
                + (result instanceof ServiceResult.Error<?> e
                    ? e.error().code() + " " + e.error().message()
                    : "unknown"));
    }

    /**
     * Create a ReasonerServiceImpl wired to the temp home directory.
     * The workspaceBasePath must point at the {@code workspaces/}
     * directory so that {@code workspaceName=default} resolves to the
     * correct per-ontology subdirectories created by the importer.
     */
    private ReasonerServiceImpl createReasonerService() {
        String workspaceBasePath = homeResolver.resolveHomeDirectory()
            .resolve("workspaces").toString();
        return new ReasonerServiceImpl(catalogStore, workspaceBasePath);
    }
}
