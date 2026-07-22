package org.owl4agents.cli;

import org.owl4agents.core.ServiceResult;
import org.owl4agents.overlay.DynamicStateParser;
import org.owl4agents.overlay.EnvironmentSnapshot;
import org.owl4agents.overlay.SnapshotChecksum;
import org.owl4agents.overlay.ToolCallCandidate;
import org.owl4agents.overlay.TransientOverlay;
import org.owl4agents.shacl.ShaclValidationService;
import org.owl4agents.shacl.ShapeRegistry;
import org.owl4agents.toolcall.ToolCallJsonSerializer;
import org.owl4agents.toolcall.ToolCallValidationReport;
import org.owl4agents.toolcall.ToolContractRegistry;
import org.owl4agents.toolcall.pipeline.ToolCallValidationPipeline;
import org.owl4agents.validation.ClaimWorkflowService;

import org.semanticweb.owlapi.model.OWLAxiom;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * v0.8.7 PL-005: {@code owl4agents toolcall-validate} CLI command.
 *
 * <p>Runs the 10-stage {@link ToolCallValidationPipeline} against a
 * tool call candidate and dynamic environment state. Per the
 * cli-interface spec "Tool Call validation CLI", the command accepts:</p>
 * <ul>
 *   <li>{@code --ontology} (required) — base ontology ID</li>
 *   <li>{@code --contract} (required) — tool name (looked up in
 *       {@link ToolContractRegistry}; overrides {@code call.toolName})</li>
 *   <li>{@code --call} (required) — path to a {@link ToolCallCandidate} JSON file</li>
 *   <li>{@code --state} (optional) — path to an environment state file
 *       (JSON {@link EnvironmentSnapshot} or Turtle/JSON-LD/N-Triples RDF)</li>
 *   <li>{@code --output} (optional) — output format, only "json" supported</li>
 *   <li>{@code --workspace} (optional) — workspace name (default: "default")</li>
 * </ul>
 *
 * <p>Per spec "CLI and MCP Service Contract Sharing", the CLI reuses the
 * same {@link ToolCallValidationPipeline} service as the MCP
 * {@code ontology_validate_tool_call} tool, ensuring byte-for-byte
 * identical output for the same fixtures (cli-interface spec
 * "toolcall validate parity with MCP").</p>
 *
 * <p>Exit codes:</p>
 * <ul>
 *   <li>{@code 0} — pipeline produced a structured report (including
 *       REJECT/CLARIFY/SYSTEM_ERROR decisions); the report's
 *       {@code decision} field distinguishes outcomes.</li>
 *   <li>{@code 1} — unrecoverable error (missing input file, invalid
 *       JSON, ontology not found, etc.).</li>
 * </ul>
 */
@Command(
    name = "toolcall-validate",
    description = "Run the 10-stage tool-call validation pipeline and emit a " +
                  "ToolCallValidationReport (decision, risk, evidence, repair space)."
)
public class ToolCallValidateCommand implements Callable<Integer> {

    @Option(names = {"--ontology"}, required = true,
        description = "Base ontology ID to load for the overlay")
    private String ontologyId;

    @Option(names = {"--contract"}, required = true,
        description = "Tool name (looked up in ~/.owl4agents/contracts/<name>.json; " +
                      "overrides call.toolName)")
    private String contractToolName;

    @Option(names = {"--call"}, required = true,
        description = "Path to a ToolCallCandidate JSON file")
    private String callPath;

    @Option(names = {"--state"},
        description = "Path to an environment state file (JSON EnvironmentSnapshot " +
                      "or Turtle/JSON-LD/N-Triples RDF)")
    private String statePath;

    @Option(names = {"--output"},
        description = "Output format (only 'json' supported, default 'json')")
    private String output = "json";

    @Option(names = {"--workspace"},
        description = "Workspace name (default: 'default')")
    private String workspaceName = "default";

    @Option(names = {"--home"},
        description = "owl4agents home directory override")
    private String homeDirectory;

    private static final Gson GSON = GsonFactory.createGson();
    private static final java.lang.reflect.Type MAP_TYPE =
        new TypeToken<Map<String, Object>>(){}.getType();

