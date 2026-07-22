package org.owl4agents.toolcall.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ResultMetadata;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.AggregateAnswerStatus;
import org.owl4agents.core.model.AnswerVerificationReport;
import org.owl4agents.core.model.ClaimBatchInput;
import org.owl4agents.overlay.EnvironmentSnapshot;
import org.owl4agents.overlay.OverlayOptions;
import org.owl4agents.overlay.ToolCallCandidate;
import org.owl4agents.overlay.TransientOntologyOverlayService;
import org.owl4agents.overlay.TransientOverlay;
import org.owl4agents.toolcall.ToolCallExecutionStatus;
import org.owl4agents.toolcall.ToolCallValidationReport;
import org.owl4agents.toolcall.ToolContractRegistry;
import org.owl4agents.toolcall.ValidationDecision;
import org.owl4agents.validation.ClaimWorkflowService;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PL-002 / Task 7.8: Integration tests for the pipeline short-circuit
 * rules. Each test drives a short-circuit through the full pipeline
 * (not just the {@link ShortCircuitStrategy} static methods), verifying
 * the stage skips, decision, and high-risk override behavior.
 *
 * <p>Coverage (at least 1 test per short-circuit rule):</p>
 * <ol>
 *   <li>Malformed JSON (stage 1) → SYSTEM_ERROR via {@code validateRawJson}</li>
 *   <li>Unknown tool name (stage 2) → REJECT</li>
 *   <li>Missing required parameter (stage 3) → AUTO_REPAIR</li>
 *   <li>Invalid snapshot / null snapshot (stage 4) → SYSTEM_ERROR</li>
 *   <li>Overlay creation failure (stage 4) → SYSTEM_ERROR</li>
 *   <li>OWL contradiction (stage 6) → REJECT</li>
 *   <li>Reasoner timeout (stage 6) → SYSTEM_ERROR with TIMEOUT status</li>
 *   <li>High-risk override: high-risk + repairable error → REQUEST_CONFIRMATION</li>
 * </ol>
 */
@DisplayName("PL-002 Pipeline Short-Circuit Integration (Task 7.8)")
class PipelineShortCircuitIntegrationTest {

    @TempDir
    Path tempDir;

    private ToolContractRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        Path contractsDir = tempDir.resolve("contracts");
        java.nio.file.Files.createDirectories(contractsDir);
        registry = new ToolContractRegistry(contractsDir);

        registerContract("set_temperature", """
            {
              "toolName": "set_temperature",
              "inputSchema": {
                "type": "object",
                "properties": {
                  "targetTemperature": {"type": "integer"}
                },
                "required": ["targetTemperature"]
              },
              "targetClass": null,
              "requiredCapabilities": [],
              "requiredStates": [],
              "effects": [],
              "riskLevel": "low",
              "requiredPermission": null,
              "shapeSetIds": []
            }
            """);

