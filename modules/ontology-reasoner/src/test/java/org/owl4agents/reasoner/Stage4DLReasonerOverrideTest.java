package org.owl4agents.reasoner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.ConsistencyAfterAdditionResult;
import org.owl4agents.core.model.ConsistencyAfterAdditionStatus;
import org.owl4agents.core.model.EntailmentResult;
import org.owl4agents.storage.CatalogStore;
import org.owl4agents.storage.HomeDirectoryResolver;
import org.owl4agents.storage.WorkspaceInitializer;
import org.owl4agents.core.WorkspaceId;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.8 task 7.5: Unit tests for Stage 4 DL reasoner override (D1).
 *
 * <p>Verifies that {@link ReasonerServiceImpl#checkConsistencyAfterAdding}
 * overrides ELK to a full DL profile reasoner (HermiT for small ontologies,
 * Openllet for large ontologies >20K classes) for Stage 4 consistency checking.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>ELK on small ontology (&lt;20K classes) → override to HermiT</li>
 *   <li>ELK on "large" ontology (&gt;20K classes) → override to Openllet</li>
 *   <li>HermiT unchanged (no override)</li>
 *   <li>auto on small DL ontology → auto selects HermiT → no override needed</li>
 *   <li>Cache key uses DL reasoner name (second call reuses cached session)</li>
 *   <li>D1 override + DL reasoner timeout → REASONER_TIMEOUT (no ELK fallback)</li>
 *   <li>Stage 3 (entailment) still uses ELK — D1 override is Stage 4 only</li>
 * </ol>
 *
 * <p>These tests use small synthetic ontologies (not real HPO/Mondo).
 */
@DisplayName("v0.8.8 7.5: Stage 4 DL reasoner override (D1)")
class Stage4DLReasonerOverrideTest {

    private static final String TEST_NS = "http://owl4agents.org/test/stage4-override#";

    @TempDir
    Path tempDir;

    private HomeDirectoryResolver homeResolver;
    private CatalogStore catalogStore;
    private ReasonerServiceImpl reasonerService;

    @BeforeEach
    void setUp() {
        homeResolver = new HomeDirectoryResolver(tempDir);
        catalogStore = new CatalogStore(homeResolver);
        WorkspaceInitializer initializer = new WorkspaceInitializer(homeResolver);
        initializer.initializeIdempotent(WorkspaceId.DEFAULT);
        String basePath = homeResolver.resolveHomeDirectory().resolve("workspaces").toString();
        reasonerService = new ReasonerServiceImpl(catalogStore, basePath);
    }

    /**
     * Build a small ontology with disjoint classes C and D.
     * classCount < 20K → D1 override should select HermiT.
     */
    private OWLOntology buildSmallDisjointOntology() throws Exception {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = mgr.getOWLDataFactory();
        OWLOntology ont = mgr.createOntology(IRI.create(TEST_NS + "small"));
        OWLClass c = df.getOWLClass(IRI.create(TEST_NS + "C"));
        OWLClass d = df.getOWLClass(IRI.create(TEST_NS + "D"));
        ont.addAxiom(df.getOWLDeclarationAxiom(c));
        ont.addAxiom(df.getOWLDeclarationAxiom(d));
        ont.addAxiom(df.getOWLDisjointClassesAxiom(c, d));
        return ont;
    }

    /**
     * Build a synthetic ontology with >20K classes (just declarations).
     * classCount > 20K → D1 override should select Openllet.
     */
    private OWLOntology buildLargeOntology() throws Exception {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = mgr.getOWLDataFactory();
        OWLOntology ont = mgr.createOntology(IRI.create(TEST_NS + "large"));
        // Generate 20_001 classes — just above the LARGE_ONTOLOGY_CLASS_THRESHOLD
        for (int i = 0; i < 20_001; i++) {
            OWLClass cls = df.getOWLClass(IRI.create(TEST_NS + "Class" + i));
            ont.addAxiom(df.getOWLDeclarationAxiom(cls));
        }
        return ont;
    }

    @Test
    @DisplayName("ELK on small ontology (<20K classes) → override to HermiT")
    void elkOnSmallOntologyOverridesToHermiT() throws Exception {
        OWLOntology ont = buildSmallDisjointOntology();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass c = df.getOWLClass(IRI.create(TEST_NS + "C"));
        OWLClass d = df.getOWLClass(IRI.create(TEST_NS + "D"));
        // Claim axiom: SubClassOf(C, D) — adding this makes C unsatisfiable
        OWLSubClassOfAxiom claimAxiom = df.getOWLSubClassOfAxiom(c, d);

        ServiceResult<ConsistencyAfterAdditionResult> result = reasonerService.checkConsistencyAfterAdding(
            ont, new OntologyId("small-override-test"), "claim-1",
            claimAxiom, Optional.of("elk"), Duration.ofSeconds(60));

        assertTrue(result.isSuccess(), "checkConsistencyAfterAdding should succeed");
        ConsistencyAfterAdditionResult data =
            ((ServiceResult.Success<ConsistencyAfterAdditionResult>) result).data();
        assertEquals("HermiT", data.reasonerName(),
            "Stage 4 should override ELK to HermiT for small ontology (classCount < 20K)");
    }

    @Test
    @DisplayName("ELK on large ontology (>20K classes) → override to Openllet")
    void elkOnLargeOntologyOverridesToOpenllet() throws Exception {
        OWLOntology ont = buildLargeOntology();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        // Use two classes from the generated set for the claim axiom
        OWLClass c0 = df.getOWLClass(IRI.create(TEST_NS + "Class0"));
        OWLClass c1 = df.getOWLClass(IRI.create(TEST_NS + "Class1"));
        OWLSubClassOfAxiom claimAxiom = df.getOWLSubClassOfAxiom(c0, c1);

        ServiceResult<ConsistencyAfterAdditionResult> result = reasonerService.checkConsistencyAfterAdding(
            ont, new OntologyId("large-override-test"), "claim-2",
            claimAxiom, Optional.of("elk"), Duration.ofSeconds(120));

        assertTrue(result.isSuccess(), "checkConsistencyAfterAdding should succeed");
        ConsistencyAfterAdditionResult data =
            ((ServiceResult.Success<ConsistencyAfterAdditionResult>) result).data();
        assertEquals("Openllet", data.reasonerName(),
            "Stage 4 should override ELK to Openllet for large ontology (classCount > 20K)");
    }

    @Test
    @DisplayName("HermiT unchanged: no override when reasoner=hermit")
    void hermitUnchangedNoOverride() throws Exception {
        OWLOntology ont = buildSmallDisjointOntology();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass c = df.getOWLClass(IRI.create(TEST_NS + "C"));
        OWLClass d = df.getOWLClass(IRI.create(TEST_NS + "D"));
        OWLSubClassOfAxiom claimAxiom = df.getOWLSubClassOfAxiom(c, d);

        ServiceResult<ConsistencyAfterAdditionResult> result = reasonerService.checkConsistencyAfterAdding(
            ont, new OntologyId("hermit-no-override"), "claim-3",
            claimAxiom, Optional.of("HermiT"), Duration.ofSeconds(60));

        assertTrue(result.isSuccess());
        ConsistencyAfterAdditionResult data =
            ((ServiceResult.Success<ConsistencyAfterAdditionResult>) result).data();
        assertEquals("HermiT", data.reasonerName(),
            "HermiT should not be overridden (already a DL profile reasoner)");
    }

    @Test
    @DisplayName("auto on small DL ontology → auto selects HermiT → no override needed")
    void autoOnSmallDlOntologySelectsHermiT() throws Exception {
        // Build a small DL ontology (disjoint classes make it OWL 2 DL, not EL)
        OWLOntology ont = buildSmallDisjointOntology();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass c = df.getOWLClass(IRI.create(TEST_NS + "C"));
        OWLClass d = df.getOWLClass(IRI.create(TEST_NS + "D"));
        OWLSubClassOfAxiom claimAxiom = df.getOWLSubClassOfAxiom(c, d);

        // reasoner=auto (empty Optional) → resolveReasonerName auto-selects
        ServiceResult<ConsistencyAfterAdditionResult> result = reasonerService.checkConsistencyAfterAdding(
            ont, new OntologyId("auto-small-dl"), "claim-4",
            claimAxiom, Optional.empty(), Duration.ofSeconds(60));

        assertTrue(result.isSuccess());
        ConsistencyAfterAdditionResult data =
            ((ServiceResult.Success<ConsistencyAfterAdditionResult>) result).data();
        // Auto-selection on a small DL ontology picks HermiT; D1 override does
        // not trigger (HermiT is already a DL reasoner).
        assertEquals("HermiT", data.reasonerName(),
            "auto on small DL ontology should select HermiT (no D1 override needed)");
    }

    @Test
    @DisplayName("Cache key uses DL reasoner name: second ELK call reuses cached HermiT session")
    void cacheKeyUsesDlReasonerName() throws Exception {
        OWLOntology ont = buildSmallDisjointOntology();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass c = df.getOWLClass(IRI.create(TEST_NS + "C"));
        OWLClass d = df.getOWLClass(IRI.create(TEST_NS + "D"));
        OWLSubClassOfAxiom claimAxiom = df.getOWLSubClassOfAxiom(c, d);

        OntologyId ontId = new OntologyId("cache-key-test");

        // First call: cache miss — creates temporary copy + initializes HermiT
        ServiceResult<ConsistencyAfterAdditionResult> result1 = reasonerService.checkConsistencyAfterAdding(
            ont, ontId, "claim-5a",
            claimAxiom, Optional.of("elk"), Duration.ofSeconds(60));
        assertTrue(result1.isSuccess());
        ConsistencyAfterAdditionResult data1 =
            ((ServiceResult.Success<ConsistencyAfterAdditionResult>) result1).data();
        assertEquals("HermiT", data1.reasonerName(),
            "First call: ELK should be overridden to HermiT");

        // Cache miss on first call: temporaryCopyMs and reasonerInitMs should be > 0
        // (or at least one of them, indicating session creation work was done)
        Long initMs1 = data1.perStageTiming() != null ? data1.perStageTiming().reasonerInitMs() : null;
        assertNotNull(initMs1, "First call should have reasonerInitMs");

        // Second call: cache hit — should reuse the cached HermiT session
        // (cache key is "cache-key-test|HermiT", same as first call after D1 override)
        ServiceResult<ConsistencyAfterAdditionResult> result2 = reasonerService.checkConsistencyAfterAdding(
            ont, ontId, "claim-5b",
            claimAxiom, Optional.of("elk"), Duration.ofSeconds(60));
        assertTrue(result2.isSuccess());
        ConsistencyAfterAdditionResult data2 =
            ((ServiceResult.Success<ConsistencyAfterAdditionResult>) result2).data();
        assertEquals("HermiT", data2.reasonerName(),
            "Second call: ELK should still be overridden to HermiT");

        // Cache hit on second call: reasonerInitMs should be 0 (session reused)
        Long initMs2 = data2.perStageTiming() != null ? data2.perStageTiming().reasonerInitMs() : null;
        assertNotNull(initMs2, "Second call should have reasonerInitMs");
        assertEquals(0L, initMs2,
            "Second call should reuse cached session (reasonerInitMs=0, cache key uses DL reasoner name)");
    }

    @Test
    @DisplayName("D1 override + DL reasoner timeout → REASONER_TIMEOUT (no ELK fallback)")
    void d1OverrideTimeoutReturnsTimeoutNotElkFallback() throws Exception {
        // Spec: "D1 override 后的 DL reasoner 超时 → 返回 REASONER_TIMEOUT, 禁止回退 ELK"
        // (design.md Resolved Open Questions #3)
        //
        // Duration.ZERO triggers an immediate timeout in ReasonerCallWrapper.callInternal
        // (line 130-139) without invoking the supplier. D1 override routes through call()
        // (not callWithElkFallback()), so timeout returns REASONER_TIMEOUT directly.
        // If ELK fallback were accidentally enabled, ELK would return CONSISTENT for
        // disjointness (ELK cannot detect it), masking the timeout — this test guards
        // against that regression.
        OWLOntology ont = buildSmallDisjointOntology();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass c = df.getOWLClass(IRI.create(TEST_NS + "C"));
        OWLClass d = df.getOWLClass(IRI.create(TEST_NS + "D"));
        OWLSubClassOfAxiom claimAxiom = df.getOWLSubClassOfAxiom(c, d);

        ServiceResult<ConsistencyAfterAdditionResult> result = reasonerService.checkConsistencyAfterAdding(
            ont, new OntologyId("d1-timeout-test"), "claim-timeout",
            claimAxiom, Optional.of("elk"), Duration.ZERO);

        assertTrue(result.isSuccess(),
            "checkConsistencyAfterAdding should return success wrapper (timeout mapped to result status)");
        ConsistencyAfterAdditionResult data =
            ((ServiceResult.Success<ConsistencyAfterAdditionResult>) result).data();

        // D1 override should have switched ELK → HermiT before the call;
        // the recorded reasonerName reflects the effective reasoner (HermiT).
        assertEquals("HermiT", data.reasonerName(),
            "D1 override should select HermiT even on timeout (reasoner name recorded before call)");

        // Critical assertion: status must be TIMEOUT, not CONSISTENT.
        // If ELK fallback had occurred, ELK would return CONSISTENT (cannot detect
        // disjointness), producing CONSISTENT status instead of TIMEOUT.
        assertEquals(ConsistencyAfterAdditionStatus.TIMEOUT, data.status(),
            "D1 override + timeout must return TIMEOUT status — ELK fallback is forbidden");
        assertNotEquals(ConsistencyAfterAdditionStatus.CONSISTENT, data.status(),
            "CONSISTENT status would indicate ELK fallback occurred (forbidden by D1)");
    }

    @Test
    @DisplayName("Stage 3 (entailment) still uses ELK — D1 override is Stage 4 only")
    void stage3StillUsesElkWhileStage4UsesHermiT() throws Exception {
        // Spec: "Stage 3 的 entailment check 仍用 claim 指定的 reasoner (ELK 快速子类推理)"
        // (design.md D1 decision). D1 override must NOT leak into Stage 3.
        //
        // This test calls both checkAxiomEntailment (Stage 3) and
        // checkConsistencyAfterAdding (Stage 4) with reasoner=elk on the same
        // ontology, and verifies Stage 3 records reasoner=ELK while Stage 4
        // records reasoner=HermiT (D1 override).
        OWLOntology ont = buildSmallDisjointOntology();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass c = df.getOWLClass(IRI.create(TEST_NS + "C"));
        OWLClass d = df.getOWLClass(IRI.create(TEST_NS + "D"));
        OntologyId ontId = new OntologyId("stage3-stage4-parity");

        // Stage 3: checkAxiomEntailment with reasoner=elk.
        // Use SubClassOf(C, D) as the entailment query — this axiom is NOT asserted
        // in the ontology (only DisjointClasses(C, D) is), so the asserted fast-path
        // is skipped and the reasoner is invoked. ELK supports SubClassOf entailment.
        OWLSubClassOfAxiom entailmentQuery = df.getOWLSubClassOfAxiom(c, d);
        ServiceResult<EntailmentResult> stage3Result = reasonerService.checkAxiomEntailment(
            ont, ontId, entailmentQuery, Optional.of("elk"));

        assertTrue(stage3Result.isSuccess(), "Stage 3 entailment check should succeed");
        EntailmentResult entailment = ((ServiceResult.Success<EntailmentResult>) stage3Result).data();
        assertTrue("ELK".equalsIgnoreCase(entailment.reasonerName()),
            "Stage 3 should use ELK (claim-specified reasoner) — D1 override must NOT affect Stage 3. "
                + "Actual reasonerName: " + entailment.reasonerName());

        // Stage 4: checkConsistencyAfterAdding with reasoner=elk → D1 override to HermiT.
        OWLSubClassOfAxiom claimAxiom = df.getOWLSubClassOfAxiom(c, d);
        ServiceResult<ConsistencyAfterAdditionResult> stage4Result = reasonerService.checkConsistencyAfterAdding(
            ont, ontId, "claim-stage4", claimAxiom, Optional.of("elk"), Duration.ofSeconds(60));

        assertTrue(stage4Result.isSuccess(), "Stage 4 consistency check should succeed");
        ConsistencyAfterAdditionResult consistency =
            ((ServiceResult.Success<ConsistencyAfterAdditionResult>) stage4Result).data();
        assertEquals("HermiT", consistency.reasonerName(),
            "Stage 4 should override ELK to HermiT (D1 override applies to Stage 4 only)");

        // Parity assertion: Stage 3 used ELK (any case), Stage 4 used HermiT — different
        // reasoners for the same claim-specified reasoner=elk. This is the core D1 invariant.
        assertNotEquals(entailment.reasonerName(), consistency.reasonerName(),
            "Stage 3 and Stage 4 must use different reasoners when claim specifies elk "
                + "(Stage 3=elk, Stage 4=HermiT via D1 override)");
    }
}