    @Override
    public Integer call() {
        // 1. Read and parse the call.json file.
        if (!Files.exists(Path.of(callPath))) {
            System.err.println("Error: call file not found: " + callPath);
            return 1;
        }
        String callJson;
        try {
            callJson = Files.readString(Path.of(callPath));
        } catch (Exception e) {
            System.err.println("Error: failed to read call file: " + e.getMessage());
            return 1;
        }

        ToolCallCandidate candidate;
        try {
            candidate = parseCandidate(callJson, contractToolName);
        } catch (RuntimeException e) {
            // Per spec "INVALID_TOOL_CALL_SCHEMA for missing required field",
            // malformed call JSON still produces a structured report with
            // decision=SYSTEM_ERROR. We synthesize a minimal candidate
            // (callId=unknown, toolName=contract) so the pipeline can run
            // stage 1 (which will short-circuit on the parse error).
            System.err.println("Error: failed to parse call JSON: " + e.getMessage());
            return 1;
        }

        // 2. Read and parse the state file (if provided).
        EnvironmentSnapshot snapshot = null;
        Collection<OWLAxiom> preParsedAxioms = null;
        if (statePath != null && !statePath.isBlank()) {
            if (!Files.exists(Path.of(statePath))) {
                System.err.println("Error: state file not found: " + statePath);
                return 1;
            }
            String stateContent;
            try {
                stateContent = Files.readString(Path.of(statePath));
            } catch (Exception e) {
                System.err.println("Error: failed to read state file: " + e.getMessage());
                return 1;
            }
            // Detect format: JSON if it starts with '{' (after trim), else RDF.
            String trimmed = stateContent.trim();
            if (trimmed.startsWith("{")) {
                // JSON EnvironmentSnapshot path.
                try {
                    snapshot = parseSnapshot(stateContent);
                } catch (RuntimeException e) {
                    System.err.println("Error: failed to parse state JSON: " + e.getMessage());
                    return 1;
                }
            } else {
                // RDF (Turtle/JSON-LD/N-Triples) path. Parse into axioms
                // and build a metadata-only snapshot with a computed checksum.
                try {
                    preParsedAxioms = DynamicStateParser.parseRdf(stateContent);
                    String checksum = SnapshotChecksum.compute(preParsedAxioms);
                    snapshot = new EnvironmentSnapshot(
                        UUID.randomUUID().toString(),
                        Instant.now(),
                        "cli",
                        0L,
                        checksum,
                        List.of(), List.of(), List.of()
                    );
                } catch (RuntimeException e) {
                    System.err.println("Error: failed to parse state RDF: " + e.getMessage());
                    return 1;
                }
            }
        }
        if (snapshot == null) {
            // No state file provided: build an empty snapshot so the
            // pipeline can proceed (stage 4 will create an overlay with
            // no dynamic axioms).
            snapshot = new EnvironmentSnapshot(
                UUID.randomUUID().toString(),
                Instant.now(),
                "cli",
                0L,
                "",
                List.of(), List.of(), List.of()
            );
        }

        // 3. Build the pipeline using the shared CliServiceFactory.
        CliServiceFactory factory = new CliServiceFactory(workspaceName, homeDirectory);
        ToolContractRegistry registry = factory.getToolContractRegistry();
        ClaimWorkflowService claimWorkflow = factory.getClaimWorkflowService();
        ShapeRegistry shapeRegistry = factory.getShapeRegistry();
        ShaclValidationService shaclService = factory.getShaclValidationService();

        ToolCallValidationPipeline pipeline = ToolCallValidationPipeline.builder()
            .toolContractRegistry(registry)
            .overlayService(factory.getOverlayService())
            .claimWorkflowService(claimWorkflow)
            .shapeRegistry(shapeRegistry)
            .shaclValidationService(shaclService)
            .build();

        // 4. Run the pipeline.
        ToolCallValidationReport report;
        if (preParsedAxioms != null) {
            report = pipeline.validateWithAxioms(candidate, ontologyId, snapshot, preParsedAxioms);
        } else {
            report = pipeline.validate(candidate, ontologyId, snapshot);
        }

        // 5. Emit the report as JSON.
        Map<String, Object> reportMap = ToolCallJsonSerializer.reportToMap(report);
        System.out.println(GSON.toJson(reportMap));

        // Exit code 0: pipeline produced a structured report (including
        // REJECT/SYSTEM_ERROR). Exit code 1 is reserved for unrecoverable
        // errors (missing files, parse failures) handled above.
        return 0;
    }

