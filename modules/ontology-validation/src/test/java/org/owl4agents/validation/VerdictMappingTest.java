package org.owl4agents.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.model.ClaimVerificationResult;
import org.owl4agents.core.model.EvidenceItem;
import org.owl4agents.core.model.Verdict;
import org.owl4agents.owlapi.EntitySignatureCacheManager;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.owlapi.OntologyImporter;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.ReasonerServiceImpl;
import org.owl4agents.storage.CatalogStore;
import org.owl4agents.storage.HomeDirectoryResolver;
import org.owl4agents.storage.WorkspaceInitializer;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.8 task 7.7: Unit tests for 3-state verdict mapping (D3).
 *
 * <p>Verifies the verdict mapping from Stage 4 results:
 * <ul>
 *   <li>INCONSISTENT → CONTRADICTED (exact inconsistency path)</li>
 *   <li>CONSISTENT + unsatisfiable → CONTRADICTED (satisfiability check path)</li>
 *   <li>CONSISTENT + satisfiable → UNKNOWN</li>
 *   <li>Evidence includes SATISFIABILITY_CHECK when unsatisfiable</li>
 *   <li>Evidence does NOT include SATISFIABILITY_CHECK when all satisfiable</li>
 * </ul>
 */
@DisplayName("v0.8.8 7.7: Verdict mapping (3-state)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VerdictMappingTest {

    @TempDir
    Path tempDir;

    private HomeDirectoryResolver homeResolver;
    private CatalogStore catalogStore;
    private OntologyImporter importer;
    private ReasonerServiceImpl reasonerService;
    private ClaimVerificationService verificationService;

    @BeforeEach
    void setUp() throws Exception {
        homeResolver = new HomeDirectoryResolver(tempDir);
        catalogStore = new CatalogStore(homeResolver);
        importer = new OntologyImporter(homeResolver, catalogStore);
        System.setProperty("OWL4AGENTS_HOME", tempDir.toString());
        WorkspaceInitializer initializer = new WorkspaceInitializer(homeResolver);
        initializer.initializeIdempotent(WorkspaceId.DEFAULT);

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
    }

    @FunctionalInterface
    interface OntologyBuilder {
        void build(OWLOntology ont, OWLDataFactory df, String ns) throws Exception;
    }

    private void importInMemoryOntology(String ontId, OntologyBuilder builder) throws Exception {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = mgr.getOWLDataFactory();
        String ns = "http://owl4agents.org/test/verdict-map/" + ontId + "#";
        OWLOntology ont = mgr.createOntology(IRI.create(ns));
        builder.build(ont, factory, ns);

        Path owlFile = tempDir.resolve(ontId + ".owl");
        mgr.saveOntology(ont, IRI.create(owlFile.toUri()));

        ServiceResult<?> result = importer.importOntology(
            new OntologyId(ontId), owlFile, WorkspaceId.DEFAULT);
        assertTrue(result.isSuccess(), "Import failed for " + ontId + ": " + result);
    }

    private Claim subclassClaim(String id, String subIri, String objIri, String ontId) {
        return new Claim(id, ClaimType.SUBCLASS, ontId,
            new ClaimEntity("class", subIri),
            "http://www.w3.org/2000/01/rdf-schema#subClassOf",
            new ClaimEntity("class", objIri),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private ClaimVerificationResult extract(ServiceResult<ClaimVerificationResult> result) {
        assertTrue(result.isSuccess(), "ServiceResult should be success: " + result);
        return ((ServiceResult.Success<ClaimVerificationResult>) result).data();
    }

    private boolean hasSatisfiabilityCheckEvidence(ClaimVerificationResult result) {
        return result.evidence().stream()
            .anyMatch(e -> "satisfiability-check".equals(e.source()));
    }

    private boolean hasConsistencyReportEvidence(ClaimVerificationResult result) {
        return result.evidence().stream()
            .anyMatch(e -> "exact-consistency-check".equals(e.source()));
    }

    // ════════════════════════════════════════════════════════════════════
    // Scenario 1: INCONSISTENT → CONTRADICTED (exact inconsistency path)
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("1. INCONSISTENT → CONTRADICTED (witness individual makes O∪{α} inconsistent)")
    void inconsistentYieldsContradicted() throws Exception {
        String ontId = "vm-inconsistent";
        importInMemoryOntology(ontId, (ont, df, ns) -> {
            OWLClass c = df.getOWLClass(IRI.create(ns + "C"));
            OWLClass d = df.getOWLClass(IRI.create(ns + "D"));
            OWLNamedIndividual a = df.getOWLNamedIndividual(IRI.create(ns + "a"));
            ont.addAxiom(df.getOWLDeclarationAxiom(c));
            ont.addAxiom(df.getOWLDeclarationAxiom(d));
            ont.addAxiom(df.getOWLDeclarationAxiom(a));
            // DisjointClasses(C, D) + witness individual a of type C
            // Adding SubClassOf(C, D) makes a both C and D → INCONSISTENT
            ont.addAxiom(df.getOWLDisjointClassesAxiom(c, d));
            ont.addAxiom(df.getOWLClassAssertionAxiom(c, a));
        });

        String ns = "http://owl4agents.org/test/verdict-map/" + ontId + "#";
        Claim claim = subclassClaim("vm-1", ns + "C", ns + "D", ontId);
        ClaimVerificationResult result = extract(verificationService.verify(claim));

        assertEquals(Verdict.CONTRADICTED, result.verdict(),
            "Adding SubClassOf(C, D) with witness individual a∈C and Disjoint(C,D) → INCONSISTENT → CONTRADICTED");
        // The inconsistency path produces exact-consistency-check evidence
        assertTrue(hasConsistencyReportEvidence(result),
            "Evidence should include an exact-consistency-check entry for the INCONSISTENT path");
    }

    // ════════════════════════════════════════════════════════════════════
    // Scenario 2: CONSISTENT + unsatisfiable → CONTRADICTED (satisfiability path)
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("2. CONSISTENT + unsatisfiable → CONTRADICTED (satisfiability check path)")
    void consistentUnsatisfiableYieldsContradicted() throws Exception {
        String ontId = "vm-consistent-unsat";
        importInMemoryOntology(ontId, (ont, df, ns) -> {
            OWLClass c = df.getOWLClass(IRI.create(ns + "C"));
            OWLClass d = df.getOWLClass(IRI.create(ns + "D"));
            ont.addAxiom(df.getOWLDeclarationAxiom(c));
            ont.addAxiom(df.getOWLDeclarationAxiom(d));
            // DisjointClasses(C, D) — no witness individual
            // Adding SubClassOf(C, D) makes C unsatisfiable, but O∪{α} is still CONSISTENT
            ont.addAxiom(df.getOWLDisjointClassesAxiom(c, d));
        });

        String ns = "http://owl4agents.org/test/verdict-map/" + ontId + "#";
        Claim claim = subclassClaim("vm-2", ns + "C", ns + "D", ontId);
        ClaimVerificationResult result = extract(verificationService.verify(claim));

        assertEquals(Verdict.CONTRADICTED, result.verdict(),
            "Adding SubClassOf(C, D) with Disjoint(C,D) and no witness → CONSISTENT + C unsatisfiable → CONTRADICTED");
        assertTrue(hasSatisfiabilityCheckEvidence(result),
            "Evidence should include a SATISFIABILITY_CHECK entry for the satisfiability path");
    }

    // ════════════════════════════════════════════════════════════════════
    // Scenario 3: CONSISTENT + satisfiable → UNKNOWN
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("3. CONSISTENT + satisfiable → UNKNOWN")
    void consistentSatisfiableYieldsUnknown() throws Exception {
        String ontId = "vm-consistent-sat";
        importInMemoryOntology(ontId, (ont, df, ns) -> {
            OWLClass c = df.getOWLClass(IRI.create(ns + "C"));
            OWLClass d = df.getOWLClass(IRI.create(ns + "D"));
            OWLClass e = df.getOWLClass(IRI.create(ns + "E"));
            ont.addAxiom(df.getOWLDeclarationAxiom(c));
            ont.addAxiom(df.getOWLDeclarationAxiom(d));
            ont.addAxiom(df.getOWLDeclarationAxiom(e));
            // D ⊑ C — simple hierarchy, no disjointness
            ont.addAxiom(df.getOWLSubClassOfAxiom(d, c));
        });

        String ns = "http://owl4agents.org/test/verdict-map/" + ontId + "#";
        // SubClassOf(E, C) — not entailed, consistent, all satisfiable → UNKNOWN
        Claim claim = subclassClaim("vm-3", ns + "E", ns + "C", ontId);
        ClaimVerificationResult result = extract(verificationService.verify(claim));

        assertEquals(Verdict.UNKNOWN, result.verdict(),
            "SubClassOf(E, C) with no disjointness → CONSISTENT + all satisfiable → UNKNOWN");
        assertFalse(hasSatisfiabilityCheckEvidence(result),
            "Evidence should NOT include SATISFIABILITY_CHECK when all satisfiable");
    }

    // ════════════════════════════════════════════════════════════════════
    // Scenario 4: Evidence includes SATISFIABILITY_CHECK when unsatisfiable
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("4. Evidence includes SATISFIABILITY_CHECK when unsatisfiable")
    void evidenceIncludesSatisfiabilityCheckWhenUnsatisfiable() throws Exception {
        String ontId = "vm-evidence-unsat";
        importInMemoryOntology(ontId, (ont, df, ns) -> {
            OWLClass c = df.getOWLClass(IRI.create(ns + "C"));
            OWLClass d = df.getOWLClass(IRI.create(ns + "D"));
            ont.addAxiom(df.getOWLDeclarationAxiom(c));
            ont.addAxiom(df.getOWLDeclarationAxiom(d));
            ont.addAxiom(df.getOWLDisjointClassesAxiom(c, d));
        });

        String ns = "http://owl4agents.org/test/verdict-map/" + ontId + "#";
        Claim claim = subclassClaim("vm-4", ns + "C", ns + "D", ontId);
        ClaimVerificationResult result = extract(verificationService.verify(claim));

        assertEquals(Verdict.CONTRADICTED, result.verdict());

        // Find the SATISFIABILITY_CHECK evidence item and verify its structure
        EvidenceItem satEvidence = result.evidence().stream()
            .filter(e -> "satisfiability-check".equals(e.source()))
            .findFirst()
            .orElse(null);
        assertNotNull(satEvidence, "Evidence must contain a SATISFIABILITY_CHECK item");
        assertEquals(EvidenceItem.ROLE_COUNTER, satEvidence.role(),
            "SATISFIABILITY_CHECK evidence role should be 'counter'");
        assertEquals(org.owl4agents.core.model.EvidenceKind.REASONING_REPORT, satEvidence.kind(),
            "SATISFIABILITY_CHECK evidence kind should be REASONING_REPORT");
        assertTrue(satEvidence.entities() != null && !satEvidence.entities().isEmpty(),
            "SATISFIABILITY_CHECK evidence should list the unsatisfiable class IRI(s)");
        assertTrue(satEvidence.entities().contains(ns + "C"),
            "SATISFIABILITY_CHECK entities should include the unsatisfiable class C");
    }

    // ════════════════════════════════════════════════════════════════════
    // Scenario 5: Evidence does NOT include SATISFIABILITY_CHECK when all satisfiable
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("5. Evidence does NOT include SATISFIABILITY_CHECK when all satisfiable")
    void evidenceDoesNotIncludeSatisfiabilityCheckWhenAllSatisfiable() throws Exception {
        String ontId = "vm-evidence-sat";
        importInMemoryOntology(ontId, (ont, df, ns) -> {
            OWLClass c = df.getOWLClass(IRI.create(ns + "C"));
            OWLClass d = df.getOWLClass(IRI.create(ns + "D"));
            OWLClass e = df.getOWLClass(IRI.create(ns + "E"));
            ont.addAxiom(df.getOWLDeclarationAxiom(c));
            ont.addAxiom(df.getOWLDeclarationAxiom(d));
            ont.addAxiom(df.getOWLDeclarationAxiom(e));
            ont.addAxiom(df.getOWLSubClassOfAxiom(d, c));
        });

        String ns = "http://owl4agents.org/test/verdict-map/" + ontId + "#";
        Claim claim = subclassClaim("vm-5", ns + "E", ns + "C", ontId);
        ClaimVerificationResult result = extract(verificationService.verify(claim));

        assertEquals(Verdict.UNKNOWN, result.verdict());

        // Verify NO evidence item has source="satisfiability-check"
        boolean hasSatCheck = result.evidence().stream()
            .anyMatch(e -> "satisfiability-check".equals(e.source()));
        assertFalse(hasSatCheck,
            "Evidence should NOT contain any SATISFIABILITY_CHECK item when all classes are satisfiable");

        // The UNKNOWN path should still have the no-entailment evidence
        boolean hasNoEntailment = result.evidence().stream()
            .anyMatch(e -> e.evidenceId() != null && e.evidenceId().contains("no-entailment"));
        assertTrue(hasNoEntailment,
            "UNKNOWN evidence should include a no-entailment entry");
    }
}