        registerContract("unlock_door", """
            {
              "toolName": "unlock_door",
              "inputSchema": {
                "type": "object",
                "properties": {
                  "doorId": {"type": "string"}
                },
                "required": ["doorId"]
              },
              "targetClass": null,
              "requiredCapabilities": [],
              "requiredStates": [],
              "effects": [],
              "riskLevel": "low",
              "requiredPermission": null,
              "shapeSetIds": []
            }
            """);
    }

    // ── Rule 1: Malformed JSON short-circuits at stage 1 ──

    @Test
    @DisplayName("Malformed JSON -> SYSTEM_ERROR at stage 1 (Parse)")
    void malformedJsonShortCircuitsAtStage1() {
        ToolCallValidationPipeline pipeline = buildPipeline(verifiedWorkflow(), successOverlay());
        ToolCallValidationReport report = pipeline.validateRawJson(
            "{ this is not valid json", "smart-home", buildSnapshot());

        assertEquals(ValidationDecision.SYSTEM_ERROR, report.decision(),
            "Malformed JSON must produce SYSTEM_ERROR");
        assertEquals(ToolCallExecutionStatus.ERROR, report.executionStatus(),
            "Malformed JSON must set executionStatus=error");
        // Stages 2-9 should be 0 (skipped). Stage 1 (parseToolCallMs) and
        // stage 10 (structuredReportMs) should be present.
        assertEquals(0L, report.perStageTiming().get("loadToolContractMs"),
            "Stage 2 must be skipped on parse failure");
        assertEquals(0L, report.perStageTiming().get("owlBatchMs"),
            "Stage 6 must be skipped on parse failure");
        assertEquals(0L, report.perStageTiming().get("shaclMs"),
            "Stage 7 must be skipped on parse failure");
    }

    // ── Rule 2: Unknown tool name short-circuits at stage 2 ──

    @Test
    @DisplayName("Unknown tool name -> REJECT at stage 2 (Load Contract)")
    void unknownToolShortCircuitsAtStage2() {
        ToolCallValidationPipeline pipeline = buildPipeline(verifiedWorkflow(), successOverlay());
        ToolCallCandidate candidate = new ToolCallCandidate(
            "call-1", Optional.empty(), Optional.empty(),
            "nonexistent_tool", Optional.empty(), Map.of(),
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        ToolCallValidationReport report = pipeline.validate(
            candidate, "smart-home", buildSnapshot());

        assertEquals(ValidationDecision.REJECT, report.decision(),
            "Unknown tool name must produce REJECT");
        assertEquals(ToolCallExecutionStatus.ERROR, report.executionStatus(),
            "Unknown tool name must set executionStatus=error");
        // Stage 3 (jsonSchemaMs) and later must be 0.
        assertEquals(0L, report.perStageTiming().get("jsonSchemaMs"),
            "Stage 3 must be skipped on unknown tool name");
        assertEquals(0L, report.perStageTiming().get("buildOverlayMs"),
            "Stage 4 must be skipped on unknown tool name");
    }

    // ── Rule 3: Missing required parameter short-circuits at stage 3 ──

    @Test
    @DisplayName("Missing required parameter -> AUTO_REPAIR/CLARIFY at stage 3")
    void missingRequiredParamShortCircuitsAtStage3() {
        ToolCallValidationPipeline pipeline = buildPipeline(verifiedWorkflow(), successOverlay());
        ToolCallCandidate candidate = new ToolCallCandidate(
            "call-1", Optional.empty(), Optional.empty(),
            "set_temperature", Optional.empty(), Map.of(),  // missing targetTemperature
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        ToolCallValidationReport report = pipeline.validate(
            candidate, "smart-home", buildSnapshot());

        assertTrue(
            report.decision() == ValidationDecision.AUTO_REPAIR
                || report.decision() == ValidationDecision.CLARIFY,
            "Missing required parameter should produce AUTO_REPAIR or CLARIFY");
        assertFalse(report.jsonSchemaViolations().isEmpty(),
            "JSON Schema violations must be recorded");
        // Stages 4-9 must be 0 (skipped — reasoner not started).
        assertEquals(0L, report.perStageTiming().get("buildOverlayMs"),
            "Stage 4 must be skipped on JSON Schema failure");
        assertEquals(0L, report.perStageTiming().get("owlBatchMs"),
            "Stage 6 (OWL) must be skipped — reasoner not started");
        assertEquals(0L, report.perStageTiming().get("shaclMs"),
            "Stage 7 (SHACL) must be skipped");
    }

    // ── Rule 4: Invalid (null) snapshot short-circuits at stage 4 ──

    @Test
    @DisplayName("Null snapshot -> SYSTEM_ERROR at stage 4 (Build Overlay)")
    void nullSnapshotShortCircuitsAtStage4() {
        ToolCallValidationPipeline pipeline = buildPipeline(verifiedWorkflow(), successOverlay());
        ToolCallCandidate candidate = new ToolCallCandidate(
            "call-1", Optional.empty(), Optional.empty(),
            "set_temperature", Optional.empty(),
            Map.of("targetTemperature", "22"),
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        ToolCallValidationReport report = pipeline.validate(
            candidate, "smart-home", null);

        assertEquals(ValidationDecision.SYSTEM_ERROR, report.decision(),
            "Null snapshot must produce SYSTEM_ERROR");
        assertEquals(ToolCallExecutionStatus.ERROR, report.executionStatus(),
            "Null snapshot must set executionStatus=error");
        // Stages 5-9 must be 0.
        assertEquals(0L, report.perStageTiming().get("claimDecompositionMs"),
            "Stage 5 must be skipped on snapshot failure");
        assertEquals(0L, report.perStageTiming().get("owlBatchMs"),
            "Stage 6 must be skipped on snapshot failure");
    }

    // ── Rule 5: Overlay creation failure short-circuits at stage 4 ──

    @Test
    @DisplayName("Overlay creation failure -> SYSTEM_ERROR at stage 4")
    void overlayCreationFailureShortCircuitsAtStage4() {
        ToolCallValidationPipeline pipeline = buildPipeline(
            verifiedWorkflow(),
            failingOverlay(ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED,
                "isolated manager allocation failed"));
        ToolCallCandidate candidate = new ToolCallCandidate(
            "call-1", Optional.empty(), Optional.empty(),
            "set_temperature", Optional.empty(),
            Map.of("targetTemperature", "22"),
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        ToolCallValidationReport report = pipeline.validate(
            candidate, "smart-home", buildSnapshot());

        assertEquals(ValidationDecision.SYSTEM_ERROR, report.decision(),
            "Overlay creation failure must produce SYSTEM_ERROR");
        assertEquals(ToolCallExecutionStatus.ERROR, report.executionStatus());
        // Stages 5-9 must be 0.
        assertEquals(0L, report.perStageTiming().get("owlBatchMs"),
            "Stage 6 must be skipped on overlay failure");
    }

    // ── Rule 6: OWL contradiction short-circuits at stage 6 ──

    @Test
    @DisplayName("OWL contradiction -> REJECT at stage 6 (SHACL skipped)")
    void owlContradictionShortCircuitsAtStage6() {
        ToolCallValidationPipeline pipeline = buildPipeline(
            contradictedWorkflow(),
            successOverlay());
        ToolCallCandidate candidate = new ToolCallCandidate(
            "call-1", Optional.empty(), Optional.empty(),
            "set_temperature", Optional.empty(),
            Map.of("targetTemperature", "22"),
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        ToolCallValidationReport report = pipeline.validate(
            candidate, "smart-home", buildSnapshot());

        assertEquals(ValidationDecision.REJECT, report.decision(),
            "OWL contradiction must produce REJECT");
        // Stage 7 (SHACL) must be skipped because running SHACL on an
        // inconsistent ontology is meaningless.
        assertEquals(0L, report.perStageTiming().get("shaclMs"),
            "Stage 7 (SHACL) must be skipped on OWL contradiction");
    }

    // ── Rule 7: Reasoner timeout short-circuits at stage 6 ──

    @Test
    @DisplayName("Reasoner timeout -> SYSTEM_ERROR with TIMEOUT executionStatus")
    void reasonerTimeoutShortCircuitsAtStage6() {
        ToolCallValidationPipeline pipeline = buildPipeline(
            timeoutWorkflow(),
            successOverlay());
        ToolCallCandidate candidate = new ToolCallCandidate(
            "call-1", Optional.empty(), Optional.empty(),
            "set_temperature", Optional.empty(),
            Map.of("targetTemperature", "22"),
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        ToolCallValidationReport report = pipeline.validate(
            candidate, "smart-home", buildSnapshot());

        assertEquals(ValidationDecision.SYSTEM_ERROR, report.decision(),
            "Reasoner timeout must produce SYSTEM_ERROR (not UNKNOWN)");
        assertEquals(ToolCallExecutionStatus.TIMEOUT, report.executionStatus(),
            "Reasoner timeout must set executionStatus=timeout");
        assertNotEquals(ValidationDecision.EXECUTE, report.decision(),
            "Reasoner timeout must NOT allow EXECUTE");
        // Stage 7 (SHACL) must be skipped.
        assertEquals(0L, report.perStageTiming().get("shaclMs"),
            "Stage 7 must be skipped after reasoner timeout");
    }

    // ── Rule 8: High-risk override forces REQUEST_CONFIRMATION ──

    @Test
    @DisplayName("High-risk + missing required arg -> REQUEST_CONFIRMATION (override AUTO_REPAIR)")
    void highRiskOverrideForcesConfirmation() {
        ToolCallValidationPipeline pipeline = buildPipeline(verifiedWorkflow(), successOverlay());
        ToolCallCandidate candidate = new ToolCallCandidate(
            "call-1", Optional.empty(), Optional.empty(),
            "unlock_door",  // blacklisted high-risk action
            Optional.empty(),
            Map.of(),  // missing doorId (would normally produce AUTO_REPAIR)
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        ToolCallValidationReport report = pipeline.validate(
            candidate, "smart-home", buildSnapshot());

        // The high-risk override (applied by PipelineContext.resolvedShortCircuitDecision)
        // forces REQUEST_CONFIRMATION instead of AUTO_REPAIR.
        assertEquals(ValidationDecision.REQUEST_CONFIRMATION, report.decision(),
            "High-risk + repairable error must force REQUEST_CONFIRMATION");
        assertTrue(report.riskLevel().isHighRisk(),
            "Risk level must be high/critical for blacklisted action");
        assertFalse(report.repairSpace().isEmpty(),
            "repairSpace must still be populated for the confirmer's reference");
    }

    // ── Helpers ──

    private ToolCallValidationPipeline buildPipeline(ClaimWorkflowService workflow,
                                                      TransientOntologyOverlayService overlay) {
        return ToolCallValidationPipeline.builder()
            .toolContractRegistry(registry)
            .overlayService(overlay)
            .claimWorkflowService(workflow)
            .build();
    }

    private void registerContract(String toolName, String json) {
        try {
            java.nio.file.Files.writeString(
                tempDir.resolve("contracts").resolve(toolName + ".json"),
                json);
        } catch (java.io.IOException e) {
            fail("Cannot write contract " + toolName + ": " + e.getMessage());
        }
    }

    private static EnvironmentSnapshot buildSnapshot() {
        return new EnvironmentSnapshot(
            UUID.randomUUID().toString(),
            Instant.now(),
            "test",
            0L,
            "test-checksum",
            List.of(), List.of(), List.of());
    }

    private static TransientOntologyOverlayService successOverlay() {
        return new FakeOverlayService(null, null);
    }

    private static TransientOntologyOverlayService failingOverlay(ErrorCode code, String message) {
        return new FakeOverlayService(code, message);
    }

    private static ClaimWorkflowService verifiedWorkflow() {
        return new FakeClaimWorkflowService(AggregateAnswerStatus.VERIFIED, null);
    }

    private static ClaimWorkflowService contradictedWorkflow() {
        return new FakeClaimWorkflowService(AggregateAnswerStatus.CONTRADICTED, null);
    }

    private static ClaimWorkflowService timeoutWorkflow() {
        return new FakeClaimWorkflowService(null, ErrorCode.REASONER_TIMEOUT);
    }

    // ── Test doubles ──

    /**
     * Fake overlay service. When {@code failCode} is null, returns a
     * successful overlay wrapping an empty ontology. Otherwise returns
     * the configured error.
     */
    private static final class FakeOverlayService implements TransientOntologyOverlayService {
        private final ErrorCode failCode;
        private final String failMessage;

        FakeOverlayService(ErrorCode failCode, String failMessage) {
            this.failCode = failCode;
            this.failMessage = failMessage;
        }

        @Override
        public ServiceResult<TransientOverlay> createOverlay(
                OntologyId baseOntology,
                Collection<OWLAxiom> dynamicAxioms,
                OverlayOptions options) {
            if (failCode != null) {
                return ServiceResult.error(failCode, failMessage);
            }
            try {
                OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
                OWLOntology ont = mgr.createOntology(
                    IRI.create("http://example.org/test-overlay-" + UUID.randomUUID()));
                return ServiceResult.success(new FakeOverlay(ont, baseOntology),
                    ResultMetadata.empty());
            } catch (Exception e) {
                return ServiceResult.error(
                    ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED,
                    "FakeOverlayService failed: " + e.getMessage());
            }
        }

        @Override
        public ServiceResult<TransientOverlay> createOverlay(
                OntologyId baseOntology,
                EnvironmentSnapshot snapshot,
                OverlayOptions options) {
            return createOverlay(baseOntology, List.of(), options);
        }
    }

    private static final class FakeOverlay implements TransientOverlay {
        private final OWLOntology ontology;
        private final OntologyId baseOntologyId;

        FakeOverlay(OWLOntology ontology, OntologyId baseOntologyId) {
            this.ontology = ontology;
            this.baseOntologyId = baseOntologyId;
        }

        @Override public String overlayId() { return "fake-overlay"; }
        @Override public OntologyId baseOntologyId() { return baseOntologyId; }
        @Override public Collection<OWLAxiom> dynamicAxioms() { return List.of(); }
        @Override public String snapshotId() { return "fake-snapshot"; }
        @Override public Instant createdAt() { return Instant.now(); }
        @Override public OWLOntology ontology() { return ontology; }
        @Override public EnvironmentSnapshot snapshot() { return null; }
        @Override public void release() { /* no-op */ }
    }

    /**
     * Fake {@link ClaimWorkflowService}. When {@code timeoutCode} is
     * non-null, returns that error code. Otherwise returns a report with
     * the configured {@link AggregateAnswerStatus}.
     */
    private static final class FakeClaimWorkflowService extends ClaimWorkflowService {
        private final AggregateAnswerStatus status;
        private final ErrorCode timeoutCode;

        FakeClaimWorkflowService(AggregateAnswerStatus status, ErrorCode timeoutCode) {
            super(null, null, null, null, null);
            this.status = status;
            this.timeoutCode = timeoutCode;
        }

        @Override
        public ServiceResult<AnswerVerificationReport> verifyBatchAgainstOntology(
                ClaimBatchInput batch, OWLOntology ontology, String ontologyId) {
            if (timeoutCode != null) {
                return ServiceResult.error(timeoutCode, "reasoner timeout (fake)");
            }
            AnswerVerificationReport report = new AnswerVerificationReport(
                batch == null ? "fake" : batch.answerId(),
                ontologyId,
                status,
                List.of(),
                Optional.empty(),
                Optional.empty());
            return ServiceResult.success(report, ResultMetadata.empty());
        }
    }
}