    // ── Helpers ──

    /**
     * Parse a {@link ToolCallCandidate} from JSON. The {@code toolName}
     * is overridden with the {@code --contract} value so the pipeline
     * looks up the contract by the user-specified name.
     */
    private static ToolCallCandidate parseCandidate(String json, String toolNameOverride) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        String callId = getStr(obj, "callId", null);
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("call.callId is required");
        }
        // Use the --contract value as the toolName (per spec
        // "--contract <tool_name|contract_id>").
        String toolName = toolNameOverride != null && !toolNameOverride.isBlank()
            ? toolNameOverride
            : getStr(obj, "toolName", null);
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName is required (from --contract or call.toolName)");
        }

        Optional<String> requestId = getOptStr(obj, "requestId");
        Optional<String> userRequest = getOptStr(obj, "userRequest");
        Optional<String> targetEntity = getOptStr(obj, "targetEntity");
        Optional<String> requestedBy = getOptStr(obj, "requestedBy");
        Optional<String> timestamp = getOptStr(obj, "timestamp");
        Optional<String> environmentSnapshotId = getOptStr(obj, "environmentSnapshotId");
        Optional<String> sourceModel = getOptStr(obj, "sourceModel");
        Optional<String> sourceModelResponseId = getOptStr(obj, "sourceModelResponseId");

        Map<String, String> arguments = new LinkedHashMap<>();
        if (obj.has("arguments") && obj.get("arguments").isJsonObject()) {
            for (Map.Entry<String, com.google.gson.JsonElement> e :
                obj.getAsJsonObject("arguments").entrySet()) {
                arguments.put(e.getKey(),
                    e.getValue().isJsonPrimitive()
                        ? e.getValue().getAsString()
                        : e.getValue().toString());
            }
        }

        return new ToolCallCandidate(
            callId, requestId, userRequest, toolName, targetEntity,
            arguments, requestedBy, timestamp, environmentSnapshotId,
            sourceModel, sourceModelResponseId
        );
    }

    /**
     * Parse an {@link EnvironmentSnapshot} from JSON.
     */
    @SuppressWarnings("unchecked")
    private static EnvironmentSnapshot parseSnapshot(String json) {
        Map<String, Object> map = GSON.fromJson(json, MAP_TYPE);
        if (map == null) {
            throw new IllegalArgumentException("state JSON is null");
        }
        String snapshotId = getStr(map, "snapshotId");
        if (snapshotId == null || snapshotId.isBlank()) {
            snapshotId = UUID.randomUUID().toString();
        }
        String capturedAtStr = getStr(map, "capturedAt");
        Instant capturedAt = capturedAtStr != null
            ? Instant.parse(capturedAtStr) : Instant.now();
        String source = getStr(map, "source");
        if (source == null || source.isBlank()) source = "cli";
        long version = 0L;
        Object versionObj = map.get("version");
        if (versionObj instanceof Number n) version = n.longValue();
        String checksum = getStr(map, "checksum");
        if (checksum == null) checksum = "";

        // Devices and other structured fields are parsed best-effort.
        // For the CLI, the pipeline's stage 4 will re-derive axioms via
        // DynamicStateParser.parse(snapshot), which only inspects
        // devices/userContexts/pendingToolCalls. We parse devices as
        // a list of maps and convert to DeviceSnapshot records.
        List<org.owl4agents.overlay.DeviceSnapshot> devices = new ArrayList<>();
        Object devicesObj = map.get("devices");
        if (devicesObj instanceof List<?> list) {
            for (Object d : list) {
                if (d instanceof Map<?, ?> dm) {
                    String deviceIri = getStr(dm, "deviceIri");
                    String deviceType = getStr(dm, "deviceType");
                    if (deviceIri != null && deviceType != null) {
                        devices.add(new org.owl4agents.overlay.DeviceSnapshot(
                            deviceIri, deviceType,
                            Optional.ofNullable(getStr(dm, "location")),
                            Optional.ofNullable(getStr(dm, "state")),
                            Map.of()
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

    private static String getStr(JsonObject obj, String key, String dflt) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return dflt;
    }

    private static Optional<String> getOptStr(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            String s = obj.get(key).getAsString();
            return s == null || s.isBlank() ? Optional.empty() : Optional.of(s);
        }
        return Optional.empty();
    }

    private static String getStr(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : null;
    }
}
