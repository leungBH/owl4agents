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
import org.owl4agents.core.model.EvidenceKind;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.8 task 7.6: Unit tests for Stage 4 satisfiability check (D2/D3).
 *
 * <p>Verifies that {@link ClaimVerificationService#verify} correctly performs
 * the class satisfiability check after Stage 4 consistency check returns
 * CONSISTENT, and maps the result to the correct verdict (CONTRADICTED when
 * a class becomes unsatisfiable, UNKNOWN otherwise).
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Subclass claim with unsatisfiable subject → CONTRADICTED</li>
 *   <li>Disjoint classes claim with unsatisfiable subject → CONTRADICTED</li>
 *   <li>Equivalent classes claim with unsatisfiable entity → CONTRADICTED</li>
 *   <li>All satisfiable → UNKNOWN</li>
 *   <li>Individual membership skipped → UNKNOWN</li>
 *   <li>Object property assertion skipped → UNKNOWN</li>
 *   <li>Pre-existing unsatisfiable class → UNKNOWN (NOT CONTRADICTED)</li>
 * </ol>
 */
@DisplayName("v0.8.8 7.6: Stage 4 satisfiability check")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Stage4SatisfiabilityCheckTest {

    @TempDir
    Path tempDir;

    private HomeDirectoryResolver homeResolver;
    private CatalogStore catalogStore;
    private OntologyImporter importer;
    private ReasonerServiceImpl reasonerService;
    private ClaimVerificationService verificationService;
    private OWLDataFactory df;

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

        df = OWLManager.getOWLDataFactory();
    }

    /**
     * Create an ontology in memory, save it to a temp file, and import it
     * into the workspace catalog under the given ontology ID.
     */
    private void importInMemoryOntology(String ontId, OntologyBuilder builder) throws Exception {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = mgr.getOWLDataFactory();
        String ns = "http://owl4agents.org/test/stage4-sat/" + ontId + "#";
        OWLOntology ont = mgr.createOntology(IRI.create(ns));
        builder.build(ont, factory, ns);

        Path owlFile = tempDir.resolve(ontId + ".owl");
        mgr.saveOntology(ont, IRI.create(owlFile.toUri()));

        ServiceResult<?> result = importer.importOntology(
            new OntologyId(ontId), owlFile, WorkspaceId.DEFAULT);
        assertTrue(result.isSuccess(), "Import failed for " + ontId + ": " + result);
    }

    @FunctionalInterface
    interface OntologyBuilder {
        void build(OWLOntology ont, OWLDataFactory df, String ns) throws Exception;
    }

    private Claim subclassClaim(String id, String subIri, String objIri, String ontId) {
        return new Claim(id, ClaimType.SUBCLASS, ontId,
            new ClaimEntity("class", subIri),
            "http://www.w3.org/2000/01/rdf-schema#subClassOf",
            new ClaimEntity("class", objIri),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private Claim disjointClassesClaim(String id, String c1Iri, String c2Iri, String ontId) {
        return new Claim(id, ClaimType.DISJOINT_CLASSES, ontId,
            new ClaimEntity("class", c1Iri), null,
            new ClaimEntity("class", c2Iri),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private Claim equivalentClassesClaim(String id, String c1Iri, String c2Iri, String ontId) {
        return new Claim(id, ClaimType.EQUIVALENT_CLASSES, ontId,
            new ClaimEntity("class", c1Iri), null,
            new ClaimEntity("class", c2Iri),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private Claim individualMembershipClaim(String id, String indIri, String clsIri, String ontId) {
        return new Claim(id, ClaimType.INDIVIDUAL_MEMBERSHIP, ontId,
            new ClaimEntity("individual", indIri), null,
            new ClaimEntity("class", clsIri),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private Claim objectPropertyAssertionClaim(String id, String subjIri, String propIri,
                                                String objIri, String ontId) {
        return new Claim(id, ClaimType.OBJECT_PROPERTY_ASSERTION, ontId,
            new ClaimEntity("individual", subjIri),
            propIri,
            new ClaimEntity("individual", objIri),
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

    // ════════════════════════════════════════════════════════════════════
    // Scenario 1: Subclass claim with unsatisfiable subject → CONTRADICTED
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("1. Subclass claim with unsatisfiable subject → CONTRADICTED")
    void subclassUnsatisfiableSubjectYieldsContradicted() throws Exception {
        String ontId = "sc-unsat";
        importInMemoryOntology(ontId, (ont, df, ns) -> {
            OWLClass c = df.getOWLClass(IRI.create(ns + "C"));
            OWLClass d = df.getOWLClass(IRI.create(ns + "D"));
            ont.addAxiom(df.getOWLDeclarationAxiom(c));
            ont.addAxiom(df.getOWLDeclarationAxiom(d));
            // DisjointClasses(C, D) — adding SubClassOf(C, D) makes C unsatisfiable
            ont.addAxiom(df.getOWLDisjointClassesAxiom(c, d));
        });

        String ns = "http://owl4agents.org/test/stage4-sat/" + ontId + "#";
        Claim claim = subclassClaim("sc-1", ns + "C", ns + "D", ontId);
        ClaimVerificationResult result = extract(verificationService.verify(claim));

        assertEquals(Verdict.CONTRADICTED, result.verdict(),
            "SubClassOf(C, D) with DisjointClasses(C, D) makes C unsatisfiable → CONTRADICTED");
        assertTrue(hasSatisfiabilityCheckEvidence(result),
            "Evidence should include a SATISFIABILITY_CHECK entry");
    }

    // ════════════════════════════════════════════════════════════════════
    // Scenario 2: Disjoint classes claim with unsatisfiable subject → CONTRADICTED
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("2. Disjoint classes claim with unsatisfiable subject → CONTRADICTED")
    void disjointClassesUnsatisfiableSubjectYieldsContradicted() throws Exception {
        String ontId = "dc-unsat";
        importInMemoryOntology(ontId, (ont, df, ns) -> {
            OWLClass c = df.getOWLClass(IRI.create(ns + "C"));
            OWLClass d = df.getOWLClass(IRI.create(ns + "D"));
            ont.addAxiom(df.getOWLDeclarationAxiom(c));
            ont.addAxiom(df.getOWLDeclarationAxiom(d));
            // SubClassOf(C, D) — adding DisjointClasses(C, D) makes C unsatisfiable
            ont.addAxiom(df.getOWLSubClassOfAxiom(c, d));
        });

        String ns = "http://owl4agents.org/test/stage4-sat/" + ontId + "#";
        Claim claim = disjointClassesClaim("dc-1", ns + "C", ns + "D", ontId);
        ClaimVerificationResult result = extract(verificationService.verify(claim));

        assertEquals(Verdict.CONTRADICTED, result.verdict(),
            "DisjointClasses(C, D) with SubClassOf(C, D) makes C unsatisfiable → CONTRADICTED");
        assertTrue(hasSatisfiabilityCheckEvidence(result),
            "Evidence should include a SATISFIABILITY_CHECK entry");
    }

    // ════════════════════════════════════════════════════════════════════
    // Scenario 3: Equivalent classes claim with unsatisfiable entity → CONTRADICTED
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("3. Equivalent classes claim with unsatisfiable entity → CONTRADICTED")
    void equivalentClassesUnsatisfiableEntityYieldsContradicted() throws Exception {
        String ontId = "ec-unsat";
        importInMemoryOntology(ontId, (ont, df, ns) -> {
            OWLClass c = df.getOWLClass(IRI.create(ns + "C"));
            OWLClass d = df.getOWLClass(IRI.create(ns + "D"));
            ont.addAxiom(df.getOWLDeclarationAxiom(c));
            ont.addAxiom(df.getOWLDeclarationAxiom(d));
            // DisjointClasses(C, D) — adding EquivalentClasses(C, D) makes both unsatisfiable
            ont.addAxiom(df.getOWLDisjointClassesAxiom(c, d));
        });

        String ns = "http://owl4agents.org/test/stage4-sat/" + ontId + "#";
        Claim claim = equivalentClassesClaim("ec-1", ns + "C", ns + "D", ontId);
        ClaimVerificationResult result = extract(verificationService.verify(claim));

        assertEquals(Verdict.CONTRADICTED, result.verdict(),
            "EquivalentClasses(C, D) with DisjointClasses(C, D) makes C and D unsatisfiable → CONTRADICTED");
        assertTrue(hasSatisfiabilityCheckEvidence(result),
            "Evidence should include a SATISFIABILITY_CHECK entry");
    }

    // ════════════════════════════════════════════════════════════════════
    // Scenario 4: All satisfiable → UNKNOWN
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("4. All satisfiable → UNKNOWN")
    void allSatisfiableYieldsUnknown() throws Exception {
        String ontId = "sc-all-sat";
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

        String ns = "http://owl4agents.org/test/stage4-sat/" + ontId + "#";
        // SubClassOf(E, C) — not entailed, consistent, all satisfiable → UNKNOWN
        Claim claim = subclassClaim("sc-4", ns + "E", ns + "C", ontId);
        ClaimVerificationResult result = extract(verificationService.verify(claim));

        assertEquals(Verdict.UNKNOWN, result.verdict(),
            "SubClassOf(E, C) with no disjointness → all satisfiable → UNKNOWN");
        assertFalse(hasSatisfiabilityCheckEvidence(result),
            "Evidence should NOT include a SATISFIABILITY_CHECK entry when all satisfiable");
    }

    // ════════════════════════════════════════════════════════════════════
    // Scenario 5: Individual membership skipped → UNKNOWN
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("5. Individual membership skipped → UNKNOWN")
    void individualMembershipSkippedYieldsUnknown() throws Exception {
        String ontId = "im-skip";
        importInMemoryOntology(ontId, (ont, df, ns) -> {
            OWLClass c = df.getOWLClass(IRI.create(ns + "C"));
            OWLClass d = df.getOWLClass(IRI.create(ns + "D"));
            OWLNamedIndividual a = df.getOWLNamedIndividual(IRI.create(ns + "a"));
            ont.addAxiom(df.getOWLDeclarationAxiom(c));
            ont.addAxiom(df.getOWLDeclarationAxiom(d));
            ont.addAxiom(df.getOWLDeclarationAxiom(a));
            // a is of type C, D ⊑ C — a is NOT of type D (D is a subclass of C)
            ont.addAxiom(df.getOWLSubClassOfAxiom(d, c));
            ont.addAxiom(df.getOWLClassAssertionAxiom(c, a));
        });

        String ns = "http://owl4agents.org/test/stage4-sat/" + ontId + "#";
        // IndividualMembership(a, D) — not entailed (a is C, but not necessarily D)
        // Satisfiability check is skipped for individual_membership → UNKNOWN
        Claim claim = individualMembershipClaim("im-1", ns + "a", ns + "D", ontId);
        ClaimVerificationResult result = extract(verificationService.verify(claim));

        assertEquals(Verdict.UNKNOWN, result.verdict(),
            "IndividualMembership(a, D) not entailed, consistent → UNKNOWN (satisfiability check skipped)");
        assertFalse(hasSatisfiabilityCheckEvidence(result),
            "Evidence should NOT include SATISFIABILITY_CHECK for individual_membership claims");
    }

    // ════════════════════════════════════════════════════════════════════
    // Scenario 6: Object property assertion skipped → UNKNOWN
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("6. Object property assertion skipped → UNKNOWN")
    void objectPropertyAssertionSkippedYieldsUnknown() throws Exception {
        String ontId = "op-skip";
        importInMemoryOntology(ontId, (ont, df, ns) -> {
            OWLClass c = df.getOWLClass(IRI.create(ns + "C"));
            OWLClass d = df.getOWLClass(IRI.create(ns + "D"));
            OWLObjectProperty r = df.getOWLObjectProperty(IRI.create(ns + "r"));
            OWLNamedIndividual a = df.getOWLNamedIndividual(IRI.create(ns + "a"));
            OWLNamedIndividual b = df.getOWLNamedIndividual(IRI.create(ns + "b"));
            ont.addAxiom(df.getOWLDeclarationAxiom(c));
            ont.addAxiom(df.getOWLDeclarationAxiom(d));
            ont.addAxiom(df.getOWLDeclarationAxiom(r));
            ont.addAxiom(df.getOWLDeclarationAxiom(a));
            ont.addAxiom(df.getOWLDeclarationAxiom(b));
            ont.addAxiom(df.getOWLClassAssertionAxiom(c, a));
            ont.addAxiom(df.getOWLClassAssertionAxiom(d, b));
        });

        String ns = "http://owl4agents.org/test/stage4-sat/" + ontId + "#";
        // ObjectPropertyAssertion(a, r, b) — not entailed, consistent
        // Satisfiability check is skipped for object_property_assertion → UNKNOWN
        Claim claim = objectPropertyAssertionClaim("op-1", ns + "a", ns + "r", ns + "b", ontId);
        ClaimVerificationResult result = extract(verificationService.verify(claim));

        assertEquals(Verdict.UNKNOWN, result.verdict(),
            "ObjectPropertyAssertion(a, r, b) not entailed, consistent → UNKNOWN (satisfiability check skipped)");
        assertFalse(hasSatisfiabilityCheckEvidence(result),
            "Evidence should NOT include SATISFIABILITY_CHECK for object_property_assertion claims");
    }

    // ════════════════════════════════════════════════════════════════════
    // Scenario 7: Pre-existing unsatisfiable class → UNKNOWN (NOT CONTRADICTED)
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("7. Pre-existing unsatisfiable class → UNKNOWN (NOT CONTRADICTED)")
    void preExistingUnsatisfiableClassYieldsUnknown() throws Exception {
        // NOTE: In OWL 2 DL, an unsatisfiable class C is equivalent to owl:Nothing
        // (C ≡ ⊥). This means SubClassOf(C, X) is trivially entailed (⊥ ⊑ X) and
        // would return SUPPORTED at Stage 3, never reaching Stage 4's satisfiability
        // check. To test the "pre-existing unsatisfiable" path, we create an
        // ontology with a pre-existing unsatisfiable class (A) that is NOT involved
        // in the claim axiom. The claim involves separate satisfiable classes (E, C).
        // The test verifies that the pre-existing unsatisfiable class A does not
        // cause a false CONTRADICTED verdict — the satisfiability check only
        // inspects classes named in the claim axiom (E, C), not all classes in O.
        String ontId = "preexist-unsat";
        importInMemoryOntology(ontId, (ont, df, ns) -> {
            OWLClass a = df.getOWLClass(IRI.create(ns + "A"));
            OWLClass b = df.getOWLClass(IRI.create(ns + "B"));
            OWLClass c = df.getOWLClass(IRI.create(ns + "C"));
            OWLClass d = df.getOWLClass(IRI.create(ns + "D"));
            OWLClass e = df.getOWLClass(IRI.create(ns + "E"));
            ont.addAxiom(df.getOWLDeclarationAxiom(a));
            ont.addAxiom(df.getOWLDeclarationAxiom(b));
            ont.addAxiom(df.getOWLDeclarationAxiom(c));
            ont.addAxiom(df.getOWLDeclarationAxiom(d));
            ont.addAxiom(df.getOWLDeclarationAxiom(e));
            // A is ALREADY unsatisfiable in O: DisjointClasses(A, B) + SubClassOf(A, B)
            // (pre-existing unsatisfiability — not caused by the claim)
            ont.addAxiom(df.getOWLDisjointClassesAxiom(a, b));
            ont.addAxiom(df.getOWLSubClassOfAxiom(a, b));
            // D ⊑ C — simple hierarchy for the claim classes
            ont.addAxiom(df.getOWLSubClassOfAxiom(d, c));
        });

        String ns = "http://owl4agents.org/test/stage4-sat/" + ontId + "#";
        // SubClassOf(E, C) — not entailed, consistent
        // E and C are satisfiable in O∪{α} (pre-existing unsat A is irrelevant)
        // → UNKNOWN (NOT CONTRADICTED, because no class BECAME unsatisfiable)
        Claim claim = subclassClaim("pre-1", ns + "E", ns + "C", ontId);
        ClaimVerificationResult result = extract(verificationService.verify(claim));

        assertEquals(Verdict.UNKNOWN, result.verdict(),
            "Pre-existing unsatisfiable class A: claim SubClassOf(E, C) doesn't make E or C unsatisfiable → UNKNOWN (NOT CONTRADICTED)");
        assertFalse(hasSatisfiabilityCheckEvidence(result),
            "Evidence should NOT include SATISFIABILITY_CHECK when no class became unsatisfiable");
    }
}
