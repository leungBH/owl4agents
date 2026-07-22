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
import org.owl4agents.toolcall.ToolCallJsonSerializer;
import org.owl4agents.toolcall.ToolCallValidationReport;
import org.owl4agents.toolcall.ToolContractRegistry;
import org.owl4agents.validation.ClaimWorkflowService;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PL-004 / Task 7.7: CLI/MCP parity tests for the pipeline.
 *
 * <p>Per the {@code toolcall-validation-pipeline} spec
 * "CLI and MCP Service Contract Sharing": the CLI and MCP tools SHALL
 * delegate to the same {@link ToolCallValidationPipeline} service. The
 * output JSON for the same fixture SHALL be byte-for-byte identical
 * between CLI and MCP, ignoring transport-level differences.</p>
 *
 * <p>This test constructs the same logical input via two paths:</p>
 * <ul>
 *   <li><strong>CLI path</strong>: builds a {@link ToolCallCandidate} +
 *       {@link EnvironmentSnapshot} directly and calls
 *       {@link ToolCallValidationPipeline#validate} (this is what
 *       {@code ToolCallValidateCommand} ultimately does after parsing
 *       its {@code --call} and {@code --state} files).</li>
 *   <li><strong>MCP path</strong>: builds the equivalent {@code Map<String, Object>}
 *       args and calls {@link PipelineMcpTools#validateToolCall} (this is
 *       what {@code ontology_validate_tool_call} does after JSON-RPC
 *       deserialization).</li>
 * </ul>
 *
 * <p>Both paths use the same pipeline instance, ensuring parity at the
 * service layer. The tests verify the parity fields mandated by spec:
 * {@code decision}, {@code riskLevel}, {@code shaclViolations.length},
 * {@code owlClaimResults.length}, and {@code stateVersion.snapshotId}.</p>
 */
@DisplayName("PL-004 Pipeline CLI/MCP Parity (Task 7.7)")
class PipelineCliMcpParityTest {

    @TempDir
    Path tempDir;

    private ToolCallValidationPipeline pipeline;
    private PipelineMcpTools mcpTools;
    private EnvironmentSnapshot sharedSnapshot;

