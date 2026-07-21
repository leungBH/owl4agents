package org.owl4agents.validation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.model.ClaimVerificationResult;
import org.owl4agents.core.model.ExecutionStatus;
import org.owl4agents.core.model.ReasonerCallMetadata;
import org.owl4agents.core.model.Verdict;
import org.owl4agents.owlapi.EntitySignatureCacheManager;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.owlapi.OntologyImporter;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.ReasonerServiceImpl;
import org.owl4agents.storage.CatalogStore;
import org.owl4agents.storage.HomeDirectoryResolver;
import org.owl4agents.storage.WorkspaceInitializer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.6 task 3.10d: Verify {@code ClaimVerificationResult.metadata} is populated
 * from the LAST non-null stage's reasoner call metadata in the 5-stage pipeline.
 *
 * <p>Test cases (per design D11):</p>
 * <ul>
 *   <li><b>META-1</b>: Stage 4 metadata wins over Stage 3 — when the pipeline
 *       reaches Stage 4 (UNKNOWN/CONTRADICTED), the result metadata comes from
 *       the exact consistency check, not the entailment check.</li>
 *   <li><b>META-2</b>: Stage 1 short-circuit produces {@code metadata = null} —
 *       the same-individual self-contradiction path skips all reasoner calls.</li>
 *   <li><b>META-3</b>: Asserted-axiom fast-path preserves Stage 2's metadata —
 *       when Stage 3 finds the axiom already asserted (no reasoner inference),
 *       Stage 3 produces no metadata, so Stage 2's metadata is preserved.</li>
 *   <li><b>META-4</b>: Stage 2 timeout metadata is preserved — when the source
 *       consistency check times out, the error result carries the timeout
 *       metadata.</li>
 * </ul>
 */
