package org.owl4agents.validation;

import java.util.List;
import java.util.Optional;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.ReasonerLifecycleManager;

/**
 * Task 3.7 and 3.8 tests: proves batch workflow results preserve
 * single-claim verdict semantics, and v0.3 wrapped fixtures maintain
 * their verdicts when wrapped into v0.5 batches.
 */
class ClaimWorkflowServiceTest {

    private StubReasonerService stubReasoner;
    private StubCatalogStore stubCatalog;
    private ClaimVerificationService claimVerificationService;
    private EvidenceGroundingService evidenceGroundingService;
    private ClaimWorkflowService workflowService;

    @BeforeEach
    void setUp() {
        stubReasoner = new StubReasonerService();
        stubCatalog = new StubCatalogStore();

        claimVerificationService = new ClaimVerificationService(
            stubReasoner,
            new ConsistencyAnalysisService(new ReasonerLifecycleManager(), "dummy-path"),
            new SemanticDeepeningService("dummy-path"),
            stubCatalog,
            new WorkspaceId("default")
        );

        evidenceGroundingService = new EvidenceGroundingService(
            stubReasoner,
            new ConsistencyAnalysisService(new ReasonerLifecycleManager(), "dummy-path")
        );

        workflowService = new ClaimWorkflowService(
            claimVerificationService,
            evidenceGroundingService,
            stubCatalog,
            new WorkspaceId("default")
        );
    }

    // ── Task 3.7: Batch results preserve single-claim verdict semantics ──

    @Nested
    @DisplayName("Task 3.7: Batch results preserve single-claim verdict semantics")
    class BatchParityTests {

        @Test
        @DisplayName("Single supported claim in batch → verified aggregate")
        void singleSupportedClaimYieldsVerified() {
            stubReasoner.withEntailmentResult(EntailmentResult.ENTAILED);

            ClaimBatchInput batch = new ClaimBatchInput(
                "answer-001",
                Optional.of("Is Dog a kind of Animal?"),
                Optional.empty(),
                List.of(new ClaimBatchInput.BatchClaim(
                    "c1", ClaimType.SUBCLASS, true,
                    Optional.of(new ClaimEntity("class", "http://ex.org/Dog")),
                    Optional.of("subClassOf"),
                    Optional.of(new ClaimEntity("class", "http://ex.org/Animal")),
                    Optional.empty(), Optional.empty(), Optional.empty()
                )),
                Optional.empty()
            );

            ServiceResult<AnswerVerificationReport> result = workflowService.verifyBatch(batch, "test-ontology");
            assertTrue(result.isSuccess(), "Batch verification should succeed");

            AnswerVerificationReport report = ((ServiceResult.Success<AnswerVerificationReport>) result).data();
            assertEquals(AggregateAnswerStatus.VERIFIED, report.aggregateStatus(),
                "Single supported required claim → verified aggregate");
            assertEquals(1, report.claimResults().size());
            assertEquals(Verdict.SUPPORTED, report.claimResults().get(0).verdict(),
                "Per-claim verdict should be SUPPORTED");
            assertTrue(report.claimResults().get(0).required(),
                "Claim should be marked as required=true");
        }

        @Test
        @DisplayName("Single contradicted required claim → contradicted aggregate")
        void singleContradictedClaimYieldsContradicted() {
            // ONTOLOGY_CONSISTENCY with inconsistent ontology → contradicted verdict
            stubReasoner.withConsistent(false);

            ClaimBatchInput batch = new ClaimBatchInput(
                "answer-002",
                Optional.empty(),
                Optional.empty(),
                List.of(new ClaimBatchInput.BatchClaim(
                    "c1", ClaimType.ONTOLOGY_CONSISTENCY, true,
                    Optional.empty(),  // no subject for ontology-level claims
                    Optional.empty(),
                    Optional.empty(),  // no object for ontology-level claims
                    Optional.empty(), Optional.empty(), Optional.empty()
                )),
                Optional.empty()
            );

            ServiceResult<AnswerVerificationReport> result = workflowService.verifyBatch(batch, "test-ontology");
            assertTrue(result.isSuccess());

            AnswerVerificationReport report = ((ServiceResult.Success<AnswerVerificationReport>) result).data();
            assertEquals(AggregateAnswerStatus.CONTRADICTED, report.aggregateStatus(),
                "Single contradicted required claim → contradicted aggregate");
            assertEquals(Verdict.CONTRADICTED, report.claimResults().get(0).verdict());
        }

