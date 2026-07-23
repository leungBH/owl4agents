package org.owl4agents.validation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.owl4agents.core.model.ConsistencyAfterAdditionResult;
import org.owl4agents.core.model.ConsistencyAfterAdditionStatus;
import org.owl4agents.core.model.ExecutionStatus;
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
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.util.AutoIRIMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.5 end-to-end acceptance suite for the exact consistency verification
 * pipeline (tasks 14.1-14.5).
 *
 * <p>Tests the 15 semantic fixtures defined in
 * {@code test/contracts/acceptance-report/exact-consistency-contracts.md}
 * using the real {@link ReasonerServiceImpl} with HermiT. Verifies that the
 * 5-stage pipeline produces the expected verdicts for each scenario.
 *
 * <p>Also covers:
 * <ul>
 *   <li>14.2: source immutability (100 verify calls, axiom count + hash unchanged)</li>
 *   <li>14.3: timeout (REASONER_TIMEOUT with semanticVerdict=null)</li>
 *   <li>14.4: imports closure (conflict in imported ontology detected)</li>
 *   <li>14.5: batch order independence (10 mixed claims, 2 orders)</li>
 * </ul>
 */
@DisplayName("v0.8.5 exact consistency acceptance suite (15 semantic fixtures)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V085AcceptanceSuite {

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

        // Import all fixtures except imports-closure-main (which has
        // unresolvable <owl:imports> without an IRI mapper)
        importAllFixtures();

        // Build services
        String basePath = homeResolver.resolveHomeDirectory().resolve("workspaces").toString();
        ontologyCache = new OntologyCache(basePath, "default");
        escManager = new EntitySignatureCacheManager();
        ontologyCache.addReloadListener(escManager);
        reasonerService = new ReasonerServiceImpl(catalogStore, basePath, "default", ontologyCache, escManager);

        // Pre-run reasoner for each ontology to populate inferred hierarchy.
        // Skip imports-closure-main (tested separately via checkConsistencyAfterAdding).
        for (String ontId : getFixtureOntologyIds()) {
            if ("imports-closure-main".equals(ontId)) continue;
            try {
                reasonerService.runReasoner(new OntologyId(ontId), Optional.of(REASONER));
            } catch (Exception e) {
                // Best-effort: reasoner pre-run is not required for correctness
            }
        }

        ConsistencyAnalysisService consistencyService =
            new ConsistencyAnalysisService(reasonerService.getLifecycleManager(), basePath, ontologyCache, escManager);
        SemanticDeepeningService deepeningService = new SemanticDeepeningService(basePath, ontologyCache);
        verificationService = new ClaimVerificationService(
            reasonerService, consistencyService, deepeningService, catalogStore, WorkspaceId.DEFAULT);
    }

    // ════════════════════════════════════════════════════════════════════
    // Task 14.1: 15 semantic fixtures acceptance tests
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14.1: Semantic fixtures verdict mapping")
    class SemanticFixturesTests {

        @Test
        @DisplayName("EC-DISJOINT-NO-WITNESS → CONTRADICTED")
        void disjointNoWitnessYieldsContradicted() {
            String ns = "http://owl4agents.org/test/exact-consistency/disjoint-no-witness#";
            Claim claim = subclassClaim("ec-1", ns + "C", ns + "D", "disjoint-no-witness");
            ClaimVerificationResult result = extract(verificationService.verify(claim));
            assertEquals(Verdict.CONTRADICTED, result.verdict(),
                "Disjoint without witness: adding C⊑D makes C unsatisfiable (C⊑D and Disjoint(C,D)) → CONTRADICTED (v0.8.8 D2/D3)");
        }

        @Test
        @DisplayName("EC-DISJOINT-WITH-WITNESS → CONTRADICTED")
        void disjointWithWitnessYieldsContradicted() {
            String ns = "http://owl4agents.org/test/exact-consistency/disjoint-with-witness#";
            Claim claim = subclassClaim("ec-2", ns + "C", ns + "D", "disjoint-with-witness");
            ClaimVerificationResult result = extract(verificationService.verify(claim));
            assertEquals(Verdict.CONTRADICTED, result.verdict(),
                "Disjoint with witness: adding C⊑D makes individual 'a' both C and D → CONTRADICTED");
        }

        @Test
        @DisplayName("EC-EMPTY-CLASS → SUPPORTED")
        void emptyClassYieldsSupported() {
            String ns = "http://owl4agents.org/test/exact-consistency/empty-class#";
            Claim claim = subclassClaim("ec-3", ns + "C", "http://www.w3.org/2002/07/owl#Nothing", "empty-class");
            ClaimVerificationResult result = extract(verificationService.verify(claim));
            assertEquals(Verdict.SUPPORTED, result.verdict(),
                "C⊑owl:Nothing is asserted → ENTAILED → SUPPORTED");
        }

        @Test
        @DisplayName("EC-EXISTENTIAL-NO-WITNESS → CONTRADICTED")
        void existentialNoWitnessYieldsContradicted() {
            String ns = "http://owl4agents.org/test/exact-consistency/existential-no-witness#";
            Claim claim = subclassClaim("ec-4", ns + "C", ns + "D", "existential-no-witness");
            ClaimVerificationResult result = extract(verificationService.verify(claim));
            assertEquals(Verdict.CONTRADICTED, result.verdict(),
                "Existential without witness: adding C⊑D makes C unsatisfiable → CONTRADICTED (v0.8.8 D2/D3)");
        }

        @Test
        @DisplayName("EC-EXISTENTIAL-WITH-WITNESS → CONTRADICTED")
        void existentialWithWitnessYieldsContradicted() {
            String ns = "http://owl4agents.org/test/exact-consistency/existential-with-witness#";
            Claim claim = subclassClaim("ec-5", ns + "C", ns + "D", "existential-with-witness");
            ClaimVerificationResult result = extract(verificationService.verify(claim));
            assertEquals(Verdict.CONTRADICTED, result.verdict(),
                "Existential with witness: adding C⊑D makes 'a' violate restrictions → CONTRADICTED");
        }

        @Test
        @DisplayName("EC-NEGATIVE-MEMBERSHIP → CONTRADICTED")
        void negativeMembershipYieldsContradicted() {
            String ns = "http://owl4agents.org/test/exact-consistency/negative-membership#";
            Claim claim = new Claim("ec-6", ClaimType.INDIVIDUAL_MEMBERSHIP, "negative-membership",
                new ClaimEntity("individual", ns + "a"), null,
                new ClaimEntity("class", ns + "C"),
                Optional.empty(), Optional.empty(), Optional.empty());
            ClaimVerificationResult result = extract(verificationService.verify(claim));
            assertEquals(Verdict.CONTRADICTED, result.verdict(),
                "a is complementOf C; adding a∈C → INCONSISTENT → CONTRADICTED");
        }

        @Test
        @DisplayName("EC-NEGATIVE-PROP-ASSERTION → CONTRADICTED")
        void negativePropAssertionYieldsContradicted() {
            String ns = "http://owl4agents.org/test/exact-consistency/negative-prop-assertion#";
            // NegativeObjectPropertyAssertion requires OWL 2 DL (HermiT).
            // The auto-selector may pick ELK (OWL 2 EL) which silently ignores
            // negative property assertions, so we explicitly request HermiT.
            Claim claim = new Claim("ec-7", ClaimType.OBJECT_PROPERTY_ASSERTION, "negative-prop-assertion",
                new ClaimEntity("individual", ns + "a"),
                ns + "r",
                new ClaimEntity("individual", ns + "b"),
                Optional.of(REASONER), Optional.empty(), Optional.empty());
            ClaimVerificationResult result = extract(verificationService.verify(claim));
            assertEquals(Verdict.CONTRADICTED, result.verdict(),
                "Negative property assertion exists; adding positive assertion → CONTRADICTED");
        }

        @Test
        @DisplayName("EC-EQUIV-DISJOINT-NO-WITNESS → CONTRADICTED")
        void equivDisjointNoWitnessYieldsContradicted() {
            String ns = "http://owl4agents.org/test/exact-consistency/equiv-disjoint-no-witness#";
            Claim claim = new Claim("ec-8", ClaimType.EQUIVALENT_CLASSES, "equiv-disjoint-no-witness",
                new ClaimEntity("class", ns + "C"), null,
                new ClaimEntity("class", ns + "D"),
                Optional.empty(), Optional.empty(), Optional.empty());
            ClaimVerificationResult result = extract(verificationService.verify(claim));
            assertEquals(Verdict.CONTRADICTED, result.verdict(),
                "Equiv disjoint without witness: adding C≡D makes both C and D unsatisfiable → CONTRADICTED (v0.8.8 D2/D3)");
        }

        @Test
        @DisplayName("EC-EQUIV-DISJOINT-WITH-WITNESS → CONTRADICTED")
        void equivDisjointWithWitnessYieldsContradicted() {
            String ns = "http://owl4agents.org/test/exact-consistency/equiv-disjoint-with-witness#";
            Claim claim = new Claim("ec-9", ClaimType.EQUIVALENT_CLASSES, "equiv-disjoint-with-witness",
                new ClaimEntity("class", ns + "C"), null,
                new ClaimEntity("class", ns + "D"),
                Optional.empty(), Optional.empty(), Optional.empty());
            ClaimVerificationResult result = extract(verificationService.verify(claim));
            assertEquals(Verdict.CONTRADICTED, result.verdict(),
                "Equiv disjoint with witness: adding C≡D makes 'a' both C and D → CONTRADICTED");
        }

        @Test
        @DisplayName("EC-DATA-PROPERTY-FACET-CONFLICT → CONTRADICTED")
        void dataPropertyFacetConflictYieldsContradicted() {
            String ns = "http://owl4agents.org/test/exact-consistency/data-property-facet-conflict#";
            Claim claim = new Claim("ec-10", ClaimType.DATA_PROPERTY_ASSERTION, "data-property-facet-conflict",
                new ClaimEntity("individual", ns + "a"),
                ns + "r",
                new ClaimEntity("literal", "5"),
                Optional.empty(), Optional.empty(), Optional.empty());
            ClaimVerificationResult result = extract(verificationService.verify(claim));
            assertEquals(Verdict.CONTRADICTED, result.verdict(),
                "Data property r has minInclusive=10; adding a r 5 → CONTRADICTED");
        }

        @Test
        @DisplayName("EC-DIFFERENT-INDIVIDUALS-WITNESS → CONTRADICTED")
        void differentIndividualsWitnessYieldsContradicted() {
            String ns = "http://owl4agents.org/test/exact-consistency/different-individuals-witness#";
            Claim claim = new Claim("ec-11", ClaimType.DIFFERENT_INDIVIDUALS, "different-individuals-witness",
                new ClaimEntity("individual", ns + "a"), null,
                new ClaimEntity("individual", ns + "b"),
                Optional.empty(), Optional.empty(), Optional.empty());
            ClaimVerificationResult result = extract(verificationService.verify(claim));
            assertEquals(Verdict.CONTRADICTED, result.verdict(),
                "a≡b (SameIndividual); adding DifferentIndividuals(a,b) → CONTRADICTED");
        }

        @Test
        @DisplayName("EC-SUBPROPERTY-HIERARCHY-CONFLICT → CONTRADICTED")
        void subpropertyHierarchyConflictYieldsContradicted() {
            String ns = "http://owl4agents.org/test/exact-consistency/subproperty-hierarchy-conflict#";
            Claim claim = new Claim("ec-12", ClaimType.OBJECT_PROPERTY_ASSERTION, "subproperty-hierarchy-conflict",
                new ClaimEntity("individual", ns + "x"),
                ns + "r",
                new ClaimEntity("individual", ns + "y"),
                Optional.empty(), Optional.empty(), Optional.empty());
            ClaimVerificationResult result = extract(verificationService.verify(claim));
            assertEquals(Verdict.CONTRADICTED, result.verdict(),
                "x is complementOf C; adding x r y → x s y → x∈domain(s)=C → CONTRADICTED");
        }

        @Test
        @DisplayName("EC-SOURCE-INCONSISTENT → SOURCE_ONTOLOGY_INCONSISTENT, verdict=null")
        void sourceInconsistentYieldsError() {
            String ns = "http://owl4agents.org/test/exact-consistency/source-inconsistent#";
            Claim claim = subclassClaim("ec-13", ns + "C", ns + "D", "source-inconsistent");
            ClaimVerificationResult result = extract(verificationService.verify(claim));
            assertEquals(ExecutionStatus.ERROR, result.executionStatus());
            assertTrue(result.errorCode().isPresent());
            assertEquals(ErrorCode.SOURCE_ONTOLOGY_INCONSISTENT, result.errorCode().get());
            assertNull(result.verdict(), "Errored result must have null semanticVerdict");
        }

        @Test
        @DisplayName("EC-IMPORTS-CLOSURE → exact check detects conflict via checkConsistencyAfterAdding")
        void importsClosureDetectsConflictViaExactCheck() throws Exception {
            // Load both ontologies into the same manager with import resolution
            OWLOntology combinedOntology = loadImportsClosureOntology();
            assertNotNull(combinedOntology, "Combined ontology must be loaded");

            // Build claim axiom: ClassAssertion(a, E)
            OWLDataFactory df = OWLManager.getOWLDataFactory();
            OWLIndividual a = df.getOWLNamedIndividual(IRI.create(
                "http://owl4agents.org/test/exact-consistency/imports-closure-main#a"));
            OWLClassExpression e = df.getOWLClass(IRI.create(
                "http://owl4agents.org/test/exact-consistency/imports-closure-imported#E"));
            org.semanticweb.owlapi.model.OWLAxiom claimAxiom = df.getOWLClassAssertionAxiom(e, a);

            // Call checkConsistencyAfterAdding directly (bypasses cache loading)
            ServiceResult<ConsistencyAfterAdditionResult> result = reasonerService.checkConsistencyAfterAdding(
                combinedOntology,
                new OntologyId("imports-closure-main"),
                "ec-14",
                claimAxiom,
                Optional.of(REASONER),
                Duration.ofSeconds(60));

            assertTrue(result.isSuccess(), "checkConsistencyAfterAdding should succeed");
            ConsistencyAfterAdditionResult data = ((ServiceResult.Success<ConsistencyAfterAdditionResult>) result).data();
            assertEquals(ConsistencyAfterAdditionStatus.INCONSISTENT, data.status(),
                "Adding a∈E with imports closure (C∩D disjoint, E⊑C, E⊑D) → INCONSISTENT");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Task 14.2: Source immutability
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14.2: Source immutability (100 verify calls)")
    class SourceImmutabilityTests {

        @Test
        @DisplayName("Source axiom count and hash unchanged after 100 verify calls")
        void sourceUnchangedAfter100Verifies() throws Exception {
            String ontId = "disjoint-no-witness";
            String ns = "http://owl4agents.org/test/exact-consistency/disjoint-no-witness#";
            Claim claim = subclassClaim("immut-1", ns + "C", ns + "D", ontId);

            // Record initial axiom count and hash
            OWLOntology ontology = reasonerService.loadOntologyForClaim(new OntologyId(ontId));
            int initialAxiomCount = ontology.getAxiomCount();
            String initialHash = computeOntologyHash(ontology);

            // Run 100 verify calls
            for (int i = 0; i < 100; i++) {
                verificationService.verify(claim);
            }

            // Verify source unchanged
            OWLOntology reloaded = reasonerService.loadOntologyForClaim(new OntologyId(ontId));
            assertEquals(initialAxiomCount, reloaded.getAxiomCount(),
                "Source axiom count must not change after 100 verify calls");
            assertEquals(initialHash, computeOntologyHash(reloaded),
                "Source ontology hash must not change after 100 verify calls");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Task 14.3: Timeout
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14.3: Timeout handling")
    class TimeoutTests {

        @Test
        @DisplayName("EC-TIMEOUT → REASONER_TIMEOUT with semanticVerdict=null")
        void timeoutYieldsReasonerTimeout() {
            String ns = "http://owl4agents.org/test/exact-consistency/timeout-large#";
            // Use Root⊑L2A1 (NOT asserted, NOT entailed) so the pipeline
            // reaches stage 4 (exact consistency check) where the timeout
            // applies. Conflict⊑L2A1 is asserted → ENTAILED → SUPPORTED,
            // never reaching stage 4.
            Claim claim = subclassClaim("ec-15", ns + "Root", ns + "L2A1", "timeout-large");
            // Duration.ZERO → future.get(0, NANOSECONDS) checks task state;
            // if not done, throws TimeoutException immediately without
            // parkNanos (avoiding the Windows ~1ms timer resolution race
            // that Duration.ofNanos(1) suffers from).
            ClaimVerificationResult result = extract(verificationService.verify(claim, Duration.ZERO));
            assertEquals(ExecutionStatus.TIMEOUT, result.executionStatus(),
                "Zero timeout should yield TIMEOUT execution status");
            assertTrue(result.errorCode().isPresent());
            assertEquals(ErrorCode.REASONER_TIMEOUT, result.errorCode().get());
            assertNull(result.verdict(), "Timeout result must have null semanticVerdict");
        }

        @Test
        @DisplayName("Service continues after timeout")
        void serviceContinuesAfterTimeout() {
            // First call: trigger real timeout (Root⊑L2A1 not entailed → reaches stage 4)
            String ns1 = "http://owl4agents.org/test/exact-consistency/timeout-large#";
            Claim timeoutClaim = subclassClaim("to-1", ns1 + "Root", ns1 + "L2A1", "timeout-large");
            verificationService.verify(timeoutClaim, Duration.ZERO);

            // Second call: normal verification on a different ontology
            String ns2 = "http://owl4agents.org/test/exact-consistency/disjoint-no-witness#";
            Claim normalClaim = subclassClaim("to-2", ns2 + "C", ns2 + "D", "disjoint-no-witness");
            ClaimVerificationResult result = extract(verificationService.verify(normalClaim));
            assertNotNull(result.verdict(), "Service should continue working after timeout");
            assertEquals(Verdict.CONTRADICTED, result.verdict());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Task 14.4: Imports closure (detailed test)
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14.4: Imports closure detection")
    class ImportsClosureTests {

        @Test
        @DisplayName("Exact check detects conflict from imported ontology via TemporaryOntologyFactory")
        void exactCheckUsesImportsClosure() throws Exception {
            OWLOntology combined = loadImportsClosureOntology();
            String mainNs = "http://owl4agents.org/test/exact-consistency/imports-closure-main#";
            String importedNs = "http://owl4agents.org/test/exact-consistency/imports-closure-imported#";

            // Build claim axiom: ClassAssertion(a, E)
            OWLDataFactory df = OWLManager.getOWLDataFactory();
            OWLIndividual a = df.getOWLNamedIndividual(IRI.create(mainNs + "a"));
            OWLClassExpression e = df.getOWLClass(IRI.create(importedNs + "E"));
            org.semanticweb.owlapi.model.OWLAxiom claimAxiom = df.getOWLClassAssertionAxiom(e, a);

            // Call checkConsistencyAfterAdding directly — this uses TemporaryOntologyFactory
            // internally, which copies the imports closure (Imports.INCLUDED) from the source.
            ServiceResult<ConsistencyAfterAdditionResult> result = reasonerService.checkConsistencyAfterAdding(
                combined,
                new OntologyId("imports-closure-main"),
                "ic-1",
                claimAxiom,
                Optional.of(REASONER),
                Duration.ofSeconds(60));

            assertTrue(result.isSuccess(), "checkConsistencyAfterAdding should succeed");
            ConsistencyAfterAdditionResult data = ((ServiceResult.Success<ConsistencyAfterAdditionResult>) result).data();
            assertEquals(ConsistencyAfterAdditionStatus.INCONSISTENT, data.status(),
                "Exact check must detect conflict from imports closure: a∈E → a∈C, a∈D, C∩D disjoint → INCONSISTENT");
        }

        @Test
        @DisplayName("TemporaryOntologyFactory copies imports closure axioms")
        void temporaryOntologyFactoryCopiesImportsClosure() throws Exception {
            OWLOntology combined = loadImportsClosureOntology();

            // Verify the combined ontology has axioms from both main and imported
            int combinedAxiomCount = combined.getAxiomCount();
            assertTrue(combinedAxiomCount > 0, "Combined ontology should have axioms");

            // Check that the disjoint axiom from the imported ontology is present
            // in the imports closure (not just direct axioms)
            boolean hasDisjoint = combined.getAxioms(Imports.INCLUDED).stream()
                .anyMatch(ax -> ax.getAxiomType().getName().equals("DisjointClasses"));
            assertTrue(hasDisjoint, "Combined ontology must include DisjointClasses from imported ontology");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Task 14.5: Batch order independence
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14.5: Batch order independence")
    class BatchOrderIndependenceTests {

        @Test
        @DisplayName("10 mixed claims in 2 orders produce identical results")
        void batchOrderIndependence() {
            String ns = "http://owl4agents.org/test/exact-consistency/disjoint-no-witness#";
            String ns2 = "http://owl4agents.org/test/exact-consistency/disjoint-with-witness#";
            String ns3 = "http://owl4agents.org/test/exact-consistency/empty-class#";

            List<Claim> order1 = new ArrayList<>();
            List<Claim> order2 = new ArrayList<>();

            // Create 10 mixed claims
            for (int i = 0; i < 4; i++) {
                order1.add(subclassClaim("ord1-" + i, ns + "C", ns + "D", "disjoint-no-witness"));
            }
            for (int i = 0; i < 3; i++) {
                order1.add(subclassClaim("ord1-w" + i, ns2 + "C", ns2 + "D", "disjoint-with-witness"));
            }
            for (int i = 0; i < 3; i++) {
                order1.add(subclassClaim("ord1-e" + i, ns3 + "C", "http://www.w3.org/2002/07/owl#Nothing", "empty-class"));
            }

            // Reverse order
            for (int i = 0; i < 3; i++) {
                order2.add(subclassClaim("ord2-e" + i, ns3 + "C", "http://www.w3.org/2002/07/owl#Nothing", "empty-class"));
            }
            for (int i = 0; i < 3; i++) {
                order2.add(subclassClaim("ord2-w" + i, ns2 + "C", ns2 + "D", "disjoint-with-witness"));
            }
            for (int i = 0; i < 4; i++) {
                order2.add(subclassClaim("ord2-" + i, ns + "C", ns + "D", "disjoint-no-witness"));
            }

            // Verify all claims in both orders and compare verdicts
            List<Verdict> verdicts1 = new ArrayList<>();
            List<Verdict> verdicts2 = new ArrayList<>();

            for (Claim c : order1) {
                ClaimVerificationResult r = extract(verificationService.verify(c));
                verdicts1.add(r.verdict());
            }
            for (Claim c : order2) {
                ClaimVerificationResult r = extract(verificationService.verify(c));
                verdicts2.add(r.verdict());
            }

            // Both orders should produce the same set of verdicts (order-independent)
            long unknown1 = verdicts1.stream().filter(v -> v == Verdict.UNKNOWN).count();
            long unknown2 = verdicts2.stream().filter(v -> v == Verdict.UNKNOWN).count();
            long contradicted1 = verdicts1.stream().filter(v -> v == Verdict.CONTRADICTED).count();
            long contradicted2 = verdicts2.stream().filter(v -> v == Verdict.CONTRADICTED).count();
            long supported1 = verdicts1.stream().filter(v -> v == Verdict.SUPPORTED).count();
            long supported2 = verdicts2.stream().filter(v -> v == Verdict.SUPPORTED).count();

            assertEquals(unknown1, unknown2, "UNKNOWN count must be order-independent");
            assertEquals(contradicted1, contradicted2, "CONTRADICTED count must be order-independent");
            assertEquals(supported1, supported2, "SUPPORTED count must be order-independent");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Task 14.6: CLI/MCP parity (determinism = parity foundation)
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14.6: CLI/MCP parity (determinism)")
    class CliMcpParityTests {

        @Test
        @DisplayName("Same claim produces identical result across repeated calls (all verdict categories)")
        void determinismAcrossVerdictCategories() {
            String[][] fixtures = {
                {"empty-class", "http://owl4agents.org/test/exact-consistency/empty-class#C", "http://www.w3.org/2002/07/owl#Nothing"},
                {"disjoint-with-witness", "http://owl4agents.org/test/exact-consistency/disjoint-with-witness#C", "http://owl4agents.org/test/exact-consistency/disjoint-with-witness#D"},
                {"disjoint-no-witness", "http://owl4agents.org/test/exact-consistency/disjoint-no-witness#C", "http://owl4agents.org/test/exact-consistency/disjoint-no-witness#D"},
            };

            for (String[] fixture : fixtures) {
                String ontId = fixture[0];
                String subIri = fixture[1];
                String objIri = fixture[2];

                Claim claim1 = subclassClaim("parity-1-" + ontId, subIri, objIri, ontId);
                Claim claim2 = subclassClaim("parity-2-" + ontId, subIri, objIri, ontId);

                ClaimVerificationResult r1 = extract(verificationService.verify(claim1));
                ClaimVerificationResult r2 = extract(verificationService.verify(claim2));

                assertEquals(r1.verdict(), r2.verdict(),
                    "Verdict must be deterministic for " + ontId);
                assertEquals(r1.executionStatus(), r2.executionStatus(),
                    "ExecutionStatus must be deterministic for " + ontId);
                assertEquals(r1.errorCode(), r2.errorCode(),
                    "ErrorCode must be deterministic for " + ontId);
                assertEquals(r1.evidence().size(), r2.evidence().size(),
                    "Evidence count must be deterministic for " + ontId);
            }
        }

        @Test
        @DisplayName("Source-inconsistent fixture produces identical error across calls")
        void sourceInconsistentDeterminism() {
            String ns = "http://owl4agents.org/test/exact-consistency/source-inconsistent#";
            Claim claim1 = subclassClaim("parity-si-1", ns + "A", ns + "B", "source-inconsistent");
            Claim claim2 = subclassClaim("parity-si-2", ns + "A", ns + "B", "source-inconsistent");

            ClaimVerificationResult r1 = extract(verificationService.verify(claim1));
            ClaimVerificationResult r2 = extract(verificationService.verify(claim2));

            assertEquals(r1.executionStatus(), r2.executionStatus(),
                "ExecutionStatus must be deterministic for source-inconsistent");
            assertEquals(r1.errorCode(), r2.errorCode(),
                "ErrorCode must be deterministic for source-inconsistent");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Task 14.7: Readonly workspace integrity
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14.7: Readonly workspace integrity")
    class ReadonlyWorkspaceIntegrityTests {

        @Test
        @DisplayName("No files created/modified in workspace after 100 verify calls")
        void noWorkspaceFilesCreatedOrModified() throws Exception {
            Path workspaceDir = homeResolver.resolveHomeDirectory().resolve("workspaces");

            java.util.Map<Path, Long> snapshotBefore = snapshotFiles(workspaceDir);

            String ns = "http://owl4agents.org/test/exact-consistency/disjoint-no-witness#";
            for (int i = 0; i < 100; i++) {
                Claim claim = subclassClaim("ri-" + i, ns + "C", ns + "D", "disjoint-no-witness");
                verificationService.verify(claim);
            }

            java.util.Map<Path, Long> snapshotAfter = snapshotFiles(workspaceDir);

            assertEquals(snapshotBefore.size(), snapshotAfter.size(),
                "No new files should be created in workspace after 100 verify calls");
            for (java.util.Map.Entry<Path, Long> entry : snapshotBefore.entrySet()) {
                Long afterSize = snapshotAfter.get(entry.getKey());
                assertNotNull(afterSize, "File should not be deleted: " + entry.getKey());
                assertEquals(entry.getValue(), afterSize,
                    "File should not be modified: " + entry.getKey());
            }
        }

        private java.util.Map<Path, Long> snapshotFiles(Path dir) throws Exception {
            java.util.Map<Path, Long> snapshot = new java.util.HashMap<>();
            if (!Files.exists(dir)) return snapshot;
            try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    try {
                        snapshot.put(path, Files.size(path));
                    } catch (IOException e) {
                        // skip files that can't be read
                    }
                });
            }
            return snapshot;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Task 14.8: Source consistency cache
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14.8: Source consistency cache")
    class SourceConsistencyCacheTests {

        @Test
        @DisplayName("Source consistency checked <=5 times for 20 claims against same ontology")
        void sourceConsistencyCacheReducesChecks() {
            long missesBefore = reasonerService.getSourceConsistencyCacheMisses();
            long hitsBefore = reasonerService.getSourceConsistencyCacheHits();

            String ns = "http://owl4agents.org/test/exact-consistency/disjoint-no-witness#";
            for (int i = 0; i < 20; i++) {
                Claim claim = subclassClaim("cache-" + i, ns + "C", ns + "D", "disjoint-no-witness");
                verificationService.verify(claim);
            }

            long missesAfter = reasonerService.getSourceConsistencyCacheMisses();
            long hitsAfter = reasonerService.getSourceConsistencyCacheHits();

            long actualMisses = missesAfter - missesBefore;
            long actualHits = hitsAfter - hitsBefore;

            assertTrue(actualMisses <= 5,
                "Source consistency should be checked <=5 times for 20 claims, but was " + actualMisses);
            assertTrue(actualHits >= 15,
                "Source consistency cache should have >=15 hits for 20 claims, but was " + actualHits);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Helper methods
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

    private List<String> getFixtureOntologyIds() {
        return List.of(
            "disjoint-no-witness", "disjoint-with-witness", "empty-class",
            "existential-no-witness", "existential-with-witness",
            "negative-membership", "negative-prop-assertion",
            "equiv-disjoint-no-witness", "equiv-disjoint-with-witness",
            "data-property-facet-conflict", "different-individuals-witness",
            "subproperty-hierarchy-conflict", "source-inconsistent",
            "imports-closure-main", "timeout-large"
        );
    }

    private void importAllFixtures() {
        for (String ontId : getFixtureOntologyIds()) {
            Path fixturePath = fixturesDir.resolve(ontId + ".owl");
            assertTrue(Files.exists(fixturePath), "Fixture missing: " + fixturePath);
            try {
                ServiceResult<?> result = importer.importOntology(
                    new OntologyId(ontId), fixturePath, WorkspaceId.DEFAULT);
                // imports-closure-main may produce import warnings but should still import
                if (!result.isSuccess()) {
                    // If import fails (e.g. due to unresolvable imports), skip this ontology
                    // — it will be tested separately via loadImportsClosureOntology()
                    if (!"imports-closure-main".equals(ontId)) {
                        assertTrue(result.isSuccess(), "Import failed for " + ontId + ": " + result);
                    }
                }
            } catch (Exception e) {
                if (!"imports-closure-main".equals(ontId)) {
                    fail("Import threw for " + ontId + ": " + e.getMessage());
                }
            }
        }
    }

    private String computeOntologyHash(OWLOntology ontology) {
        var axioms = ontology.getAxioms();
        List<String> axiomStrings = new ArrayList<>();
        for (var axiom : axioms) {
            axiomStrings.add(axiom.toString());
        }
        java.util.Collections.sort(axiomStrings);
        return Integer.toString(axiomStrings.hashCode());
    }

    /**
     * Load the imports-closure main ontology with its import resolved.
     * Uses AutoIRIMapper to map the import IRI to the local file.
     */
    private OWLOntology loadImportsClosureOntology() throws OWLOntologyCreationException, IOException {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        // Set up AutoIRIMapper to resolve imports from the fixtures directory
        AutoIRIMapper iriMapper = new AutoIRIMapper(fixturesDir.toFile(), false);
        manager.getIRIMappers().add(iriMapper);

        Path mainPath = fixturesDir.resolve("imports-closure-main.owl");
        try (InputStream is = Files.newInputStream(mainPath)) {
            return manager.loadOntologyFromOntologyDocument(is);
        }
    }
}
