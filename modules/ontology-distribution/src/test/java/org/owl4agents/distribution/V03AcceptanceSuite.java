package org.owl4agents.distribution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.owl4agents.core.GraphScope;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.model.ClaimVerificationResult;
import org.owl4agents.core.model.EvidenceItem;
import org.owl4agents.core.model.EvidencePath;
import org.owl4agents.core.model.MissingEntityResult;
import org.owl4agents.core.model.UnknownExplanation;
import org.owl4agents.core.model.UnknownReason;
import org.owl4agents.core.model.Verdict;
import org.owl4agents.owlapi.OntologyImporter;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.ReasonerServiceImpl;
import org.owl4agents.storage.CatalogStore;
import org.owl4agents.storage.HomeDirectoryResolver;
import org.owl4agents.storage.WorkspaceInitializer;
import org.owl4agents.validation.ClaimVerificationService;
import org.owl4agents.validation.ConsistencyAnalysisService;
import org.owl4agents.validation.EvidenceGroundingService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.3 end-to-end acceptance suite against the golden claim verification fixture.
 * Verifies version-specific claim verdicts and evidence grounding with the
 * real importer, catalog, reasoner adapter, validation, and evidence services.
 */
@DisplayName("v0.3 end-to-end acceptance suite")
class V03AcceptanceSuite {

    private static final String ONTOLOGY_ID = "v0.3-claim-verification";
    private static final String BASE = "http://example.org/v0.3#";

    @TempDir
    Path tempDir;

    private HomeDirectoryResolver homeResolver;
    private WorkspaceInitializer initializer;
    private CatalogStore catalogStore;
    private OntologyImporter importer;
    private ClaimVerificationService verificationService;
    private EvidenceGroundingService evidenceService;
    private String corpusFixturesPath;

    @BeforeEach
    void setup() {
        homeResolver = new HomeDirectoryResolver(tempDir);
        initializer = new WorkspaceInitializer(homeResolver);
        catalogStore = new CatalogStore(homeResolver);
        importer = new OntologyImporter(homeResolver, catalogStore);
        corpusFixturesPath = System.getProperty("corpus.fixtures", "../test/corpus");

        initializer.initializeIdempotent(WorkspaceId.DEFAULT);
        importGoldenOntology();

        ReasonerServiceImpl reasonerService = createReasonerService();
        String workspaceBasePath = workspaceBasePath();
        ConsistencyAnalysisService consistencyService =
            new ConsistencyAnalysisService(reasonerService.getLifecycleManager(), workspaceBasePath);
        SemanticDeepeningService deepeningService = new SemanticDeepeningService(workspaceBasePath);

        verificationService = new ClaimVerificationService(
            reasonerService,
            consistencyService,
            deepeningService,
            catalogStore,
            WorkspaceId.DEFAULT
        );
        evidenceService = new EvidenceGroundingService(reasonerService, consistencyService);
    }

    private String workspaceBasePath() {
        return homeResolver.resolveHomeDirectory().resolve("workspaces").toString();
    }

    private ReasonerServiceImpl createReasonerService() {
        return new ReasonerServiceImpl(catalogStore, workspaceBasePath());
    }

    private Path resolveFixture(String relativePath) {
        Path fixturePath = Path.of(corpusFixturesPath).resolve(relativePath);
        if (Files.exists(fixturePath)) {
            return fixturePath;
        }
        return Path.of("test/corpus").resolve(relativePath);
    }

    private void importGoldenOntology() {
        Path fixturePath = resolveFixture("golden/v0.3-claim-verification.owl");
        assertTrue(Files.exists(fixturePath), "Required v0.3 golden ontology is missing: " + fixturePath);

        ServiceResult<?> result = importer.importOntology(
            new OntologyId(ONTOLOGY_ID), fixturePath, WorkspaceId.DEFAULT);
        assertTrue(result.isSuccess(), "v0.3 golden ontology import must succeed");
    }

    private Claim claim(String id, ClaimType type, String subjectKind, String subjectLocalName,
                        String predicate, String objectKind, String objectLocalName) {
        ClaimEntity subject = subjectLocalName == null
            ? null
            : new ClaimEntity(subjectKind, BASE + subjectLocalName);
        ClaimEntity object = objectLocalName == null
            ? null
            : new ClaimEntity(objectKind, BASE + objectLocalName);
        return new Claim(
            id,
            type,
            ONTOLOGY_ID,
            subject,
            predicate,
            object,
            Optional.empty(),
            Optional.of(GraphScope.EXPLICIT),
            Optional.of(Map.of(Claim.INCLUDE_EVIDENCE, true))
        );
    }

    private ClaimVerificationResult verify(Claim claim) {
        ServiceResult<ClaimVerificationResult> result = verificationService.verify(claim);
        assertTrue(result.isSuccess(), "Claim verification must succeed for " + claim.claimId());
        return ((ServiceResult.Success<ClaimVerificationResult>) result).data();
    }

    @Nested
    @DisplayName("Claim verdicts")
    class ClaimVerdictTests {

