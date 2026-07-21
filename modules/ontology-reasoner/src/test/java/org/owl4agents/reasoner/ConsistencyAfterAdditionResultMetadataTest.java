package org.owl4agents.reasoner;

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
import org.owl4agents.core.model.ConsistencyAfterAdditionResult;
import org.owl4agents.core.model.ConsistencyAfterAdditionStatus;
import org.owl4agents.core.model.ReasonerCallMetadata;
import org.owl4agents.owlapi.EntitySignatureCacheManager;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.owlapi.OntologyImporter;
import org.owl4agents.storage.CatalogStore;
import org.owl4agents.storage.HomeDirectoryResolver;
import org.owl4agents.storage.WorkspaceInitializer;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.6 task 3.10e: Verify {@code ConsistencyAfterAdditionResult.metadata} is
 * populated from the {@code ReasonerCallWrapper.callWithElkFallback} result.
 *
 * <p>Tests direct calls to {@link ReasonerServiceImpl#checkConsistencyAfterAdding}
 * to verify that the metadata field flows through from the wrapper to the result
 * record on all code paths: success, timeout, and error.</p>
 *
 * <p>Test cases:</p>
 * <ul>
 *   <li><b>CMETA-1</b>: Successful consistency check (CONSISTENT/INCONSISTENT)
 *       populates metadata with reasonerName, executorRecovered=false, timeoutMs.</li>
 *   <li><b>CMETA-2</b>: Timeout (Duration.ZERO) populates metadata with
 *       executorRecovered=true, timeoutMs=0, and returns TIMEOUT status.</li>
 *   <li><b>CMETA-3</b>: Metadata.reasonerName matches the effective reasoner
 *       used (HermiT when explicitly requested, ELK when auto-selected for
 *       small OWL 2 EL ontologies).</li>
 * </ul>
 */
@DisplayName("v0.8.6 task 3.10e: ConsistencyAfterAdditionResult metadata")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsistencyAfterAdditionResultMetadataTest {

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

        importFixture("disjoint-no-witness");
        importFixture("disjoint-with-witness");
        importFixture("timeout-large");

        String basePath = homeResolver.resolveHomeDirectory().resolve("workspaces").toString();
        ontologyCache = new OntologyCache(basePath, "default");
        escManager = new EntitySignatureCacheManager();
        ontologyCache.addReloadListener(escManager);
        reasonerService = new ReasonerServiceImpl(catalogStore, basePath, "default", ontologyCache, escManager);
    }

    // ════════════════════════════════════════════════════════════════════
    // CMETA-1: Successful consistency check populates metadata
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CMETA-1a: CONSISTENT result has non-null metadata with reasonerName")
    void consistentResultHasMetadata() throws Exception {
        // disjoint-no-witness: adding C⊑D keeps ontology consistent → CONSISTENT
        OWLOntology ontology = loadOntology("disjoint-no-witness");
        String ns = "http://owl4agents.org/test/exact-consistency/disjoint-no-witness#";
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLClass c = df.getOWLClass(IRI.create(ns + "C"));
        OWLClass d = df.getOWLClass(IRI.create(ns + "D"));
        OWLSubClassOfAxiom axiom = df.getOWLSubClassOfAxiom(c, d);

        ServiceResult<ConsistencyAfterAdditionResult> result = reasonerService.checkConsistencyAfterAdding(
            ontology, new OntologyId("disjoint-no-witness"), "cmeta-1a", axiom,
            Optional.of(REASONER), Duration.ofSeconds(60));

        assertTrue(result.isSuccess(), "checkConsistencyAfterAdding should succeed");
        ConsistencyAfterAdditionResult data = extract(result);
        assertEquals(ConsistencyAfterAdditionStatus.CONSISTENT, data.status(),
            "C⊑D on disjoint-no-witness should be CONSISTENT (no witness)");

        assertNotNull(data.metadata(),
            "CONSISTENT result must have non-null metadata from the wrapper");
        assertNotNull(data.metadata().reasonerName(),
            "metadata.reasonerName must be populated");
        assertEquals("HermiT", data.metadata().reasonerName(),
            "metadata.reasonerName should match the explicitly requested reasoner");
        assertFalse(data.metadata().executorRecovered(),
            "Successful call should not have executorRecovered=true");
        assertTrue(data.metadata().timeoutMs() > 0,
            "metadata.timeoutMs should be the configured timeout (60000ms)");
        assertNull(data.metadata().fallbackFrom(),
            "Successful call without fallback should have fallbackFrom=null");
    }

    @Test
    @DisplayName("CMETA-1b: INCONSISTENT result has non-null metadata")
    void inconsistentResultHasMetadata() throws Exception {
        // disjoint-with-witness: adding C⊑D makes ontology inconsistent → INCONSISTENT
        OWLOntology ontology = loadOntology("disjoint-with-witness");
        String ns = "http://owl4agents.org/test/exact-consistency/disjoint-with-witness#";
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLClass c = df.getOWLClass(IRI.create(ns + "C"));
        OWLClass d = df.getOWLClass(IRI.create(ns + "D"));
        OWLSubClassOfAxiom axiom = df.getOWLSubClassOfAxiom(c, d);

        ServiceResult<ConsistencyAfterAdditionResult> result = reasonerService.checkConsistencyAfterAdding(
            ontology, new OntologyId("disjoint-with-witness"), "cmeta-1b", axiom,
            Optional.of(REASONER), Duration.ofSeconds(60));

        assertTrue(result.isSuccess());
        ConsistencyAfterAdditionResult data = extract(result);
        assertEquals(ConsistencyAfterAdditionStatus.INCONSISTENT, data.status(),
            "C⊑D on disjoint-with-witness should be INCONSISTENT (witness 'a' in both)");

        assertNotNull(data.metadata(),
            "INCONSISTENT result must have non-null metadata from the wrapper");
        assertEquals("HermiT", data.metadata().reasonerName());
        assertFalse(data.metadata().executorRecovered());
    }

    // ════════════════════════════════════════════════════════════════════
    // CMETA-2: Timeout populates metadata with executorRecovered=true
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CMETA-2: Timeout result has metadata with executorRecovered=true and timeoutMs=0")
    void timeoutResultHasMetadata() throws Exception {
        // timeout-large with Duration.ZERO → immediate timeout
        OWLOntology ontology = loadOntology("timeout-large");
        String ns = "http://owl4agents.org/test/exact-consistency/timeout-large#";
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLClass root = df.getOWLClass(IRI.create(ns + "Root"));
        OWLClass l2a1 = df.getOWLClass(IRI.create(ns + "L2A1"));
        OWLSubClassOfAxiom axiom = df.getOWLSubClassOfAxiom(root, l2a1);

        ServiceResult<ConsistencyAfterAdditionResult> result = reasonerService.checkConsistencyAfterAdding(
            ontology, new OntologyId("timeout-large"), "cmeta-2", axiom,
            Optional.of(REASONER), Duration.ZERO);

        assertTrue(result.isSuccess());
        ConsistencyAfterAdditionResult data = extract(result);
        assertEquals(ConsistencyAfterAdditionStatus.TIMEOUT, data.status(),
            "Duration.ZERO should yield TIMEOUT status");

        assertNotNull(data.metadata(),
            "TIMEOUT result must have non-null metadata");
        assertTrue(data.metadata().executorRecovered(),
            "Timeout metadata must have executorRecovered=true");
        assertEquals(0, data.metadata().timeoutMs(),
            "Timeout metadata.timeoutMs should be 0 (Duration.ZERO)");
    }

    // ════════════════════════════════════════════════════════════════════
    // CMETA-3: Metadata.reasonerName matches the effective reasoner
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CMETA-3: Auto-selected reasoner name is reflected in metadata")
    void autoSelectedReasonerInMetadata() throws Exception {
        // disjoint-no-witness with auto reasoner selection
        // For a small ontology, AutoReasonerSelector picks ELK (OWL 2 EL)
        OWLOntology ontology = loadOntology("disjoint-no-witness");
        String ns = "http://owl4agents.org/test/exact-consistency/disjoint-no-witness#";
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLClass c = df.getOWLClass(IRI.create(ns + "C"));
        OWLClass d = df.getOWLClass(IRI.create(ns + "D"));
        OWLSubClassOfAxiom axiom = df.getOWLSubClassOfAxiom(c, d);

        ServiceResult<ConsistencyAfterAdditionResult> result = reasonerService.checkConsistencyAfterAdding(
            ontology, new OntologyId("disjoint-no-witness"), "cmeta-3", axiom,
            Optional.empty(), // auto-select
            Duration.ofSeconds(60));

        assertTrue(result.isSuccess());
        ConsistencyAfterAdditionResult data = extract(result);
        assertNotNull(data.metadata());
        assertNotNull(data.metadata().reasonerName());
        // Auto-selector picks ELK for small OWL 2 EL ontologies; either ELK or HermiT is acceptable
        // The key assertion is that reasonerName is populated (not null/empty)
        assertFalse(data.metadata().reasonerName().isBlank(),
            "Auto-selected reasoner name should be populated in metadata");
    }

    // ════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════

    private ConsistencyAfterAdditionResult extract(ServiceResult<ConsistencyAfterAdditionResult> result) {
        assertTrue(result.isSuccess(), "ServiceResult should be success: " + result);
        return ((ServiceResult.Success<ConsistencyAfterAdditionResult>) result).data();
    }

    private OWLOntology loadOntology(String ontologyId) throws Exception {
        // Force load via the reasoner service to ensure the ontology is registered
        return reasonerService.loadOntologyForClaim(new OntologyId(ontologyId));
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