@DisplayName("v0.8.6 task 3.10d: ClaimVerificationResult metadata flow")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClaimVerificationResultMetadataFlowTest {

    private static final String FIXTURES_BASE = "test/corpus/exact-consistency";
    private static final String REASONER = "HermiT";

    @TempDir
    Path tempDir;

    private HomeDirectoryResolver homeResolver;
    private WorkspaceInitializer initializer;
    private CatalogStore catalogStore;
    private OntologyImporter importer;
    private OntologyCache ontologyCache;
    private EntitySignatureCacheManager escManager;
    private ReasonerServiceImpl reasonerService;
    private ClaimVerificationService verificationService;

    private Path fixturesDir;

    @BeforeAll
    void initFixturesDir() {
        fixturesDir = resolveFixturesDir();
    }

    @BeforeEach
    void setUp() throws Exception {
        homeResolver = new HomeDirectoryResolver(tempDir);
        initializer = new WorkspaceInitializer(homeResolver);
        catalogStore = new CatalogStore(homeResolver);
        importer = new OntologyImporter(homeResolver, catalogStore);
        System.setProperty("OWL4AGENTS_HOME", tempDir.toString());
        initializer.initializeIdempotent(WorkspaceId.DEFAULT);

        // Import the fixtures needed for metadata tests
        importFixture("disjoint-no-witness");
        importFixture("disjoint-with-witness");
        importFixture("empty-class");
        importFixture("timeout-large");

        String basePath = homeResolver.resolveHomeDirectory().resolve("workspaces").toString();
        ontologyCache = new OntologyCache(basePath, "default");
        escManager = new EntitySignatureCacheManager();
        ontologyCache.addReloadListener(escManager);
        reasonerService = new ReasonerServiceImpl(catalogStore, basePath, "default", ontologyCache, escManager);

        // Pre-run reasoner for each ontology to populate inferred hierarchy
        for (String ontId : new String[]{"disjoint-no-witness", "disjoint-with-witness", "empty-class", "timeout-large"}) {
            try {
                reasonerService.runReasoner(new OntologyId(ontId), Optional.of(REASONER));
            } catch (Exception e) {
                // Best-effort
            }
        }

        ConsistencyAnalysisService consistencyService =
            new ConsistencyAnalysisService(reasonerService.getLifecycleManager(), basePath, ontologyCache, escManager);
        SemanticDeepeningService deepeningService = new SemanticDeepeningService(basePath, ontologyCache);
        verificationService = new ClaimVerificationService(
            reasonerService, consistencyService, deepeningService, catalogStore, WorkspaceId.DEFAULT);
    }

    // ════════════════════════════════════════════════════════════════════
    // META-1: Stage 4 metadata wins over Stage 3
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("META-1: Stage 4 metadata is populated for UNKNOWN verdict (reaches Stage 4)")
    void stage4MetadataPopulatedForUnknown() {
        // disjoint-no-witness: C⊑D is not asserted, not entailed → reaches Stage 4
        // Stage 4: O ∪ {C⊑D} is consistent → UNKNOWN
        String ns = "http://owl4agents.org/test/exact-consistency/disjoint-no-witness#";
        Claim claim = subclassClaim("meta-1", ns + "C", ns + "D", "disjoint-no-witness");
        ClaimVerificationResult result = extract(verificationService.verify(claim));

        assertEquals(Verdict.UNKNOWN, result.verdict(),
            "Disjoint without witness should yield UNKNOWN");
        assertNotNull(result.metadata(),
            "Stage 4 result must have non-null metadata (last stage that invoked a reasoner)");
        assertNotNull(result.metadata().reasonerName(),
            "metadata.reasonerName must be populated");
        assertTrue(result.metadata().timeoutMs() > 0,
            "metadata.timeoutMs must be positive (wrapper default)");
    }

    @Test
    @DisplayName("META-1b: Stage 4 metadata is populated for CONTRADICTED verdict")
    void stage4MetadataPopulatedForContradicted() {
        // disjoint-with-witness: C⊑D is not asserted, not entailed → reaches Stage 4
        // Stage 4: O ∪ {C⊑D} is inconsistent (witness 'a' in both C and D) → CONTRADICTED
        String ns = "http://owl4agents.org/test/exact-consistency/disjoint-with-witness#";
        Claim claim = subclassClaim("meta-1b", ns + "C", ns + "D", "disjoint-with-witness");
        ClaimVerificationResult result = extract(verificationService.verify(claim));

        assertEquals(Verdict.CONTRADICTED, result.verdict(),
            "Disjoint with witness should yield CONTRADICTED");
        assertNotNull(result.metadata(),
            "Stage 4 result must have non-null metadata for CONTRADICTED verdict");
    }

    // ════════════════════════════════════════════════════════════════════
    // META-2: Stage 1 short-circuit produces metadata = null
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("META-2: Stage 1 same-individual short-circuit produces metadata = null")
    void stage1ShortCircuitYieldsNullMetadata() {
        // DISJOINT_CLASSES with same individual subject and object → Stage 1 short-circuit
        // No reasoner call is made → metadata must be null
        String ns = "http://owl4agents.org/test/exact-consistency/disjoint-with-witness#";
        Claim claim = new Claim("meta-2", ClaimType.DISJOINT_CLASSES, "disjoint-with-witness",
            new ClaimEntity("individual", ns + "a"),
            "http://www.w3.org/2002/07/owl#differentFrom",
            new ClaimEntity("individual", ns + "a"),  // same IRI as subject
            Optional.empty(), Optional.empty(), Optional.empty());

        ClaimVerificationResult result = extract(verificationService.verify(claim));
        assertEquals(Verdict.CONTRADICTED, result.verdict(),
            "Same-individual DISJOINT_CLASSES should yield CONTRADICTED via Stage 1 short-circuit");
        assertNull(result.metadata(),
            "Stage 1 short-circuit must produce null metadata (no reasoner invoked)");
    }

    // ════════════════════════════════════════════════════════════════════
    // META-3: Asserted-axiom fast-path preserves Stage 2's metadata
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("META-3: Asserted-axiom fast-path (SUPPORTED) has non-null metadata from Stage 2")
    void assertedAxiomFastPathPreservesStage2Metadata() {
        // empty-class: C⊑owl:Nothing is asserted → Stage 3 fast-path returns ENTAILED
        // Stage 3 asserted fast-path doesn't invoke the reasoner → stage3Meta = null
        // Therefore metadata should come from Stage 2 (source consistency check)
        String ns = "http://owl4agents.org/test/exact-consistency/empty-class#";
        Claim claim = subclassClaim("meta-3", ns + "C", "http://www.w3.org/2002/07/owl#Nothing", "empty-class");
        ClaimVerificationResult result = extract(verificationService.verify(claim));

        assertEquals(Verdict.SUPPORTED, result.verdict(),
            "Asserted C⊑owl:Nothing should yield SUPPORTED via Stage 3 asserted fast-path");
        // Stage 2 (source consistency) runs and produces metadata via the wrapper
        assertNotNull(result.metadata(),
            "Asserted-axiom fast-path should preserve Stage 2's metadata (not null)");
        assertNotNull(result.metadata().reasonerName(),
            "Stage 2 metadata should have reasonerName populated");
    }

    // ════════════════════════════════════════════════════════════════════
    // META-4: Stage 2 timeout metadata is preserved
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("META-4: Stage 4 timeout preserves metadata with executorRecovered=true")
    void stage4TimeoutPreservesMetadata() {
        // timeout-large with Duration.ZERO → Stage 4 immediate timeout
        // The wrapper returns REASONER_TIMEOUT with metadata.executorRecovered=true
        String ns = "http://owl4agents.org/test/exact-consistency/timeout-large#";
        Claim claim = subclassClaim("meta-4", ns + "Root", ns + "L2A1", "timeout-large");
        ClaimVerificationResult result = extract(verificationService.verify(claim, Duration.ZERO));

        assertEquals(ExecutionStatus.TIMEOUT, result.executionStatus(),
            "Zero timeout should yield TIMEOUT execution status");
        assertTrue(result.errorCode().isPresent());
        assertEquals(ErrorCode.REASONER_TIMEOUT, result.errorCode().get());

        assertNotNull(result.metadata(),
            "Timeout result must have non-null metadata from the wrapper");
        assertTrue(result.metadata().executorRecovered(),
            "Timeout metadata must have executorRecovered=true (executor was recovered)");
        assertTrue(result.metadata().timeoutMs() == 0,
            "Timeout metadata.timeoutMs should be 0 (Duration.ZERO)");
    }

    // ════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════

    private Claim subclassClaim(String id, String subIri, String objIri, String ontologyId) {
        return new Claim(id, ClaimType.SUBCLASS, ontologyId,
            new ClaimEntity("class", subIri),
            "http://www.w3.org/2000/01/rdf-schema#subClassOf",
            new ClaimEntity("class", objIri),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private ClaimVerificationResult extract(ServiceResult<ClaimVerificationResult> result) {
        assertTrue(result.isSuccess(), "ServiceResult should be success: " + result);
        return ((ServiceResult.Success<ClaimVerificationResult>) result).data();
    }

    private void importFixture(String ontologyId) throws Exception {
        Path owlFile = fixturesDir.resolve(ontologyId + ".owl");
        assertTrue(Files.exists(owlFile),
            "Fixture ontology not found: " + owlFile);
        importer.importOntology(new OntologyId(ontologyId), owlFile, WorkspaceId.DEFAULT);
    }

    private Path resolveFixturesDir() {
        Path cwd = Path.of("").toAbsolutePath();
        for (int i = 0; i < 10 && cwd != null; i++) {
            Path candidate = cwd.resolve(FIXTURES_BASE);
            if (Files.exists(candidate)) {
                return candidate;
            }
            cwd = cwd.getParent();
        }
        return Path.of(FIXTURES_BASE);
    }
}
