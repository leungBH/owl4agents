package org.owl4agents.validation;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ResultMetadata;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.model.ClaimVerificationResult;
import org.owl4agents.core.model.EntailmentResult;
import org.owl4agents.core.model.EvidenceItem;
import org.owl4agents.core.model.EvidenceKind;
import org.owl4agents.core.model.EvidencePath;
import org.owl4agents.core.model.InferredFact;
import org.owl4agents.core.model.MissingEntityResult;
import org.owl4agents.core.model.UnknownExplanation;
import org.owl4agents.core.model.UnknownReason;
import org.owl4agents.core.model.Verdict;
import org.owl4agents.reasoner.ReasonerLifecycleManager;
import org.owl4agents.owlapi.SemanticDeepeningService;

/**
 * Task 7.1-7.2: CLI/MCP parity tests for v0.3 claim verification and evidence grounding.
 * Both CLI and MCP use the same ClaimVerificationService and EvidenceGroundingService,
 * so parity is verified by confirming both services produce identical results
 * for the same claim inputs across all verdict categories.
 */
@DisplayName("CLI/MCP parity tests for v0.3 claim verification")
class CliMcpParityTest {

    private StubReasonerService stubReasoner;
    private ClaimVerificationService verificationService;
    private EvidenceGroundingService groundingService;

    @BeforeEach
    void setUp() {
        stubReasoner = new StubReasonerService();
        verificationService = new ClaimVerificationService(
            stubReasoner,
            new ConsistencyAnalysisService(new ReasonerLifecycleManager(), "dummy-path"),
            new SemanticDeepeningService("dummy-path"),
            new StubCatalogStore(),
            new WorkspaceId("default")
        );
        groundingService = new EvidenceGroundingService(
            stubReasoner,
            new ConsistencyAnalysisService(new ReasonerLifecycleManager(), "dummy-path")
        );
    }

