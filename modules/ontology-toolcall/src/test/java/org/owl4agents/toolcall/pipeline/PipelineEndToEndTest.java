package org.owl4agents.toolcall.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import org.owl4agents.toolcall.RiskLevel;
import org.owl4agents.toolcall.ToolCallValidationReport;
import org.owl4agents.toolcall.ToolContractRegistry;
import org.owl4agents.toolcall.ValidationDecision;
import org.owl4agents.validation.ClaimWorkflowService;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.apibinding.OWLManager;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PL-001 / Task 7.6: End-to-end tests for {@link ToolCallValidationPipeline}.
 *
 * <p>Covers the three scenarios mandated by the
 * {@code toolcall-validation-pipeline} spec "Pipeline End-to-End Tests":</p>
 * <ol>
 *   <li><strong>Dangerous call rejected</strong> — {@code unlock_door} with
 *       valid arguments forces {@link ValidationDecision#REQUEST_CONFIRMATION}
 *       via the high-risk blacklist (design D14).</li>
 *   <li><strong>Normal error safely repaired</strong> — {@code set_temperature}
 *       with a missing required argument produces
 *       {@link ValidationDecision#AUTO_REPAIR} with a non-empty
 *       {@code repairSpace}.</li>
 *   <li><strong>Normal call passes</strong> — {@code set_temperature} with
 *       valid arguments produces {@link ValidationDecision#EXECUTE}.</li>
 * </ol>
 *
 * <p>Test doubles: the pipeline is constructed with a fake
 * {@link TransientOntologyOverlayService} (returns an empty overlay) and a
 * fake {@link ClaimWorkflowService} (returns VERIFIED). SHACL services are
 * null so stage 7 is skipped. The {@link ToolContractRegistry} is real,
 * backed by a {@link TempDir}.</p>
 */
@DisplayName("PL-001 Pipeline End-to-End (Task 7.6)")
class PipelineEndToEndTest {

    @TempDir
    Path tempDir;

    private ToolContractRegistry registry;
    private ToolCallValidationPipeline pipeline;

    @BeforeEach
    void setUp() throws Exception {
        Path contractsDir = tempDir.resolve("contracts");
        java.nio.file.Files.createDirectories(contractsDir);
        registry = new ToolContractRegistry(contractsDir);

        // Register the set_temperature contract (low risk, no SHACL, no targetClass).
        registerContract("set_temperature", """
            {
              "toolName": "set_temperature",
              "inputSchema": {
                "type": "object",
                "properties": {
                  "targetTemperature": {"type": "integer", "minimum": 16, "maximum": 30}
                },
                "required": ["targetTemperature"]
              },
              "targetClass": null,
              "requiredCapabilities": [],
              "requiredStates": [],
              "effects": ["changesState(thermostat, targetTemperature)"],
              "riskLevel": "low",
              "requiredPermission": null,
              "shapeSetIds": []
            }
            """);

        // Register the unlock_door contract (low risk per contract, but the
        // blacklist forces REQUEST_CONFIRMATION regardless).
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
              "effects": ["unlocks(door)"],
              "riskLevel": "low",
              "requiredPermission": null,
              "shapeSetIds": []
            }
            """);

        pipeline = ToolCallValidationPipeline.builder()
            .toolContractRegistry(registry)
            .overlayService(new FakeOverlayService())
            .claimWorkflowService(new FakeClaimWorkflowService(AggregateAnswerStatus.VERIFIED))
            // shapeRegistry and shaclValidationService are intentionally null:
            // stage 7 is skipped when no SHACL services are configured.
            .build();
    }

    // ── Scenario 1: Dangerous call rejected ──

    @Test
    @DisplayName("unlock_door with valid args -> REQUEST_CONFIRMATION (high-risk blacklist)")
    void dangerousCallRejected() {
        ToolCallCandidate candidate = new ToolCallCandidate(
            "call-dangerous-1",
            Optional.empty(),
            Optional.empty(),
            "unlock_door",
            Optional.empty(),
            Map.of("doorId", "\"front-door\""),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

        ToolCallValidationReport report = pipeline.validate(
            candidate, "smart-home", buildSnapshot());

        assertEquals(ValidationDecision.REQUEST_CONFIRMATION, report.decision(),
            "unlock_door is blacklisted -> must force REQUEST_CONFIRMATION");
        assertTrue(report.riskLevel().isHighRisk(),
            "Blacklisted action riskLevel must be high or critical");
        assertNotEquals(ValidationDecision.EXECUTE, report.decision(),
            "Blacklisted action must NOT execute even when all stages pass");
        // Stages 1-10 should all have timing entries.
        assertEquals(10, report.perStageTiming().size(),
            "All 10 stage timing entries must be present");
    }

    // ── Scenario 2: Normal error safely repaired ──

    @Test
    @DisplayName("set_temperature with missing targetTemperature -> AUTO_REPAIR + repairSpace")
    void normalErrorSafelyRepaired() {
        ToolCallCandidate candidate = new ToolCallCandidate(
            "call-repair-1",
            Optional.empty(),
            Optional.empty(),
            "set_temperature",
            Optional.empty(),
            Map.of(),  // missing targetTemperature
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

        ToolCallValidationReport report = pipeline.validate(
            candidate, "smart-home", buildSnapshot());

        // Stage 3 short-circuits with AUTO_REPAIR (or CLARIFY) per the
        // suggestShortCircuitDecision of the JSON Schema validator.
        assertTrue(
            report.decision() == ValidationDecision.AUTO_REPAIR
                || report.decision() == ValidationDecision.CLARIFY,
            "Missing required argument should produce AUTO_REPAIR or CLARIFY");
        assertFalse(report.jsonSchemaViolations().isEmpty(),
            "JSON Schema violations must be recorded");
        assertFalse(report.repairSpace().isEmpty(),
            "repairSpace must be populated for the repair path");
        // Stages 4-9 (owlBatchMs, shaclMs) must be 0 (skipped).
        assertEquals(0L, report.perStageTiming().get("owlBatchMs"),
            "OWL batch must be skipped when JSON Schema fails");
        assertEquals(0L, report.perStageTiming().get("shaclMs"),
            "SHACL must be skipped when JSON Schema fails");
    }

    // ── Scenario 3: Normal call passes ──

    @Test
    @DisplayName("set_temperature with valid targetTemperature -> EXECUTE")
    void normalCallPasses() {
        ToolCallCandidate candidate = new ToolCallCandidate(
            "call-ok-1",
            Optional.empty(),
            Optional.empty(),
            "set_temperature",
            Optional.empty(),
            Map.of("targetTemperature", "22"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

        ToolCallValidationReport report = pipeline.validate(
            candidate, "smart-home", buildSnapshot());

        assertEquals(ValidationDecision.EXECUTE, report.decision(),
            "Valid call with low risk should EXECUTE");
        assertEquals(RiskLevel.LOW, report.riskLevel(),
            "set_temperature contract riskLevel is low");
        assertTrue(report.jsonSchemaViolations().isEmpty(),
            "No JSON Schema violations for a valid call");
        assertTrue(report.shaclViolations().isEmpty(),
            "No SHACL violations (SHACL skipped — no shapeSetIds)");
    }

    // ── Scenario 4: Repaired dangerous call still requires confirmation ──

    @Test
    @DisplayName("unlock_door with missing arg -> REQUEST_CONFIRMATION (override AUTO_REPAIR)")
    void repairedDangerousCallStillRequiresConfirmation() {
        ToolCallCandidate candidate = new ToolCallCandidate(
            "call-dangerous-repair-1",
            Optional.empty(),
            Optional.empty(),
            "unlock_door",
            Optional.empty(),
            Map.of(),  // missing doorId
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

        ToolCallValidationReport report = pipeline.validate(
            candidate, "smart-home", buildSnapshot());

        // The high-risk override forces REQUEST_CONFIRMATION even though
        // the JSON Schema failure would normally produce AUTO_REPAIR.
        assertEquals(ValidationDecision.REQUEST_CONFIRMATION, report.decision(),
            "High-risk + repairable error must force REQUEST_CONFIRMATION");
        assertTrue(report.riskLevel().isHighRisk(),
            "Risk level must be high/critical for blacklisted action");
        assertFalse(report.repairSpace().isEmpty(),
            "repairSpace must still be populated for the confirmer's reference");
    }

    // ── Helpers ──

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

    // ── Test doubles ──

    /**
     * Fake overlay service that returns a transient overlay wrapping an
     * empty {@link OWLOntology}. Sufficient for tests that don't assert
     * on overlay contents.
     */
    private static final class FakeOverlayService implements TransientOntologyOverlayService {
        @Override
        public ServiceResult<TransientOverlay> createOverlay(
                OntologyId baseOntology,
                Collection<OWLAxiom> dynamicAxioms,
                OverlayOptions options) {
            try {
                OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
                OWLOntology ont = mgr.createOntology(
                    IRI.create("http://example.org/test-overlay-" + UUID.randomUUID()));
                return ServiceResult.success(new FakeOverlay(ont, baseOntology),
                    ResultMetadata.empty());
            } catch (Exception e) {
                return ServiceResult.error(
                    org.owl4agents.core.ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED,
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

    /**
     * Fake {@link TransientOverlay} that wraps an empty ontology and
     * does nothing on {@link #release()}.
     */
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
     * Fake {@link ClaimWorkflowService} that returns a configurable
     * {@link AggregateAnswerStatus}. Passes null for all parent-constructor
     * dependencies and overrides {@link #verifyBatchAgainstOntology} so
     * they're never used.
     */
    private static final class FakeClaimWorkflowService extends ClaimWorkflowService {
        private final AggregateAnswerStatus status;

        FakeClaimWorkflowService(AggregateAnswerStatus status) {
            super(null, null, null, null, null);
            this.status = status;
        }

        @Override
        public ServiceResult<AnswerVerificationReport> verifyBatchAgainstOntology(
                ClaimBatchInput batch, OWLOntology ontology, String ontologyId) {
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