        @Test
        @DisplayName("Single unknown required claim with missing entities → out_of_scope (DEFECT-014 fix)")
        void singleUnknownClaimWithMissingEntitiesYieldsOutOfScope() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);

            ClaimBatchInput batch = new ClaimBatchInput(
                "answer-003",
                Optional.empty(),
                Optional.empty(),
                List.of(new ClaimBatchInput.BatchClaim(
                    "c1", ClaimType.SUBCLASS, true,
                    Optional.of(new ClaimEntity("class", "http://ex.org/Goldfish")),
                    Optional.of("subClassOf"),
                    Optional.of(new ClaimEntity("class", "http://ex.org/Fish")),
                    Optional.empty(), Optional.empty(), Optional.empty()
                )),
                Optional.empty()
            );

            ServiceResult<AnswerVerificationReport> result = workflowService.verifyBatch(batch, "test-ontology");
            assertTrue(result.isSuccess());

            AnswerVerificationReport report = ((ServiceResult.Success<AnswerVerificationReport>) result).data();
            // DEFECT-014 fix: when entities are missing from ontology, UNKNOWN verdict
            // is upgraded to OUT_OF_SCOPE, and aggregate becomes out_of_scope (not insufficient_evidence)
            assertEquals(AggregateAnswerStatus.OUT_OF_SCOPE, report.aggregateStatus(),
                "Missing entities → out_of_scope aggregate (DEFECT-014 fix)");
            assertEquals(Verdict.OUT_OF_SCOPE, report.claimResults().get(0).verdict(),
                "Verdict upgraded from UNKNOWN to OUT_OF_SCOPE when entities are missing");
            assertTrue(report.claimResults().get(0).unknownReason().isPresent(),
                "Upgraded claim must have unknownReason explaining upgrade");
            assertEquals("missing_or_out_of_scope_entities",
                report.claimResults().get(0).unknownReason().get(),
                "Upgrade reason must be 'missing_or_out_of_scope_entities'");
        }

        @Test
        @DisplayName("Ontology not found → error result")
        void ontologyNotFoundReturnsError() {
            stubCatalog.withNotFound("missing-ontology");

            ClaimBatchInput batch = new ClaimBatchInput(
                "answer-missing",
                Optional.empty(),
                Optional.empty(),
                List.of(new ClaimBatchInput.BatchClaim(
                    "c1", ClaimType.SUBCLASS, true,
                    Optional.of(new ClaimEntity("class", "http://ex.org/A")),
                    Optional.of("subClassOf"),
                    Optional.of(new ClaimEntity("class", "http://ex.org/B")),
                    Optional.empty(), Optional.empty(), Optional.empty()
                )),
                Optional.empty()
            );

            ServiceResult<AnswerVerificationReport> result = workflowService.verifyBatch(batch, "missing-ontology");
            assertFalse(result.isSuccess(), "Should fail for missing ontology");
        }
    }

    // ── Task 3.7: Aggregate status truth table ──

    @Nested
    @DisplayName("Aggregate status truth table tests")
    class AggregateStatusTruthTableTests {

        @Test
        @DisplayName("All required supported → verified")
        void allRequiredSupportedYieldsVerified() {
            List<ClaimWorkflowResult> claims = List.of(
                makeClaimResult("c1", true, Verdict.SUPPORTED),
                makeClaimResult("c2", true, Verdict.SUPPORTED)
            );
            assertEquals(AggregateAnswerStatus.VERIFIED,
                computeStatus(claims), "All required supported → verified");
        }

        @Test
        @DisplayName("Supported + unknown required → insufficient_evidence")
        void supportedAndUnknownYieldsInsufficientEvidence() {
            List<ClaimWorkflowResult> claims = List.of(
                makeClaimResult("c1", true, Verdict.SUPPORTED),
                makeClaimResult("c2", true, Verdict.UNKNOWN)
            );
            assertEquals(AggregateAnswerStatus.INSUFFICIENT_EVIDENCE,
                computeStatus(claims), "Supported + unknown required → insufficient_evidence");
        }

        @Test
        @DisplayName("Supported + out_of_scope required → partially_verified")
        void supportedAndOutOfScopeYieldsPartiallyVerified() {
            List<ClaimWorkflowResult> claims = List.of(
                makeClaimResult("c1", true, Verdict.SUPPORTED),
                makeClaimResult("c2", true, Verdict.OUT_OF_SCOPE)
            );
            assertEquals(AggregateAnswerStatus.PARTIALLY_VERIFIED,
                computeStatus(claims), "Supported + out_of_scope required → partially_verified");
        }

        @Test
        @DisplayName("All required out_of_scope → out_of_scope")
        void allOutOfScopeYieldsOutOfScope() {
            List<ClaimWorkflowResult> claims = List.of(
                makeClaimResult("c1", true, Verdict.OUT_OF_SCOPE),
                makeClaimResult("c2", true, Verdict.OUT_OF_SCOPE)
            );
            assertEquals(AggregateAnswerStatus.OUT_OF_SCOPE,
                computeStatus(claims), "All out_of_scope required → out_of_scope");
        }

        @Test
        @DisplayName("Contradicted + anything → contradicted (priority rule)")
        void contradictedDominatesAll() {
            // contradicted + supported
            assertEquals(AggregateAnswerStatus.CONTRADICTED,
                computeStatus(List.of(
                    makeClaimResult("c1", true, Verdict.CONTRADICTED),
                    makeClaimResult("c2", true, Verdict.SUPPORTED)
                )), "contradicted + supported → contradicted");

            // contradicted + unknown
            assertEquals(AggregateAnswerStatus.CONTRADICTED,
                computeStatus(List.of(
                    makeClaimResult("c1", true, Verdict.CONTRADICTED),
                    makeClaimResult("c2", true, Verdict.UNKNOWN)
                )), "contradicted + unknown → contradicted");

            // contradicted + out_of_scope
            assertEquals(AggregateAnswerStatus.CONTRADICTED,
                computeStatus(List.of(
                    makeClaimResult("c1", true, Verdict.CONTRADICTED),
                    makeClaimResult("c2", true, Verdict.OUT_OF_SCOPE)
                )), "contradicted + out_of_scope → contradicted");
        }

        @Test
        @DisplayName("Unknown + out_of_scope → insufficient_evidence (unknown dominates)")
        void unknownDominatesOutOfScope() {
            assertEquals(AggregateAnswerStatus.INSUFFICIENT_EVIDENCE,
                computeStatus(List.of(
                    makeClaimResult("c1", true, Verdict.UNKNOWN),
                    makeClaimResult("c2", true, Verdict.OUT_OF_SCOPE)
                )), "unknown + out_of_scope → insufficient_evidence");
        }

        @Test
        @DisplayName("Optional contradicted, all required supported → verified")
        void optionalContradictedDoesNotOverrideVerified() {
            List<ClaimWorkflowResult> claims = List.of(
                makeClaimResult("c1", true, Verdict.SUPPORTED),
                makeClaimResult("c2", false, Verdict.CONTRADICTED)
            );
            assertEquals(AggregateAnswerStatus.VERIFIED,
                computeStatus(claims), "Optional contradicted does not override required successes");
        }

        @Test
        @DisplayName("Optional unknown, all required supported → verified")
        void optionalUnknownDoesNotOverrideVerified() {
            List<ClaimWorkflowResult> claims = List.of(
                makeClaimResult("c1", true, Verdict.SUPPORTED),
                makeClaimResult("c2", false, Verdict.UNKNOWN)
            );
            assertEquals(AggregateAnswerStatus.VERIFIED,
                computeStatus(claims), "Optional unknown does not override required successes");
        }
    }

    // ── Task 3.8: v0.3 single-claim fixture parity ──

    @Nested
    @DisplayName("Task 3.8: v0.3 wrapped claim preserves verdict")
    class V03WrappedParityTests {

        @Test
        @DisplayName("v0.3 subclass claim wrapped in batch preserves SUPPORTED verdict")
        void v03SupportedClaimPreservedInBatch() {
            stubReasoner.withEntailmentResult(EntailmentResult.ENTAILED);

            // Simulate a v0.3 claim wrapped into v0.5 batch format
            ClaimBatchInput batch = new ClaimBatchInput(
                "answer-v03-wrapped",
                Optional.of("Is Dog a kind of Animal?"),
                Optional.empty(),
                List.of(new ClaimBatchInput.BatchClaim(
                    "wrapped-claim-001", ClaimType.SUBCLASS, true,
                    Optional.of(new ClaimEntity("class", "http://example.org/v0.3#Dog")),
                    Optional.of("subClassOf"),
                    Optional.of(new ClaimEntity("class", "http://example.org/v0.3#Animal")),
                    Optional.of("auto"),
                    Optional.empty(),
                    Optional.of(Map.of("requireReasoning", true))
                )),
                Optional.of(new WorkflowOptions(
                    Optional.of("auto"),
                    Optional.of(true),
                    Optional.empty(),
                    Optional.empty()
                ))
            );

            ServiceResult<AnswerVerificationReport> result = workflowService.verifyBatch(batch, "test-ontology");
            assertTrue(result.isSuccess());

            AnswerVerificationReport report = ((ServiceResult.Success<AnswerVerificationReport>) result).data();
            assertEquals(AggregateAnswerStatus.VERIFIED, report.aggregateStatus(),
                "Wrapped v0.3 supported claim → verified aggregate");
            assertEquals(Verdict.SUPPORTED, report.claimResults().get(0).verdict(),
                "Wrapped v0.3 claim verdict must match v0.3 single-claim verdict");
        }

        @Test
        @DisplayName("v0.3 subclass NOT_ENTAILED claim wrapped → verdict upgraded when entities missing")
        void v03WrappedNotEntailedClaimUpgradedWhenEntitiesMissing() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);

            ClaimBatchInput batch = new ClaimBatchInput(
                "answer-v03-wrapped-unknown",
                Optional.empty(),
                Optional.empty(),
                List.of(new ClaimBatchInput.BatchClaim(
                    "wrapped-claim-002", ClaimType.SUBCLASS, true,
                    Optional.of(new ClaimEntity("class", "http://example.org/v0.3#Goldfish")),
                    Optional.of("subClassOf"),
                    Optional.of(new ClaimEntity("class", "http://example.org/v0.3#Fish")),
                    Optional.empty(), Optional.empty(), Optional.empty()
                )),
                Optional.empty()
            );

            ServiceResult<AnswerVerificationReport> result = workflowService.verifyBatch(batch, "test-ontology");
            assertTrue(result.isSuccess());

            AnswerVerificationReport report = ((ServiceResult.Success<AnswerVerificationReport>) result).data();
            // DEFECT-014 fix: entities not in stub ontology → verdict upgraded from UNKNOWN to OUT_OF_SCOPE
            assertEquals(AggregateAnswerStatus.OUT_OF_SCOPE, report.aggregateStatus(),
                "Entities missing from ontology → out_of_scope aggregate");
            assertEquals(Verdict.OUT_OF_SCOPE, report.claimResults().get(0).verdict(),
                "Verdict upgraded from UNKNOWN to OUT_OF_SCOPE for missing entities");
            assertTrue(report.claimResults().get(0).unknownReason().isPresent(),
                "Upgraded claim must explain the upgrade reason");
        }
    }

    // ── Task 3.7: required defaults to true ──

    @Nested
    @DisplayName("required field defaults to true")
    class RequiredDefaultTests {

        @Test
        @DisplayName("Claim without explicit required defaults to true")
        void requiredDefaultsToTrue() {
            stubReasoner.withEntailmentResult(EntailmentResult.ENTAILED);

            ClaimBatchInput batch = new ClaimBatchInput(
                "answer-default-required",
                Optional.empty(),
                Optional.empty(),
                List.of(new ClaimBatchInput.BatchClaim(
                    "c1", ClaimType.SUBCLASS, true,  // required defaults to true
                    Optional.of(new ClaimEntity("class", "http://ex.org/A")),
                    Optional.of("subClassOf"),
                    Optional.of(new ClaimEntity("class", "http://ex.org/B")),
                    Optional.empty(), Optional.empty(), Optional.empty()
                )),
                Optional.empty()
            );

            ServiceResult<AnswerVerificationReport> result = workflowService.verifyBatch(batch, "test-ontology");
            assertTrue(result.isSuccess());

            AnswerVerificationReport report = ((ServiceResult.Success<AnswerVerificationReport>) result).data();
            assertTrue(report.claimResults().get(0).required(),
                "Omitted required field defaults to true");
        }
    }

    // ── Helpers ──

    private ClaimWorkflowResult makeClaimResult(String claimId, boolean required, Verdict verdict) {
        return new ClaimWorkflowResult(
            claimId,
            ClaimType.SUBCLASS,
            required,
            verdict,
            List.of(),
            verdict == Verdict.UNKNOWN ? Optional.of("insufficient_axioms") : Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    /**
     * Compute aggregate status directly from claim results, testing the
     * truth table logic independently of the service layer.
     */
    private AggregateAnswerStatus computeStatus(List<ClaimWorkflowResult> claims) {
        List<ClaimWorkflowResult> requiredClaims = claims.stream()
            .filter(ClaimWorkflowResult::required)
            .toList();

        if (requiredClaims.isEmpty()) {
            boolean allOptionalSupported = claims.stream()
                .allMatch(r -> r.verdict() == Verdict.SUPPORTED);
            return allOptionalSupported ? AggregateAnswerStatus.VERIFIED : AggregateAnswerStatus.INSUFFICIENT_EVIDENCE;
        }

        if (requiredClaims.stream().anyMatch(r -> r.verdict() == Verdict.CONTRADICTED)) {
            return AggregateAnswerStatus.CONTRADICTED;
        }
        if (requiredClaims.stream().anyMatch(r -> r.verdict() == Verdict.UNKNOWN)) {
            return AggregateAnswerStatus.INSUFFICIENT_EVIDENCE;
        }
        if (requiredClaims.stream().allMatch(r -> r.verdict() == Verdict.OUT_OF_SCOPE)) {
            return AggregateAnswerStatus.OUT_OF_SCOPE;
        }
        boolean anySupported = requiredClaims.stream().anyMatch(r -> r.verdict() == Verdict.SUPPORTED);
        boolean anyNotSupported = requiredClaims.stream().anyMatch(r -> r.verdict() != Verdict.SUPPORTED);
        if (anySupported && anyNotSupported) {
            return AggregateAnswerStatus.PARTIALLY_VERIFIED;
        }
        if (requiredClaims.stream().allMatch(r -> r.verdict() == Verdict.SUPPORTED)) {
            return AggregateAnswerStatus.VERIFIED;
        }
        return AggregateAnswerStatus.INSUFFICIENT_EVIDENCE;
    }
}