        @Test
        @DisplayName("Subclass claim is supported by inferred hierarchy")
        void subclassClaimIsSupported() {
            Claim claim = claim("v03-supported", ClaimType.SUBCLASS,
                "class", "Dog", "subClassOf", "class", "Animal");

            ClaimVerificationResult result = verify(claim);

            assertEquals(Verdict.SUPPORTED, result.verdict());
            assertEquals(ClaimType.SUBCLASS, result.claimType());
            assertFalse(result.evidence().isEmpty(), "Supported claims must include grounding evidence");
        }

        @Test
        @DisplayName("False disjointness claim is contradicted by class compatibility")
        void falseDisjointnessClaimIsContradicted() {
            Claim claim = claim("v03-contradicted", ClaimType.DISJOINT_CLASSES,
                "class", "Dog", "disjointWith", "class", "Mammal");

            ClaimVerificationResult result = verify(claim);

            assertEquals(Verdict.CONTRADICTED, result.verdict());
            assertTrue(result.evidence().stream()
                .anyMatch(item -> EvidenceItem.ROLE_COUNTER.equals(item.role())),
                "Contradicted claims must include counter evidence");
        }

        @Test
        @DisplayName("Sparse subclass claim remains unknown instead of contradicted")
        void sparseSubclassClaimIsUnknown() {
            Claim claim = claim("v03-unknown", ClaimType.SUBCLASS,
                "class", "Goldfish", "subClassOf", "class", "Fish");

            ClaimVerificationResult result = verify(claim);

            assertEquals(Verdict.UNKNOWN, result.verdict());
            assertEquals(Optional.of(UnknownReason.INSUFFICIENT_AXIOMS), result.unknownReason(),
                "Non-entailed subclass claim should have insufficient_axioms reason");
        }

        @Test
        @DisplayName("Undeclared entity claim is out of scope with missing entity reason")
        void undeclaredEntityClaimIsOutOfScope() {
            Claim claim = claim("v03-out-of-scope", ClaimType.ONTOLOGY_SCOPE,
                "class", "DeliveryPrice", "inScopeOf", "class", "Animal");

            ClaimVerificationResult result = verify(claim);

            assertEquals(Verdict.OUT_OF_SCOPE, result.verdict());
            assertEquals(Optional.of(UnknownReason.MISSING_ENTITY), result.unknownReason());
        }
    }

    @Nested
    @DisplayName("Evidence grounding")
    class EvidenceGroundingTests {

        @Test
        @DisplayName("Supported claim has an evidence path")
        void supportedClaimHasEvidencePath() {
            Claim claim = claim("v03-evidence", ClaimType.SUBCLASS,
                "class", "Dog", "subClassOf", "class", "Animal");
            ClaimVerificationResult verification = verify(claim);

            ServiceResult<EvidencePath> result = evidenceService.getEvidencePath(claim, verification);

            assertTrue(result.isSuccess(), "Supported claim evidence path must be available");
            EvidencePath path = ((ServiceResult.Success<EvidencePath>) result).data();
            assertEquals("v03-evidence", path.claimId());
            assertFalse(path.items().isEmpty());
        }

        @Test
        @DisplayName("Contradicted claim exposes counterexamples")
        void contradictedClaimExposesCounterexamples() {
            Claim claim = claim("v03-counterexamples", ClaimType.DISJOINT_CLASSES,
                "class", "Dog", "disjointWith", "class", "Mammal");
            ClaimVerificationResult verification = verify(claim);

            ServiceResult<List<EvidenceItem>> result =
                evidenceService.findCounterexamples(claim, verification);

            assertTrue(result.isSuccess(), "Contradicted claim counterexamples must be available");
            List<EvidenceItem> counterexamples = ((ServiceResult.Success<List<EvidenceItem>>) result).data();
            assertFalse(counterexamples.isEmpty());
            assertTrue(counterexamples.stream().allMatch(item -> EvidenceItem.ROLE_COUNTER.equals(item.role())));
        }

        @Test
        @DisplayName("Unknown claim has an explanation")
        void unknownClaimHasExplanation() {
            Claim claim = claim("v03-explain-unknown", ClaimType.SUBCLASS,
                "class", "Goldfish", "subClassOf", "class", "Fish");
            ClaimVerificationResult verification = verify(claim);

            ServiceResult<UnknownExplanation> result =
                evidenceService.explainUnknown(claim, verification);

            assertTrue(result.isSuccess(), "Unknown claim explanation must be available");
            UnknownExplanation explanation = ((ServiceResult.Success<UnknownExplanation>) result).data();
            assertEquals("v03-explain-unknown", explanation.claimId());
            assertTrue(explanation.explanation().isPresent());
        }

        @Test
        @DisplayName("Missing entity detection reports undeclared claim entities")
        void missingEntityDetectionReportsUndeclaredEntities() {
            Claim claim = claim("v03-missing", ClaimType.ONTOLOGY_SCOPE,
                "class", "DeliveryPrice", "inScopeOf", "class", "Animal");

            ServiceResult<MissingEntityResult> result = evidenceService.detectMissingEntities(claim);

            assertTrue(result.isSuccess(), "Missing entity detection must succeed");
            MissingEntityResult missing = ((ServiceResult.Success<MissingEntityResult>) result).data();
            assertTrue(missing.missing().stream()
                .anyMatch(match -> match.searchTerm().equals(BASE + "DeliveryPrice")),
                "Undeclared DeliveryPrice IRI must be reported as missing");
        }
    }
}
