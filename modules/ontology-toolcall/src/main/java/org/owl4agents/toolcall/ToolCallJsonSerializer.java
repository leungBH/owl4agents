package org.owl4agents.toolcall;

import org.owl4agents.overlay.StateSnapshotId;
import org.owl4agents.overlay.ToolCallCandidate;
import org.owl4agents.shacl.ShaclViolation;
import org.owl4agents.shacl.ShaclJsonSerializer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * v0.8.7 TC-005 / TC-007: Canonical JSON-serializable Map builders for
 * the tool-call data model.
 *
 * <p>Both the MCP tools ({@code ontology_get_tool_contract},
 * {@code ontology_list_tool_contracts}) and the CLI
 * ({@code toolcall validate --output json}) use these helpers to produce
 * byte-for-byte identical output for the same fixtures, satisfying the
 * "MCP/CLI/Java parity" requirement.</p>
 *
 * <p>Nullable fields are explicitly serialized as {@code null} (not
 * omitted) to keep the schema stable, matching the
 * {@code ShaclJsonSerializer} convention from the SHACL module.</p>
 */
public final class ToolCallJsonSerializer {

    private ToolCallJsonSerializer() {}

    /**
     * Serialize a {@link ToolContract} to a Map with EXACTLY 9 fields.
     * Nullable fields emitted as {@code null} (not omitted).
     */
    public static Map<String, Object> contractToMap(ToolContract c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("toolName", c.toolName());
        m.put("inputSchema", c.inputSchema());
        m.put("targetClass", c.targetClass().orElse(null));
        m.put("requiredCapabilities", c.requiredCapabilities());
        m.put("requiredStates", c.requiredStates());
        m.put("effects", c.effects());
        m.put("riskLevel", c.riskLevel().jsonName());
        m.put("requiredPermission", c.requiredPermission().orElse(null));
        m.put("shapeSetIds", c.shapeSetIds());
        return m;
    }

    /**
     * Serialize a {@link JsonSchemaViolation} to a Map with EXACTLY 5
     * fields, nullable fields emitted as {@code null} (not omitted).
     */
    public static Map<String, Object> violationToMap(JsonSchemaViolation v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fieldPath", v.fieldPath());
        m.put("violationType", v.violationType());
        m.put("message", v.message());
        m.put("expectedValue", v.expectedValue().orElse(null));
        m.put("actualValue", v.actualValue().orElse(null));
        return m;
    }

    /**
     * Serialize a {@link ToolCallValidationReport} to a Map with EXACTLY
     * 13 fields. Field order is fixed for byte-for-byte parity.
     */
    public static Map<String, Object> reportToMap(ToolCallValidationReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", r.schemaVersion());
        m.put("callId", r.callId());
        m.put("executionStatus", r.executionStatus().jsonName());
        m.put("decision", r.decision().jsonName());
        m.put("riskLevel", r.riskLevel().jsonName());
        m.put("jsonSchemaViolations", r.jsonSchemaViolations().stream()
            .map(ToolCallJsonSerializer::violationToMap)
            .collect(Collectors.toList()));
        m.put("owlClaimResults", r.owlClaimResults());
        m.put("shaclViolations", r.shaclViolations().stream()
            .map(ShaclJsonSerializer::violationToMap)
            .collect(Collectors.toList()));
        m.put("stateVersion", stateVersionToMap(r.stateVersion().orElse(null)));
        m.put("evidence", r.evidence());
        m.put("repairSpace", r.repairSpace());
        // Preserve the 10-stage ordering for parity.
        Map<String, Object> timingMap = new LinkedHashMap<>();
        for (String name : ToolCallValidationReport.STAGE_NAMES) {
            timingMap.put(name, r.perStageTiming().getOrDefault(name, 0L));
        }
        m.put("perStageTiming", timingMap);
        m.put("totalMs", r.totalMs());
        return m;
    }

    /**
     * Serialize the {@link StateSnapshotId} metadata (the
     * {@code stateVersion} field of the report) to a Map. Returns
     * {@code null} when no snapshot was used (short-circuit before
     * stage 4).
     */
    public static Map<String, Object> stateVersionToMap(StateSnapshotId s) {
        if (s == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("snapshotId", s.snapshotId());
        m.put("capturedAt", s.capturedAt() != null ? s.capturedAt().toString() : null);
        m.put("source", s.source());
        m.put("version", s.version());
        m.put("checksum", s.checksum());
        return m;
    }

    /**
     * Serialize a {@link ToolCallCandidate} (defined in ontology-overlay)
     * to a Map with all 11 fields. Nullable fields emitted as
     * {@code null} (not omitted) to keep the schema stable per the
     * "All fields populated" / "targetEntity nullable" scenarios.
     */
    public static Map<String, Object> candidateToMap(ToolCallCandidate c) {
        if (c == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("callId", c.callId());
        m.put("requestId", c.requestId().orElse(null));
        m.put("userRequest", c.userRequest().orElse(null));
        m.put("toolName", c.toolName());
        m.put("targetEntity", c.targetEntity().orElse(null));
        m.put("arguments", c.arguments());
        m.put("requestedBy", c.requestedBy().orElse(null));
        m.put("timestamp", c.timestamp().orElse(null));
        m.put("environmentSnapshotId", c.environmentSnapshotId().orElse(null));
        m.put("sourceModel", c.sourceModel().orElse(null));
        m.put("sourceModelResponseId", c.sourceModelResponseId().orElse(null));
        return m;
    }

    /**
     * Compact metadata summary for {@code ontology_list_tool_contracts}.
     * Per the "List registered contracts" scenario, each entry carries at
     * minimum {@code toolName} and {@code riskLevel}.
     */
    public static Map<String, Object> contractSummaryToMap(ToolContract c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("toolName", c.toolName());
        m.put("riskLevel", c.riskLevel().jsonName());
        // Include additional metadata for caller convenience (still safe:
        // contracts are operator-defined, not user-supplied).
        m.put("hasShacl", c.requiresShacl());
        m.put("targetsEntity", c.targetsEntity());
        m.put("shapeSetIds", c.shapeSetIds());
        return m;
    }
}