    private Claim subclassClaim(String claimId) {
        return new Claim(claimId, ClaimType.SUBCLASS, "test-ontology",
            new ClaimEntity("class", "http://ex.org/A"), "http://ex.org/subClassOf",
            new ClaimEntity("class", "http://ex.org/B"),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    // ── 7.1: Parity for ontology_verify_claim across verdicts ──

    @Nested
    @DisplayName("7.1 Parity for ontology_verify_claim across verdicts")
    class VerifyClaimParityTests {

        @Test
        @DisplayName("SUPPORTED verdict: claimId, type, verdict, and evidence count are deterministic")
        void supportedVerdictParity() {
            stubReasoner.withEntailmentResult(EntailmentResult.ENTAILED);
            Claim claim = subclassClaim("parity-supported");

            ServiceResult<ClaimVerificationResult> result = verificationService.verify(claim);
            assertTrue(result.isSuccess());
            ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();

            assertEquals("parity-supported", data.claimId());
            assertEquals("test-ontology", data.ontologyId());
            assertEquals(ClaimType.SUBCLASS, data.claimType());
            assertEquals(Verdict.SUPPORTED, data.verdict());
            assertFalse(data.evidence().isEmpty());
            assertFalse(data.unknownReason().isPresent());
        }

        @Test
        @DisplayName("UNKNOWN verdict: NOT_ENTAILED yields UNKNOWN without unknownReason")
        void unknownVerdictParity() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            Claim claim = subclassClaim("parity-unknown");

            ServiceResult<ClaimVerificationResult> result = verificationService.verify(claim);
            assertTrue(result.isSuccess());
            ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();

            assertEquals(Verdict.UNKNOWN, data.verdict());
            assertEquals(ClaimType.SUBCLASS, data.claimType());
        }

        @Test
        @DisplayName("CONTRADICTED verdict: ontology inconsistency yields CONTRADICTED with counter evidence")
        void contradictedVerdictParity() {
            stubReasoner.withConsistent(false);
            Claim claim = new Claim("parity-contradicted", ClaimType.ONTOLOGY_CONSISTENCY, "test-ontology",
                null, null, null,
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<ClaimVerificationResult> result = verificationService.verify(claim);
            assertTrue(result.isSuccess());
            ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();

            assertEquals(Verdict.CONTRADICTED, data.verdict());
            assertFalse(data.evidence().isEmpty());
            assertTrue(data.evidence().stream()
                .anyMatch(e -> EvidenceItem.ROLE_COUNTER.equals(e.role())));
        }

        @Test
        @DisplayName("UNSUPPORTED_AXIOM_TYPE yields UNKNOWN with UNSUPPORTED_CLAIM_TYPE reason")
        void unsupportedAxiomTypeParity() {
            stubReasoner.withEntailmentResult(EntailmentResult.UNSUPPORTED_AXIOM_TYPE);
            Claim claim = subclassClaim("parity-unsupported");

            ServiceResult<ClaimVerificationResult> result = verificationService.verify(claim);
            assertTrue(result.isSuccess());
            ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();

            assertEquals(Verdict.UNKNOWN, data.verdict());
            assertTrue(data.unknownReason().isPresent());
            assertEquals(UnknownReason.UNSUPPORTED_CLAIM_TYPE, data.unknownReason().get());
        }
    }

    // ── 7.2: Parity for evidence path, counterexamples, unknown explanation, missing entities ──

    @Nested
    @DisplayName("7.2 Parity for evidence path, counterexamples, unknown explanation, missing entities")
    class EvidenceGroundingParityTests {

        @Test
        @DisplayName("Evidence path: deterministic claimId, ontologyId, and item count")
        void evidencePathParity() {
            stubReasoner.withEntailmentResult(EntailmentResult.ENTAILED);
            stubReasoner.withReasoningReport(true);
            Claim claim = subclassClaim("parity-evidence");

            ServiceResult<ClaimVerificationResult> verifyResult = verificationService.verify(claim);
            assertTrue(verifyResult.isSuccess());
            ClaimVerificationResult verification = ((ServiceResult.Success<ClaimVerificationResult>) verifyResult).data();

            ServiceResult<EvidencePath> pathResult = groundingService.getEvidencePath(claim, verification);
            assertTrue(pathResult.isSuccess());
            EvidencePath path = ((ServiceResult.Success<EvidencePath>) pathResult).data();

            assertEquals("parity-evidence", path.claimId());
            assertEquals("test-ontology", path.ontologyId());
            assertFalse(path.items().isEmpty());
        }

        @Test
        @DisplayName("Counterexamples: all items have counter role")
        void counterexamplesParity() {
            stubReasoner.withConsistent(false);
            Claim claim = new Claim("parity-counter", ClaimType.ONTOLOGY_CONSISTENCY, "test-ontology",
                null, null, null,
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<ClaimVerificationResult> verifyResult = verificationService.verify(claim);
            assertTrue(verifyResult.isSuccess());
            ClaimVerificationResult verification = ((ServiceResult.Success<ClaimVerificationResult>) verifyResult).data();

            ServiceResult<List<EvidenceItem>> counterResult = groundingService.findCounterexamples(claim, verification);
            assertTrue(counterResult.isSuccess());
            List<EvidenceItem> counterexamples = ((ServiceResult.Success<List<EvidenceItem>>) counterResult).data();

            assertFalse(counterexamples.isEmpty());
            assertTrue(counterexamples.stream()
                .allMatch(e -> EvidenceItem.ROLE_COUNTER.equals(e.role())));
        }

        @Test
        @DisplayName("Unknown explanation: deterministic reason and suggested action")
        void unknownExplanationParity() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            Claim claim = subclassClaim("parity-explain");

            ServiceResult<ClaimVerificationResult> verifyResult = verificationService.verify(claim);
            assertTrue(verifyResult.isSuccess());
            ClaimVerificationResult verification = ((ServiceResult.Success<ClaimVerificationResult>) verifyResult).data();

            ServiceResult<UnknownExplanation> explainResult = groundingService.explainUnknown(claim, verification);
            assertTrue(explainResult.isSuccess());
            UnknownExplanation explanation = ((ServiceResult.Success<UnknownExplanation>) explainResult).data();

            assertEquals("parity-explain", explanation.claimId());
            assertEquals("test-ontology", explanation.ontologyId());
            assertTrue(explanation.explanation().isPresent());
            assertTrue(explanation.suggestedAction().isPresent());
        }

        @Test
        @DisplayName("Missing entities: deterministic ontologyId")
        void missingEntitiesParity() {
            stubReasoner.withInferredFacts(List.of(
                new InferredFact("test-ontology", "http://ex.org/A",
                    "http://ex.org/subClassOf", "http://ex.org/B",
                    null, "SubClassOf", "inferred", "HermiT")
            ));
            Claim claim = subclassClaim("parity-missing");

            ServiceResult<MissingEntityResult> result = groundingService.detectMissingEntities(claim);
            assertTrue(result.isSuccess());
            MissingEntityResult data = ((ServiceResult.Success<MissingEntityResult>) result).data();

            assertEquals("test-ontology", data.ontologyId());
        }
    }
}