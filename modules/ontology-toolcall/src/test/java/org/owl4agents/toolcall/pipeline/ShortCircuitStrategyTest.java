package org.owl4agents.toolcall.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ServiceError;
import org.owl4agents.shacl.Severity;
import org.owl4agents.shacl.ShaclValidationReport;
import org.owl4agents.shacl.ShaclViolation;
import org.owl4agents.toolcall.JsonSchemaPreValidator;
import org.owl4agents.toolcall.JsonSchemaViolation;
import org.owl4agents.toolcall.ValidationDecision;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PL-002 / Task 7.8: Unit tests for {@link ShortCircuitStrategy}.
 *
 * <p>Each short-circuit rule gets at least one positive test (rule fires)
 * and one negative test (rule does not fire). The high-risk override is
 * applied by {@link PipelineContext#resolvedShortCircuitDecision()}, not
 * by the strategy itself, so these tests verify the "natural" decision
 * returned by each rule.</p>
 */
@DisplayName("PL-002 ShortCircuitStrategy (13 rules)")
class ShortCircuitStrategyTest {

    // ── 1. forParseFailure ──

    @Test
    @DisplayName("forParseFailure: returns SYSTEM_ERROR when parse error present")
    void forParseFailure_returnsSystemError() {
        Optional<ShortCircuitStrategy.ShortCircuitResult> r =
            ShortCircuitStrategy.forParseFailure("Unexpected token at pos 0");

        assertTrue(r.isPresent());
        assertEquals(PipelineStage.PARSE, r.get().stage());
        assertEquals(ValidationDecision.SYSTEM_ERROR, r.get().decision());
        assertEquals(ErrorCode.INVALID_ARGUMENTS, r.get().errorCode());
        assertEquals(ShortCircuitStrategy.ToolCallExecutionStatusForPipeline.ERROR,
            r.get().executionStatus());
        assertTrue(r.get().reason().contains("Malformed tool call JSON"));
    }

    @Test
    @DisplayName("forParseFailure: empty when no parse error")
    void forParseFailure_emptyWhenNoError() {
        assertTrue(ShortCircuitStrategy.forParseFailure(null).isEmpty());
        assertTrue(ShortCircuitStrategy.forParseFailure("").isEmpty());
        assertTrue(ShortCircuitStrategy.forParseFailure("   ").isEmpty());
    }

    // ── 2. forUnknownToolName ──

    @Test
    @DisplayName("forUnknownToolName: returns REJECT when tool not in known list")
    void forUnknownToolName_returnsReject() {
        Optional<ShortCircuitStrategy.ShortCircuitResult> r =
            ShortCircuitStrategy.forUnknownToolName(
                "missing_tool", List.of("set_temperature", "unlock_door"));

        assertTrue(r.isPresent());
        assertEquals(PipelineStage.LOAD_CONTRACT, r.get().stage());
        assertEquals(ValidationDecision.REJECT, r.get().decision());
        assertEquals(ErrorCode.TOOL_CONTRACT_NOT_FOUND, r.get().errorCode());
        assertTrue(r.get().reason().contains("missing_tool"));
        assertTrue(r.get().reason().contains("set_temperature"));
    }

    @Test
    @DisplayName("forUnknownToolName: empty when tool is known")
    void forUnknownToolName_emptyWhenKnown() {
        assertTrue(ShortCircuitStrategy.forUnknownToolName(
            "set_temperature", List.of("set_temperature")).isEmpty());
    }

    @Test
    @DisplayName("forUnknownToolName: handles empty/null known list")
    void forUnknownToolName_handlesEmptyKnownList() {
        Optional<ShortCircuitStrategy.ShortCircuitResult> r =
            ShortCircuitStrategy.forUnknownToolName("foo", List.of());
        assertTrue(r.isPresent());
        assertTrue(r.get().reason().contains("no contracts registered"));

        // null known list
        Optional<ShortCircuitStrategy.ShortCircuitResult> r2 =
            ShortCircuitStrategy.forUnknownToolName("foo", null);
        assertTrue(r2.isPresent());
    }

    // ── 3. forUnregisteredShapeSetId ──

    @Test
    @DisplayName("forUnregisteredShapeSetId: returns REJECT with SHAPE_SET_NOT_FOUND")
    void forUnregisteredShapeSetId_returnsReject() {
        Optional<ShortCircuitStrategy.ShortCircuitResult> r =
            ShortCircuitStrategy.forUnregisteredShapeSetId("missing-shapes-v1");

        assertTrue(r.isPresent());
        assertEquals(PipelineStage.LOAD_CONTRACT, r.get().stage());
        assertEquals(ValidationDecision.REJECT, r.get().decision());
        assertEquals(ErrorCode.SHAPE_SET_NOT_FOUND, r.get().errorCode());
        assertTrue(r.get().reason().contains("missing-shapes-v1"));
    }

    @Test
    @DisplayName("forUnregisteredShapeSetId: empty when shapeSetId is blank")
    void forUnregisteredShapeSetId_emptyWhenBlank() {
        assertTrue(ShortCircuitStrategy.forUnregisteredShapeSetId(null).isEmpty());
        assertTrue(ShortCircuitStrategy.forUnregisteredShapeSetId("").isEmpty());
        assertTrue(ShortCircuitStrategy.forUnregisteredShapeSetId("  ").isEmpty());
    }

    // ── 4. forJsonSchemaFailure ──

    @Test
    @DisplayName("forJsonSchemaFailure: returns suggested decision when violations present")
    void forJsonSchemaFailure_returnsSuggestedDecision() {
        JsonSchemaPreValidator validator = new JsonSchemaPreValidator();
        List<JsonSchemaViolation> violations = List.of(
            JsonSchemaViolation.ofRequired("$", "targetTemperature"));

        Optional<ShortCircuitStrategy.ShortCircuitResult> r =
            ShortCircuitStrategy.forJsonSchemaFailure(violations, validator);

        assertTrue(r.isPresent());
        assertEquals(PipelineStage.JSON_SCHEMA, r.get().stage());
        // The suggested decision for a required violation is AUTO_REPAIR or CLARIFY
        assertTrue(r.get().decision() == ValidationDecision.AUTO_REPAIR
            || r.get().decision() == ValidationDecision.CLARIFY,
            "JSON Schema failure should suggest AUTO_REPAIR or CLARIFY");
        assertTrue(r.get().reason().contains("JSON Schema violations"));
    }

    @Test
    @DisplayName("forJsonSchemaFailure: empty when no violations")
    void forJsonSchemaFailure_emptyWhenNoViolations() {
        assertTrue(ShortCircuitStrategy.forJsonSchemaFailure(
            List.of(), new JsonSchemaPreValidator()).isEmpty());
        assertTrue(ShortCircuitStrategy.forJsonSchemaFailure(null, null).isEmpty());
    }

    // ── 5. forInvalidSnapshot ──

    @Test
    @DisplayName("forInvalidSnapshot: returns SYSTEM_ERROR when reason present")
    void forInvalidSnapshot_returnsSystemError() {
        Optional<ShortCircuitStrategy.ShortCircuitResult> r =
            ShortCircuitStrategy.forInvalidSnapshot("snapshotId is not a UUID");

        assertTrue(r.isPresent());
        assertEquals(PipelineStage.BUILD_OVERLAY, r.get().stage());
        assertEquals(ValidationDecision.SYSTEM_ERROR, r.get().decision());
        assertEquals(ErrorCode.INVALID_ARGUMENTS, r.get().errorCode());
        assertTrue(r.get().reason().contains("Invalid EnvironmentSnapshot"));
    }

    @Test
    @DisplayName("forInvalidSnapshot: empty when reason is blank")
    void forInvalidSnapshot_emptyWhenBlank() {
        assertTrue(ShortCircuitStrategy.forInvalidSnapshot(null).isEmpty());
        assertTrue(ShortCircuitStrategy.forInvalidSnapshot("").isEmpty());
    }

    // ── 6. forOntologyLoadFailure ──

    @Test
    @DisplayName("forOntologyLoadFailure: returns SYSTEM_ERROR with ONTOLOGY_NOT_FOUND")
    void forOntologyLoadFailure_returnsSystemError() {
        Optional<ShortCircuitStrategy.ShortCircuitResult> r =
            ShortCircuitStrategy.forOntologyLoadFailure("missing-ontology");

        assertTrue(r.isPresent());
        assertEquals(PipelineStage.BUILD_OVERLAY, r.get().stage());
        assertEquals(ValidationDecision.SYSTEM_ERROR, r.get().decision());
        assertEquals(ErrorCode.ONTOLOGY_NOT_FOUND, r.get().errorCode());
        assertTrue(r.get().reason().contains("missing-ontology"));
    }

    // ── 7. forDynamicStateParseFailure ──

    @Test
    @DisplayName("forDynamicStateParseFailure: returns SYSTEM_ERROR")
    void forDynamicStateParseFailure_returnsSystemError() {
        Optional<ShortCircuitStrategy.ShortCircuitResult> r =
            ShortCircuitStrategy.forDynamicStateParseFailure("malformed Turtle at line 5");

        assertTrue(r.isPresent());
        assertEquals(PipelineStage.BUILD_OVERLAY, r.get().stage());
        assertEquals(ValidationDecision.SYSTEM_ERROR, r.get().decision());
        assertEquals(ErrorCode.INVALID_ARGUMENTS, r.get().errorCode());
        assertTrue(r.get().reason().contains("DynamicStateParser failed"));
    }

    // ── 8. forOverlayCreationFailure ──

    @Test
    @DisplayName("forOverlayCreationFailure: returns SYSTEM_ERROR with the service error code")
    void forOverlayCreationFailure_returnsSystemError() {
        ServiceError error = ServiceError.of(
            ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED, "boom");
        Optional<ShortCircuitStrategy.ShortCircuitResult> r =
            ShortCircuitStrategy.forOverlayCreationFailure(error);

        assertTrue(r.isPresent());
        assertEquals(PipelineStage.BUILD_OVERLAY, r.get().stage());
        assertEquals(ValidationDecision.SYSTEM_ERROR, r.get().decision());
        assertEquals(ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED, r.get().errorCode());
        assertTrue(r.get().reason().contains("Overlay creation failed"));
    }

    @Test
    @DisplayName("forOverlayCreationFailure: empty when error is null")
    void forOverlayCreationFailure_emptyWhenNull() {
        assertTrue(ShortCircuitStrategy.forOverlayCreationFailure(null).isEmpty());
    }

    // ── 9. forUnknownDevice ──

    @Test
    @DisplayName("forUnknownDevice: returns CLARIFY")
    void forUnknownDevice_returnsClarify() {
        Optional<ShortCircuitStrategy.ShortCircuitResult> r =
            ShortCircuitStrategy.forUnknownDevice("http://example.org/device/missing-1");

        assertTrue(r.isPresent());
        assertEquals(PipelineStage.CLAIM_DECOMPOSITION, r.get().stage());
        assertEquals(ValidationDecision.CLARIFY, r.get().decision());
        assertNull(r.get().errorCode(), "Unknown device is a clarification, not a system error");
        assertTrue(r.get().reason().contains("missing-1"));
    }

    // ── 10. forOwlContradiction ──

    @Test
    @DisplayName("forOwlContradiction: returns REJECT")
    void forOwlContradiction_returnsReject() {
        Optional<ShortCircuitStrategy.ShortCircuitResult> r =
            ShortCircuitStrategy.forOwlContradiction("claim-1 contradicted (disjoint classes)");

        assertTrue(r.isPresent());
        assertEquals(PipelineStage.OWL_BATCH, r.get().stage());
        assertEquals(ValidationDecision.REJECT, r.get().decision());
        assertTrue(r.get().reason().contains("OWL contradiction"));
        assertTrue(r.get().reason().contains("claim-1"));
    }

    // ── 11. forReasonerTimeout ──

    @Test
    @DisplayName("forReasonerTimeout: returns SYSTEM_ERROR with TIMEOUT execution status")
    void forReasonerTimeout_returnsSystemErrorWithTimeout() {
        Optional<ShortCircuitStrategy.ShortCircuitResult> r =
            ShortCircuitStrategy.forReasonerTimeout();

        assertTrue(r.isPresent());
        assertEquals(PipelineStage.OWL_BATCH, r.get().stage());
        assertEquals(ValidationDecision.SYSTEM_ERROR, r.get().decision());
        assertEquals(ErrorCode.REASONER_TIMEOUT, r.get().errorCode());
        assertEquals(ShortCircuitStrategy.ToolCallExecutionStatusForPipeline.TIMEOUT,
            r.get().executionStatus(),
            "Reasoner timeout must surface as TIMEOUT execution status");
    }

    // ── 12. forShaclViolation ──

    @Test
    @DisplayName("forShaclViolation: returns REJECT when no repairHint available")
    void forShaclViolation_returnsRejectWhenNoRepairHint() {
        ShaclViolation v = new ShaclViolation(
            "v1", "http://shapes#S1", "sh:minCount",
            "http://example.org/device/1", "http://example.org/hasState",
            null, Severity.Violation, "Device must have at least one state",
            List.of(), null);
        ShaclValidationReport report = new ShaclValidationReport(
            false, List.of(v), List.of(), List.of(), 10L,
            Optional.of("shapes-v1"), "1.0");

        Optional<ShortCircuitStrategy.ShortCircuitResult> r =
            ShortCircuitStrategy.forShaclViolation(report);

        assertTrue(r.isPresent());
        assertEquals(PipelineStage.SHACL, r.get().stage());
        assertEquals(ValidationDecision.REJECT, r.get().decision());
        assertTrue(r.get().reason().contains("SHACL Violation-severity"));
    }

    @Test
    @DisplayName("forShaclViolation: returns AUTO_REPAIR when repairHint present")
    void forShaclViolation_returnsAutoRepairWhenHintPresent() {
        ShaclViolation v = new ShaclViolation(
            "v1", "http://shapes#S1", "sh:minCount",
            "http://example.org/device/1", "http://example.org/hasState",
            null, Severity.Violation, "Device must have at least one state",
            List.of(), "Add a state assertion for hasState");
        ShaclValidationReport report = new ShaclValidationReport(
            false, List.of(v), List.of(), List.of(), 10L,
            Optional.of("shapes-v1"), "1.0");

        Optional<ShortCircuitStrategy.ShortCircuitResult> r =
            ShortCircuitStrategy.forShaclViolation(report);

        assertTrue(r.isPresent());
        assertEquals(ValidationDecision.AUTO_REPAIR, r.get().decision(),
            "Repairable SHACL violation should suggest AUTO_REPAIR");
    }

    @Test
    @DisplayName("forShaclViolation: empty when no violations")
    void forShaclViolation_emptyWhenNoViolations() {
        ShaclValidationReport report = ShaclValidationReport.empty(5L);
        assertTrue(ShortCircuitStrategy.forShaclViolation(report).isEmpty());
        assertTrue(ShortCircuitStrategy.forShaclViolation(null).isEmpty());
    }

    // ── 13. forShaclShapesLoadFailure ──

    @Test
    @DisplayName("forShaclShapesLoadFailure: returns SYSTEM_ERROR with SHACL_SHAPES_LOAD_FAILED")
    void forShaclShapesLoadFailure_returnsSystemError() {
        Optional<ShortCircuitStrategy.ShortCircuitResult> r =
            ShortCircuitStrategy.forShaclShapesLoadFailure("missing-shapes-v2");

        assertTrue(r.isPresent());
        assertEquals(PipelineStage.SHACL, r.get().stage());
        assertEquals(ValidationDecision.SYSTEM_ERROR, r.get().decision());
        assertEquals(ErrorCode.SHACL_SHAPES_LOAD_FAILED, r.get().errorCode());
        assertTrue(r.get().reason().contains("missing-shapes-v2"));
    }

    // ── High-risk override is applied by PipelineContext, not the strategy ──

    @Test
    @DisplayName("Strategy rules do NOT apply high-risk override (PipelineContext does)")
    void strategyRulesDoNotApplyHighRiskOverride() {
        // forJsonSchemaFailure returns AUTO_REPAIR/CLARIFY even if the tool
        // would be high-risk. The override is applied centrally by
        // PipelineContext.resolvedShortCircuitDecision().
        JsonSchemaPreValidator validator = new JsonSchemaPreValidator();
        List<JsonSchemaViolation> violations = List.of(
            JsonSchemaViolation.ofRequired("$", "targetTemperature"));

        Optional<ShortCircuitStrategy.ShortCircuitResult> r =
            ShortCircuitStrategy.forJsonSchemaFailure(violations, validator);

        assertTrue(r.isPresent());
        assertNotEquals(ValidationDecision.REQUEST_CONFIRMATION, r.get().decision(),
            "Strategy must return natural decision; override applied by PipelineContext");
    }
}
