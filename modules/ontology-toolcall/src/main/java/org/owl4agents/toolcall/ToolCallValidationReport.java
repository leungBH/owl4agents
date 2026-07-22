package org.owl4agents.toolcall;

import org.owl4agents.overlay.StateSnapshotId;
import org.owl4agents.shacl.ShaclViolation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v0.8.7 TC-004: Report produced by the 10-stage
 * {@code ToolCallValidationPipeline}.
 *
 * <p>Every pipeline invocation, including short-circuited ones,
 * produces a complete 13-field report. Short-circuited reports
 * populate un-executed stages' timing entries with {@code 0} and
 * leave the corresponding result lists empty.</p>
 *
 * <p>JSON serialization is byte-for-byte identical across MCP, CLI,
 * and Java API via
 * {@link ToolCallJsonSerializer#reportToMap(ToolCallValidationReport)}.</p>
 *
 * @param schemaVersion        report schema version (currently "1.0")
 * @param callId               unique tool call identifier from
 *                             {@code ToolCallCandidate.callId}
 * @param executionStatus      {@link ToolCallExecutionStatus#OK} for
 *                             completed/short-circuited runs,
 *                             {@link ToolCallExecutionStatus#TIMEOUT} for
 *                             a stage timeout,
 *                             {@link ToolCallExecutionStatus#ERROR} for
 *                             infrastructure failures
 * @param decision             one of the seven {@link ValidationDecision}
 *                             values; never a boolean {@code valid} field
 * @param riskLevel            risk level from the contract (or
 *                             {@link RiskLevel#LOW} when no contract
 *                             applied)
 * @param jsonSchemaViolations list of {@link JsonSchemaViolation} from
 *                             stage 3; empty when stage 3 passed
 * @param owlClaimResults      list of OWL claim verification results from
 *                             stage 6; empty when stage 6 was skipped
 *                             (short-circuit or system error)
 * @param shaclViolations      list of {@link ShaclViolation} from stage 7;
 *                             empty when stage 7 was skipped
 * @param stateVersion         {@link StateSnapshotId} reference of the
 *                             {@code EnvironmentSnapshot} used for
 *                             validation; empty for short-circuits before
 *                             stage 4 (e.g. malformed JSON)
 * @param evidence             list of evidence triples / objects gathered
 *                             across stages 5-8; empty for short-circuits
 * @param repairSpace          list of suggested repairs for
 *                             {@link ValidationDecision#AUTO_REPAIR} and
 *                             {@link ValidationDecision#CLARIFY};
 *                             populated even for
 *                             {@link ValidationDecision#REQUEST_CONFIRMATION}
 *                             so a confirming operator has context
 * @param perStageTiming       map of stage name to milliseconds spent;
 *                             contains entries for all 10 stages (zero
 *                             for un-executed stages)
 * @param totalMs              total wall-clock elapsed time in milliseconds;
 *                             strictly greater than 0 for any pipeline run
 */
public record ToolCallValidationReport(
    String schemaVersion,
    String callId,
    ToolCallExecutionStatus executionStatus,
    ValidationDecision decision,
    RiskLevel riskLevel,
    List<JsonSchemaViolation> jsonSchemaViolations,
    List<Map<String, Object>> owlClaimResults,
    List<ShaclViolation> shaclViolations,
    Optional<StateSnapshotId> stateVersion,
    List<Map<String, Object>> evidence,
    List<String> repairSpace,
    Map<String, Long> perStageTiming,
    long totalMs
) {
    /** Current report schema version (reserved for future evolution). */
    public static final String SCHEMA_VERSION = "1.0";

    /** The 10 fixed stage names that {@code perStageTiming} MUST contain. */
    public static final List<String> STAGE_NAMES = List.of(
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
    );

    public ToolCallValidationReport {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("ToolCallValidationReport.callId must not be blank");
        }
        if (executionStatus == null) executionStatus = ToolCallExecutionStatus.ERROR;
        if (decision == null) decision = ValidationDecision.SYSTEM_ERROR;
        if (riskLevel == null) riskLevel = RiskLevel.LOW;
        if (jsonSchemaViolations == null) jsonSchemaViolations = List.of();
        else jsonSchemaViolations = List.copyOf(jsonSchemaViolations);
        if (owlClaimResults == null) owlClaimResults = List.of();
        else owlClaimResults = List.copyOf(owlClaimResults);
        if (shaclViolations == null) shaclViolations = List.of();
        else shaclViolations = List.copyOf(shaclViolations);
        if (stateVersion == null) stateVersion = Optional.empty();
        if (evidence == null) evidence = List.of();
        else evidence = List.copyOf(evidence);
        if (repairSpace == null) repairSpace = List.of();
        else repairSpace = List.copyOf(repairSpace);
        if (perStageTiming == null) {
            perStageTiming = emptyTiming();
        } else {
            // Defensive copy + fill missing stages with 0.
            Map<String, Long> merged = new LinkedHashMap<>(emptyTiming());
            for (Map.Entry<String, Long> e : perStageTiming.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
            perStageTiming = Map.copyOf(merged);
        }
        if (totalMs < 0) totalMs = 0;
    }

    /**
     * Build a per-stage timing map with all 10 stage entries set to 0.
     * Used as the default for short-circuited reports and as the base
     * for merging partial timing maps.
     */
    public static Map<String, Long> emptyTiming() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String name : STAGE_NAMES) {
            m.put(name, 0L);
        }
        return m;
    }

    /**
     * Whether the pipeline short-circuited at stage 3 (JSON Schema
     * failure). Used by callers to know that stages 4-9 were not
     * executed and {@code owlClaimResults} / {@code shaclViolations}
     * are empty by construction (not by coincidence).
     */
    public boolean shortCircuitedAtJsonSchema() {
        return !jsonSchemaViolations.isEmpty()
            && owlClaimResults.isEmpty()
            && shaclViolations.isEmpty();
    }
}
