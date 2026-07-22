package org.owl4agents.toolcall.pipeline;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ServiceError;
import org.owl4agents.overlay.DeviceSnapshot;
import org.owl4agents.overlay.EnvironmentSnapshot;
import org.owl4agents.overlay.OverlayOptions;
import org.owl4agents.overlay.StateSnapshotId;
import org.owl4agents.overlay.ToolCallCandidate;
import org.owl4agents.overlay.TransientOntologyOverlayService;
import org.owl4agents.overlay.TransientOverlay;
import org.owl4agents.shacl.ShaclJsonSerializer;
import org.owl4agents.toolcall.ToolCallJsonSerializer;
import org.owl4agents.toolcall.ToolCallValidationReport;
import org.owl4agents.toolcall.ToolContract;
import org.owl4agents.toolcall.ToolContractRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.8.7 PL-004: Handlers for the 3 new pipeline MCP tools.
 *
 * <p>All 3 tools are read-only: they do not modify the workspace, the
 * ontology cache, or any persistent state. The
 * {@code ontology_preview_tool_call_effects} tool creates a transient
 * overlay, simulates the effects, returns the simulated post-state, and
 * releases the overlay before responding (per spec
 * "ontology_preview_tool_call_effects is read-only").</p>
 *
 * <p>The handlers delegate to {@link ToolCallValidationPipeline} for the
 * core 10-stage validation, ensuring CLI/MCP parity (spec "CLI and MCP
 * Service Contract Sharing").</p>
 *
 * <p>Tools provided:</p>
 * <ul>
 *   <li>{@code ontology_validate_tool_call} — runs the 10-stage pipeline
 *       and returns a {@link ToolCallValidationReport}.</li>
 *   <li>{@code ontology_explain_tool_call} — returns the evidence
 *       triples, owlClaimResults, and shaclViolations for a prior
 *       validation (cached by {@code callId}).</li>
 *   <li>{@code ontology_preview_tool_call_effects} — creates a
 *       transient overlay, simulates the tool call's effects, returns
 *       the simulated post-state, and releases the overlay.</li>
 * </ul>
 */
public final class PipelineMcpTools {

    private final ToolCallValidationPipeline pipeline;
    private final ToolContractRegistry toolContractRegistry;
    private final TransientOntologyOverlayService overlayService;
    // Simple in-memory cache for ontology_explain_tool_call. Bounded to
    // 100 entries to prevent unbounded growth; the cache is best-effort
    // — a miss returns a NOT_FOUND error.
    private final Map<String, ToolCallValidationReport> reportCache =
        new ConcurrentHashMap<>();
    private static final int CACHE_LIMIT = 100;

    public PipelineMcpTools(ToolCallValidationPipeline pipeline,
                             ToolContractRegistry toolContractRegistry,
                             TransientOntologyOverlayService overlayService) {
        this.pipeline = pipeline;
        this.toolContractRegistry = toolContractRegistry;
        this.overlayService = overlayService;
    }

    /**
     * Handler for {@code ontology_validate_tool_call}.
     *
     * <p>Expected arguments:</p>
     * <ul>
     *   <li>{@code ontology_id} (string, required) — base ontology ID</li>
     *   <li>{@code call} (object, required) — {@link ToolCallCandidate} JSON</li>
     *   <li>{@code state} (object, optional) — {@link EnvironmentSnapshot} JSON</li>
     * </ul>
     *
     * <p>Returns a {@link ToolCallValidationReport} serialized via
     * {@link ToolCallJsonSerializer#reportToMap}.</p>
     */
    public Map<String, Object> validateToolCall(Map<String, Object> args) {
        String ontologyId = getStr(args, "ontology_id");
        if (ontologyId == null || ontologyId.isBlank()) {
            return errorResponse(ErrorCode.INVALID_ARGUMENTS,
                "ontology_id is required");
        }
        Object callObj = args.get("call");
        if (callObj == null) {
            return errorResponse(ErrorCode.INVALID_ARGUMENTS,
                "call is required (ToolCallCandidate JSON)");
        }
        ToolCallCandidate candidate;
        try {
            candidate = parseCandidate(callObj);
        } catch (RuntimeException e) {
            return errorResponse(ErrorCode.INVALID_ARGUMENTS,
                "Failed to parse call: " + e.getMessage());
        }
        if (candidate.callId() == null || candidate.callId().isBlank()) {
            return errorResponse(ErrorCode.INVALID_ARGUMENTS,
                "call.callId is required");
        }

        EnvironmentSnapshot snapshot = null;
        Object stateObj = args.get("state");
        if (stateObj != null) {
            try {
                snapshot = parseSnapshot(stateObj);
            } catch (RuntimeException e) {
                return errorResponse(ErrorCode.INVALID_ARGUMENTS,
                    "Failed to parse state: " + e.getMessage());
            }
        }
        if (snapshot == null) {
            // Build a minimal empty snapshot so the pipeline can proceed
            // (stage 4 will create an overlay with no dynamic axioms).
            snapshot = buildEmptySnapshot();
        }

        ToolCallValidationReport report = pipeline.validate(candidate, ontologyId, snapshot);

        // Cache the report for ontology_explain_tool_call.
        cacheReport(report);

        return Map.of("status", "success",
            "data", ToolCallJsonSerializer.reportToMap(report));
    }

    /**
     * Handler for {@code ontology_explain_tool_call}.
     *
     * <p>Expected arguments:</p>
     * <ul>
     *   <li>{@code callId} (string, required) — callId from a prior
     *       validation</li>
     * </ul>
     *
     * <p>Returns the {@code evidence}, {@code owlClaimResults}, and
     * {@code shaclViolations} for that call (per spec
     * "ontology_explain_tool_call returns evidence").</p>
     */
    public Map<String, Object> explainToolCall(Map<String, Object> args) {
        String callId = getStr(args, "callId");
        if (callId == null || callId.isBlank()) {
            return errorResponse(ErrorCode.INVALID_ARGUMENTS,
                "callId is required");
        }
        ToolCallValidationReport report = reportCache.get(callId);
        if (report == null) {
            return errorResponse(ErrorCode.INVALID_ARGUMENTS,
                "No cached validation report for callId '" + callId
                    + "'. Run ontology_validate_tool_call first.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("callId", report.callId());
        data.put("decision", report.decision().jsonName());
        data.put("evidence", report.evidence());
        data.put("owlClaimResults", report.owlClaimResults());
        data.put("shaclViolations", report.shaclViolations().stream()
            .map(ShaclJsonSerializer::violationToMap)
            .toList());
        data.put("repairSpace", report.repairSpace());
        return Map.of("status", "success", "data", data);
    }

    /**
     * Handler for {@code ontology_preview_tool_call_effects}.
     *
     * <p>Expected arguments:</p>
     * <ul>
     *   <li>{@code ontology_id} (string, required)</li>
     *   <li>{@code call} (object, required) — {@link ToolCallCandidate} JSON</li>
     *   <li>{@code state} (object, optional) — {@link EnvironmentSnapshot} JSON</li>
     * </ul>
     *
     * <p>Creates a transient overlay, simulates the tool call's effects,
     * returns the simulated post-state (the overlay's axiom set as
     * RDF/Turtle), and releases the overlay before responding. The
     * workspace ontology is never modified (per spec
     * "ontology_preview_tool_call_effects is read-only").</p>
     */
    public Map<String, Object> previewToolCallEffects(Map<String, Object> args) {
        String ontologyId = getStr(args, "ontology_id");
        if (ontologyId == null || ontologyId.isBlank()) {
            return errorResponse(ErrorCode.INVALID_ARGUMENTS,
                "ontology_id is required");
        }
        Object callObj = args.get("call");
        if (callObj == null) {
            return errorResponse(ErrorCode.INVALID_ARGUMENTS,
                "call is required (ToolCallCandidate JSON)");
        }
        ToolCallCandidate candidate;
        try {
            candidate = parseCandidate(callObj);
        } catch (RuntimeException e) {
            return errorResponse(ErrorCode.INVALID_ARGUMENTS,
                "Failed to parse call: " + e.getMessage());
        }

        EnvironmentSnapshot snapshot = null;
        Object stateObj = args.get("state");
        if (stateObj != null) {
            try {
                snapshot = parseSnapshot(stateObj);
            } catch (RuntimeException e) {
                return errorResponse(ErrorCode.INVALID_ARGUMENTS,
                    "Failed to parse state: " + e.getMessage());
            }
        }
        if (snapshot == null) {
            snapshot = buildEmptySnapshot();
        }

        // Create the overlay directly (the preview does not run the full
        // 10-stage pipeline; it just simulates the post-state).
        org.owl4agents.core.OntologyId ontId = new org.owl4agents.core.OntologyId(ontologyId);
        java.util.Collection<org.semanticweb.owlapi.model.OWLAxiom> dynamicAxioms;
        try {
            dynamicAxioms = org.owl4agents.overlay.DynamicStateParser.parse(snapshot);
        } catch (RuntimeException e) {
            return errorResponse(ErrorCode.INVALID_ARGUMENTS,
                "Failed to parse dynamic state: " + e.getMessage());
        }
        // Also add the candidate itself as a pending tool call axiom so
        // the preview reflects the call's presence.
        try {
            dynamicAxioms = new ArrayList<>(dynamicAxioms);
            dynamicAxioms.addAll(org.owl4agents.overlay.DynamicStateParser.parse(candidate));
        } catch (RuntimeException ignored) {
            // Best-effort; the candidate may not convert cleanly.
        }

        org.owl4agents.core.ServiceResult<TransientOverlay> r =
            overlayService.createOverlay(ontId, dynamicAxioms, OverlayOptions.defaults());
        if (!r.isSuccess()) {
            org.owl4agents.core.ServiceError err =
                ((org.owl4agents.core.ServiceResult.Error<TransientOverlay>) r).error();
            return errorResponse(err.code(), err.message());
        }
        TransientOverlay overlay = ((org.owl4agents.core.ServiceResult.Success<TransientOverlay>) r).data();
        try {
            // Serialize the overlay's axiom set as RDF/Turtle for the
            // simulated post-state.
            String postStateTtl;
            try {
                postStateTtl = serializeOntologyAsTurtle(overlay.ontology());
            } catch (RuntimeException e) {
                return errorResponse(ErrorCode.INVALID_ARGUMENTS,
                    "Failed to serialize overlay: " + e.getMessage());
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("overlayId", overlay.overlayId());
            data.put("baseOntologyId", overlay.baseOntologyId().id());
            data.put("snapshotId", overlay.snapshotId());
            data.put("dynamicAxiomCount", overlay.dynamicAxioms().size());
            data.put("postStateTtl", postStateTtl);
            return Map.of("status", "success", "data", data);
        } finally {
            try {
                overlay.release();
            } catch (RuntimeException ignored) {
                // Best-effort cleanup.
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────

    private void cacheReport(ToolCallValidationReport report) {
        if (reportCache.size() >= CACHE_LIMIT) {
            // Evict an arbitrary entry (best-effort; ConcurrentHashMap
            // does not have LRU semantics, but the cache is only for
            // explain lookups and a miss is recoverable).
            String firstKey = reportCache.keySet().iterator().next();
            reportCache.remove(firstKey);
        }
        reportCache.put(report.callId(), report);
    }

    private static String getStr(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String ? (String) v : null;
    }

    private static Map<String, Object> errorResponse(ErrorCode code, String message) {
        return Map.of("status", "error",
            "error", Map.of("code", code.code(), "message", message));
    }

    @SuppressWarnings("unchecked")
    private static ToolCallCandidate parseCandidate(Object obj) {
        if (!(obj instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("call must be a JSON object");
        }
        String callId = getStrFromMap(map, "callId");
        String toolName = getStrFromMap(map, "toolName");
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("call.callId is required");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("call.toolName is required");
        }
        Optional<String> requestId = getOptStrFromMap(map, "requestId");
        Optional<String> userRequest = getOptStrFromMap(map, "userRequest");
        Optional<String> targetEntity = getOptStrFromMap(map, "targetEntity");
        Optional<String> requestedBy = getOptStrFromMap(map, "requestedBy");
        Optional<String> timestamp = getOptStrFromMap(map, "timestamp");
        Optional<String> environmentSnapshotId = getOptStrFromMap(map, "environmentSnapshotId");
        Optional<String> sourceModel = getOptStrFromMap(map, "sourceModel");
        Optional<String> sourceModelResponseId = getOptStrFromMap(map, "sourceModelResponseId");
        Map<String, String> arguments = new LinkedHashMap<>();
        Object argsObj = map.get("arguments");
        if (argsObj instanceof Map<?, ?> argsMap) {
            for (Map.Entry<?, ?> e : argsMap.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    arguments.put(e.getKey().toString(),
                        e.getValue() instanceof String
                            ? (String) e.getValue()
                            : e.getValue().toString());
                }
            }
        }
        return new ToolCallCandidate(
            callId, requestId, userRequest, toolName, targetEntity,
            arguments, requestedBy, timestamp, environmentSnapshotId,
            sourceModel, sourceModelResponseId
        );
    }

    @SuppressWarnings("unchecked")
    private static EnvironmentSnapshot parseSnapshot(Object obj) {
        if (!(obj instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("state must be a JSON object");
        }
        String snapshotId = getStrFromMap(map, "snapshotId");
        if (snapshotId == null || snapshotId.isBlank()) {
            snapshotId = UUID.randomUUID().toString();
        }
        String capturedAtStr = getStrFromMap(map, "capturedAt");
        Instant capturedAt = capturedAtStr != null
            ? Instant.parse(capturedAtStr) : Instant.now();
        String source = getStrFromMap(map, "source");
        if (source == null || source.isBlank()) source = "mcp";
        long version = 0L;
        Object versionObj = map.get("version");
        if (versionObj instanceof Number n) version = n.longValue();
        String checksum = getStrFromMap(map, "checksum");
        if (checksum == null) checksum = "";

        List<DeviceSnapshot> devices = new ArrayList<>();
        Object devicesObj = map.get("devices");
        if (devicesObj instanceof List<?> list) {
            for (Object d : list) {
                if (d instanceof Map<?, ?> dm) {
                    String deviceIri = getStrFromMap(dm, "deviceIri");
                    String deviceType = getStrFromMap(dm, "deviceType");
                    if (deviceIri != null && deviceType != null) {
                        devices.add(new DeviceSnapshot(
                            deviceIri, deviceType,
                            getStrFromMap(dm, "location"),
                            getStrFromMap(dm, "state")
                        ));
                    }
                }
            }
        }
        return new EnvironmentSnapshot(
            snapshotId, capturedAt, source, version, checksum,
            devices, List.of(), List.of()
        );
    }

    private static EnvironmentSnapshot buildEmptySnapshot() {
        return new EnvironmentSnapshot(
            UUID.randomUUID().toString(),
            Instant.now(),
            "mcp",
            0L,
            "",
            List.of(), List.of(), List.of()
        );
    }

    private static String getStrFromMap(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : null;
    }

    private static Optional<String> getOptStrFromMap(Map<?, ?> map, String key) {
        String v = getStrFromMap(map, key);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v);
    }

    private static String serializeOntologyAsTurtle(org.semanticweb.owlapi.model.OWLOntology ontology) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            org.semanticweb.owlapi.formats.TurtleDocumentFormat format =
                new org.semanticweb.owlapi.formats.TurtleDocumentFormat();
            ontology.getOWLOntologyManager().saveOntology(ontology, format, baos);
            return baos.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ontology as Turtle: " + e.getMessage(), e);
        }
    }
}