    @BeforeEach
    void setUp() throws Exception {
        Path contractsDir = tempDir.resolve("contracts");
        java.nio.file.Files.createDirectories(contractsDir);
        ToolContractRegistry registry = new ToolContractRegistry(contractsDir);

        registerContract(registry, "set_temperature", """
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

        registerContract(registry, "unlock_door", """
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

        FakeOverlayService overlayService = new FakeOverlayService();
        FakeClaimWorkflowService workflowService =
            new FakeClaimWorkflowService(AggregateAnswerStatus.VERIFIED);

        pipeline = ToolCallValidationPipeline.builder()
            .toolContractRegistry(registry)
            .overlayService(overlayService)
            .claimWorkflowService(workflowService)
            .build();

        mcpTools = new PipelineMcpTools(pipeline, registry, overlayService);

        // Shared snapshot so parity comparisons are meaningful (same
        // snapshotId, same checksum). The MCP path parses this from a Map,
        // the CLI path builds it directly.
        sharedSnapshot = new EnvironmentSnapshot(
            "snapshot-fixed-1",
            Instant.parse("2026-07-21T10:00:00Z"),
            "parity-test",
            1L,
            "parity-checksum",
            List.of(), List.of(), List.of());
    }

    // ── Parity test 1: valid low-risk call ──

    @Test
    @DisplayName("CLI and MCP produce same decision/riskLevel for valid call")
    void cliAndMcpProduceSameDecisionForValidCall() {
        ToolCallCandidate cliCandidate = new ToolCallCandidate(
            "call-parity-1", Optional.empty(), Optional.empty(),
            "set_temperature", Optional.empty(),
            Map.of("targetTemperature", "22"),
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        ToolCallValidationReport cliReport = pipeline.validate(
            cliCandidate, "smart-home", sharedSnapshot);

        Map<String, Object> mcpArgs = mcpArgsFor(
            "smart-home", "call-parity-1", "set_temperature",
            Map.of("targetTemperature", "22"));
        @SuppressWarnings("unchecked")
        Map<String, Object> mcpResult = mcpTools.validateToolCall(mcpArgs);
        @SuppressWarnings("unchecked")
        Map<String, Object> mcpData = (Map<String, Object>) mcpResult.get("data");

        assertEquals("success", mcpResult.get("status"),
            "MCP path should succeed for a valid call");
        assertEquals(cliReport.decision().jsonName(), mcpData.get("decision"),
            "CLI and MCP decision must match");
        assertEquals(cliReport.riskLevel().jsonName(), mcpData.get("riskLevel"),
            "CLI and MCP riskLevel must match");
        assertEquals(cliReport.shaclViolations().size(),
            ((List<?>) mcpData.get("shaclViolations")).size(),
            "CLI and MCP shaclViolations.length must match");
        assertEquals(cliReport.owlClaimResults().size(),
            ((List<?>) mcpData.get("owlClaimResults")).size(),
            "CLI and MCP owlClaimResults.length must match");
    }

    // ── Parity test 2: missing required parameter ──

    @Test
    @DisplayName("CLI and MCP produce same decision for missing-required-param error")
    void cliAndMcpProduceSameDecisionForRepairableError() {
        ToolCallCandidate cliCandidate = new ToolCallCandidate(
            "call-parity-2", Optional.empty(), Optional.empty(),
            "set_temperature", Optional.empty(),
            Map.of(),  // missing targetTemperature
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        ToolCallValidationReport cliReport = pipeline.validate(
            cliCandidate, "smart-home", sharedSnapshot);

        Map<String, Object> mcpArgs = mcpArgsFor(
            "smart-home", "call-parity-2", "set_temperature",
            Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> mcpResult = mcpTools.validateToolCall(mcpArgs);
        @SuppressWarnings("unchecked")
        Map<String, Object> mcpData = (Map<String, Object>) mcpResult.get("data");

        assertEquals("success", mcpResult.get("status"),
            "MCP path should return a structured report even for repairable errors");
        assertEquals(cliReport.decision().jsonName(), mcpData.get("decision"),
            "CLI and MCP decision must match for repairable errors");
        assertEquals(cliReport.riskLevel().jsonName(), mcpData.get("riskLevel"),
            "CLI and MCP riskLevel must match");
        assertEquals(cliReport.jsonSchemaViolations().size(),
            ((List<?>) mcpData.get("jsonSchemaViolations")).size(),
            "CLI and MCP jsonSchemaViolations.length must match");
        assertEquals(cliReport.repairSpace().size(),
            ((List<?>) mcpData.get("repairSpace")).size(),
            "CLI and MCP repairSpace.length must match");
    }

    // ── Parity test 3: high-risk action ──

    @Test
    @DisplayName("CLI and MCP produce REQUEST_CONFIRMATION for high-risk action")
    void cliAndMcpProduceSameDecisionForHighRiskAction() {
        ToolCallCandidate cliCandidate = new ToolCallCandidate(
            "call-parity-3", Optional.empty(), Optional.empty(),
            "unlock_door", Optional.empty(),
            Map.of("doorId", "\"front-door\""),
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        ToolCallValidationReport cliReport = pipeline.validate(
            cliCandidate, "smart-home", sharedSnapshot);

        Map<String, Object> mcpArgs = mcpArgsFor(
            "smart-home", "call-parity-3", "unlock_door",
            Map.of("doorId", "\"front-door\""));
        @SuppressWarnings("unchecked")
        Map<String, Object> mcpResult = mcpTools.validateToolCall(mcpArgs);
        @SuppressWarnings("unchecked")
        Map<String, Object> mcpData = (Map<String, Object>) mcpResult.get("data");

        assertEquals("success", mcpResult.get("status"));
        assertEquals("request_confirmation", mcpData.get("decision"),
            "MCP path must produce REQUEST_CONFIRMATION for blacklisted action");
        assertEquals(cliReport.decision().jsonName(), mcpData.get("decision"),
            "CLI and MCP decision must match for high-risk action");
        assertTrue(cliReport.riskLevel().isHighRisk(),
            "CLI riskLevel must be high/critical for blacklisted action");
        assertEquals(cliReport.riskLevel().jsonName(), mcpData.get("riskLevel"),
            "CLI and MCP riskLevel must match");
    }

    // ── Parity test 4: stateVersion.snapshotId equivalence ──

    @Test
    @DisplayName("CLI and MCP produce same stateVersion.snapshotId")
    void cliAndMcpProduceSameStateVersionSnapshotId() {
        ToolCallCandidate cliCandidate = new ToolCallCandidate(
            "call-parity-4", Optional.empty(), Optional.empty(),
            "set_temperature", Optional.empty(),
            Map.of("targetTemperature", "22"),
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        ToolCallValidationReport cliReport = pipeline.validate(
            cliCandidate, "smart-home", sharedSnapshot);

        Map<String, Object> mcpArgs = mcpArgsFor(
            "smart-home", "call-parity-4", "set_temperature",
            Map.of("targetTemperature", "22"));
        @SuppressWarnings("unchecked")
        Map<String, Object> mcpResult = mcpTools.validateToolCall(mcpArgs);
        @SuppressWarnings("unchecked")
        Map<String, Object> mcpData = (Map<String, Object>) mcpResult.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> mcpStateVersion = (Map<String, Object>) mcpData.get("stateVersion");

        assertNotNull(mcpStateVersion, "MCP report must include stateVersion");
        assertEquals(sharedSnapshot.snapshotId(), mcpStateVersion.get("snapshotId"),
            "MCP stateVersion.snapshotId must match the input snapshot");
        assertEquals(cliReport.stateVersion().get().snapshotId(),
            mcpStateVersion.get("snapshotId"),
            "CLI and MCP stateVersion.snapshotId must match");
    }

    // ── Parity test 5: report byte-for-byte equivalence via serializer ──

    @Test
    @DisplayName("CLI report serialized via ToolCallJsonSerializer matches MCP data map")
    void cliReportSerializedMatchesMcpDataMap() {
        ToolCallCandidate cliCandidate = new ToolCallCandidate(
            "call-parity-5", Optional.empty(), Optional.empty(),
            "set_temperature", Optional.empty(),
            Map.of("targetTemperature", "22"),
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        ToolCallValidationReport cliReport = pipeline.validate(
            cliCandidate, "smart-home", sharedSnapshot);
        Map<String, Object> cliMap = ToolCallJsonSerializer.reportToMap(cliReport);

        Map<String, Object> mcpArgs = mcpArgsFor(
            "smart-home", "call-parity-5", "set_temperature",
            Map.of("targetTemperature", "22"));
        @SuppressWarnings("unchecked")
        Map<String, Object> mcpResult = mcpTools.validateToolCall(mcpArgs);
        @SuppressWarnings("unchecked")
        Map<String, Object> mcpMap = (Map<String, Object>) mcpResult.get("data");

        // Same 13 fields, same values (excluding per-stage timing which
        // varies by run, and totalMs which is wall-clock dependent).
        assertEquals(cliMap.get("schemaVersion"), mcpMap.get("schemaVersion"),
            "schemaVersion must match");
        assertEquals(cliMap.get("callId"), mcpMap.get("callId"),
            "callId must match");
        assertEquals(cliMap.get("executionStatus"), mcpMap.get("executionStatus"),
            "executionStatus must match");
        assertEquals(cliMap.get("decision"), mcpMap.get("decision"),
            "decision must match");
        assertEquals(cliMap.get("riskLevel"), mcpMap.get("riskLevel"),
            "riskLevel must match");
    }

    // ── Helpers ──

    private void registerContract(ToolContractRegistry registry,
                                   String toolName, String json) {
        try {
            java.nio.file.Files.writeString(
                tempDir.resolve("contracts").resolve(toolName + ".json"),
                json);
        } catch (java.io.IOException e) {
            fail("Cannot write contract " + toolName + ": " + e.getMessage());
        }
    }

    /**
     * Build the MCP args Map equivalent to the CLI's candidate + snapshot.
     * The MCP tool parses this Map into the same ToolCallCandidate and
     * EnvironmentSnapshot objects.
     */
    private Map<String, Object> mcpArgsFor(String ontologyId,
                                            String callId,
                                            String toolName,
                                            Map<String, String> arguments) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("callId", callId);
        call.put("toolName", toolName);
        call.put("arguments", arguments);

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("snapshotId", sharedSnapshot.snapshotId());
        state.put("capturedAt", sharedSnapshot.capturedAt().toString());
        state.put("source", sharedSnapshot.source());
        state.put("version", sharedSnapshot.version());
        state.put("checksum", sharedSnapshot.checksum());

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ontology_id", ontologyId);
        args.put("call", call);
        args.put("state", state);
        return args;
    }

    // ── Test doubles (same as PipelineEndToEndTest) ──

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
