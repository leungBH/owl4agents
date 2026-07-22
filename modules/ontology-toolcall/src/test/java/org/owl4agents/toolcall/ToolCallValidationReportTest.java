package org.owl4agents.toolcall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.overlay.StateSnapshotId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-007 unit tests for {@link ToolCallValidationReport} (13 fields,
 * {@link ToolCallValidationReport#SCHEMA_VERSION},
 * {@link ToolCallValidationReport#STAGE_NAMES} (10 stages),
 * {@link ToolCallValidationReport#emptyTiming()},
 * {@link ToolCallValidationReport#shortCircuitedAtJsonSchema()}).
 */
@DisplayName("TC-007 ToolCallValidationReport record")
class ToolCallValidationReportTest {

    @Test
    @DisplayName("Record exposes exactly 13 fields per spec TC-004")
    void exactlyThirteenFields() {
        assertEquals(13,
            ToolCallValidationReport.class.getRecordComponents().length,
            "ToolCallValidationReport must have exactly 13 fields per spec TC-004");
    }

    @Test
    @DisplayName("SCHEMA_VERSION is '1.0'")
    void schemaVersion() {
        assertEquals("1.0", ToolCallValidationReport.SCHEMA_VERSION,
            "Spec TC-004: schemaVersion field (currently '1.0')");
    }

    @Test
    @DisplayName("STAGE_NAMES exposes exactly 10 stages in fixed order")
    void tenStageNamesInFixedOrder() {
        List<String> stages = ToolCallValidationReport.STAGE_NAMES;
        assertEquals(10, stages.size(),
            "Pipeline must have 10 stages per design D12");
        // Verify fixed order for byte-for-byte parity
        assertEquals(List.of(
            "parseToolCallMs",
            "loadToolContractMs",
            "jsonSchemaMs",
            "buildOverlayMs",
            "claimDecompositionMs",
            "owlBatchMs",
            "shaclMs",
            "riskEvaluationMs",
            "decisionMs",
            "structuredReportMs"
        ), stages);
    }

    @Test
    @DisplayName("emptyTiming() returns all 10 stage entries set to 0")
    void emptyTimingHasAllStagesZero() {
        Map<String, Long> t = ToolCallValidationReport.emptyTiming();
        assertEquals(10, t.size(),
            "emptyTiming must contain all 10 stage entries");
        for (String stage : ToolCallValidationReport.STAGE_NAMES) {
            assertTrue(t.containsKey(stage),
                "emptyTiming must contain '" + stage + "'");
            assertEquals(0L, t.get(stage),
                "emptyTiming must set '" + stage + "' to 0");
        }
    }

    @Test
    @DisplayName("emptyTiming() returns a mutable map (base for merging)")
    void emptyTimingIsMutable() {
        Map<String, Long> t = ToolCallValidationReport.emptyTiming();
        // Should be mutable so the canonical constructor can merge entries.
        t.put("owlBatchMs", 500L);
        assertEquals(500L, t.get("owlBatchMs"));
    }

    @Test
    @DisplayName("Blank callId is rejected")
    void blankCallIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ToolCallValidationReport(
            "1.0", "", ToolCallExecutionStatus.OK, ValidationDecision.EXECUTE,
            RiskLevel.LOW, List.of(), List.of(), List.of(), Optional.empty(),
            List.of(), List.of(), ToolCallValidationReport.emptyTiming(), 0L));
        assertThrows(IllegalArgumentException.class, () -> new ToolCallValidationReport(
            "1.0", null, ToolCallExecutionStatus.OK, ValidationDecision.EXECUTE,
            RiskLevel.LOW, List.of(), List.of(), List.of(), Optional.empty(),
            List.of(), List.of(), ToolCallValidationReport.emptyTiming(), 0L));
    }

    @Test
    @DisplayName("Null perStageTiming defaults to emptyTiming()")
    void nullTimingDefaultsToEmpty() {
        ToolCallValidationReport r = new ToolCallValidationReport(
            "1.0", "call-1", ToolCallExecutionStatus.OK, ValidationDecision.EXECUTE,
            RiskLevel.LOW, List.of(), List.of(), List.of(), Optional.empty(),
            List.of(), List.of(), null, 0L);
        Map<String, Long> t = r.perStageTiming();
        assertEquals(10, t.size(),
            "Null perStageTiming must default to emptyTiming()");
        for (String stage : ToolCallValidationReport.STAGE_NAMES) {
            assertEquals(0L, t.get(stage));
        }
    }

    @Test
    @DisplayName("Partial perStageTiming is merged with emptyTiming() (missing stages = 0)")
    void partialTimingMergedWithZeros() {
        Map<String, Long> partial = new LinkedHashMap<>();
        partial.put("owlBatchMs", 1234L);
        partial.put("jsonSchemaMs", 5L);
        ToolCallValidationReport r = new ToolCallValidationReport(
            "1.0", "call-1", ToolCallExecutionStatus.OK, ValidationDecision.EXECUTE,
            RiskLevel.LOW, List.of(), List.of(), List.of(), Optional.empty(),
            List.of(), List.of(), partial, 2000L);
        // owlBatchMs + jsonSchemaMs preserved; all others default to 0.
        assertEquals(1234L, r.perStageTiming().get("owlBatchMs"));
        assertEquals(5L, r.perStageTiming().get("jsonSchemaMs"));
        assertEquals(0L, r.perStageTiming().get("shaclMs"),
            "Missing stages must default to 0");
        assertEquals(0L, r.perStageTiming().get("riskEvaluationMs"));
    }

    @Test
    @DisplayName("Negative totalMs is normalized to 0")
    void negativeTotalMsNormalized() {
        ToolCallValidationReport r = new ToolCallValidationReport(
            "1.0", "call-1", ToolCallExecutionStatus.OK, ValidationDecision.EXECUTE,
            RiskLevel.LOW, List.of(), List.of(), List.of(), Optional.empty(),
            List.of(), List.of(), null, -100L);
        assertEquals(0L, r.totalMs(),
            "Negative totalMs must be normalized to 0");
    }

    @Test
    @DisplayName("Null enums default to ERROR / SYSTEM_ERROR (defensive)")
    void nullEnumsDefaultDefensive() {
        ToolCallValidationReport r = new ToolCallValidationReport(
            "1.0", "call-1", null, null,
            null, List.of(), List.of(), List.of(), Optional.empty(),
            List.of(), List.of(), null, 0L);
        assertEquals(ToolCallExecutionStatus.ERROR, r.executionStatus(),
            "Null executionStatus must default to ERROR (never OK)");
        assertEquals(ValidationDecision.SYSTEM_ERROR, r.decision(),
            "Null decision must default to SYSTEM_ERROR (never EXECUTE)");
        assertEquals(RiskLevel.LOW, r.riskLevel(),
            "Null riskLevel must default to LOW");
    }

    @Test
    @DisplayName("shortCircuitedAtJsonSchema() detects stage-3 short-circuit")
    void detectsJsonSchemaShortCircuit() {
        // Per spec "JSON Schema Failure Short-Circuit": when stage 3 produces
        // violations and stages 4-7 are skipped (empty results), the report
        // is short-circuited at JSON Schema.
        ToolCallValidationReport shortCircuited = new ToolCallValidationReport(
            "1.0", "call-1", ToolCallExecutionStatus.OK,
            ValidationDecision.AUTO_REPAIR, RiskLevel.LOW,
            List.of(new JsonSchemaViolation("$", "required", "missing",
                Optional.empty(), Optional.empty())),
            List.of(), // owlClaimResults empty (stage 6 skipped)
            List.of(), // shaclViolations empty (stage 7 skipped)
            Optional.empty(),
            List.of(), List.of("add targetTemperature"),
            ToolCallValidationReport.emptyTiming(), 5L);
        assertTrue(shortCircuited.shortCircuitedAtJsonSchema(),
            "Stage-3 short-circuit must be detected");
    }

    @Test
    @DisplayName("shortCircuitedAtJsonSchema() returns false when owlClaimResults non-empty")
    void notShortCircuitedWhenOwlResultsPresent() {
        ToolCallValidationReport notShortCircuited = new ToolCallValidationReport(
            "1.0", "call-1", ToolCallExecutionStatus.OK,
            ValidationDecision.EXECUTE, RiskLevel.LOW,
            List.of(), // no JSON Schema violations
            List.of(Map.of("claimId", "c1", "verdict", "supported")),
            List.of(), Optional.empty(),
            List.of(), List.of(), ToolCallValidationReport.emptyTiming(), 100L);
        assertFalse(notShortCircuited.shortCircuitedAtJsonSchema());
    }

    @Test
    @DisplayName("stateVersion carries StateSnapshotId metadata")
    void stateVersionCarriesMetadata() {
        StateSnapshotId snapshot = new StateSnapshotId(
            "snap-001", Instant.parse("2026-07-21T10:30:00Z"),
            "mcp", 1L,
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
        ToolCallValidationReport r = new ToolCallValidationReport(
            "1.0", "call-1", ToolCallExecutionStatus.OK,
            ValidationDecision.EXECUTE, RiskLevel.LOW,
            List.of(), List.of(), List.of(),
            Optional.of(snapshot),
            List.of(), List.of(), null, 100L);
        assertTrue(r.stateVersion().isPresent());
        assertEquals("snap-001", r.stateVersion().get().snapshotId());
    }

    @Test
    @DisplayName("Serialization includes all 13 fields in fixed order")
    void serializationPreservesFieldOrder() {
        ToolCallValidationReport r = new ToolCallValidationReport(
            "1.0", "call-1", ToolCallExecutionStatus.OK,
            ValidationDecision.EXECUTE, RiskLevel.LOW,
            List.of(), List.of(), List.of(), Optional.empty(),
            List.of(), List.of(), null, 100L);
        Map<String, Object> m = ToolCallJsonSerializer.reportToMap(r);
        assertEquals(13, m.size(),
            "All 13 fields must be serialized per spec");
        java.util.List<String> keys = new java.util.ArrayList<>(m.keySet());
        assertEquals(java.util.List.of(
            "schemaVersion", "callId", "executionStatus", "decision",
            "riskLevel", "jsonSchemaViolations", "owlClaimResults",
            "shaclViolations", "stateVersion", "evidence", "repairSpace",
            "perStageTiming", "totalMs"), keys,
            "Serializer must preserve spec field order for parity");
    }

    @Test
    @DisplayName("perStageTiming serialization preserves 10-stage ordering")
    void timingSerializationPreservesOrder() {
        ToolCallValidationReport r = new ToolCallValidationReport(
            "1.0", "call-1", ToolCallExecutionStatus.OK,
            ValidationDecision.EXECUTE, RiskLevel.LOW,
            List.of(), List.of(), List.of(), Optional.empty(),
            List.of(), List.of(), null, 100L);
        Map<String, Object> m = ToolCallJsonSerializer.reportToMap(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> timing = (Map<String, Object>) m.get("perStageTiming");
        assertEquals(10, timing.size());
        java.util.List<String> keys = new java.util.ArrayList<>(timing.keySet());
        assertEquals(ToolCallValidationReport.STAGE_NAMES, keys,
            "perStageTiming must preserve STAGE_NAMES order in serialization");
    }

    @Test
    @DisplayName("Short-circuit report has owlBatchMs=0 (reasoner not started)")
    void shortCircuitHasZeroOwlBatchMs() {
        // Spec "Required argument missing": perStageTiming.owlMs SHALL be 0
        // (reasoner not started). Note: our stage name is 'owlBatchMs'.
        ToolCallValidationReport shortCircuited = new ToolCallValidationReport(
            "1.0", "call-1", ToolCallExecutionStatus.OK,
            ValidationDecision.AUTO_REPAIR, RiskLevel.LOW,
            List.of(JsonSchemaViolation.ofRequired("$", "targetTemperature")),
            List.of(), List.of(), Optional.empty(),
            List.of(), List.of(),
            ToolCallValidationReport.emptyTiming(), 5L);
        assertEquals(0L, shortCircuited.perStageTiming().get("owlBatchMs"),
            "Short-circuit at stage 3 must leave owlBatchMs at 0");
        assertEquals(0L, shortCircuited.perStageTiming().get("shaclMs"),
            "Short-circuit at stage 3 must leave shaclMs at 0");
        assertEquals(0L, shortCircuited.perStageTiming().get("buildOverlayMs"),
            "Short-circuit at stage 3 must leave buildOverlayMs at 0");
    }
}
