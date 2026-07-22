package org.owl4agents.toolcall.pipeline;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.overlay.ToolCallCandidate;
import org.owl4agents.shacl.Severity;
import org.owl4agents.shacl.ShaclValidationReport;
import org.owl4agents.shacl.ShaclViolation;
import org.owl4agents.toolcall.JsonSchemaPreValidator;
import org.owl4agents.toolcall.JsonSchemaViolation;
import org.owl4agents.toolcall.ToolContract;
import org.owl4agents.toolcall.ValidationDecision;

import java.util.List;
import java.util.Optional;

/**
 * v0.8.7 PL-002 / D12: Short-circuit strategy for the 10-stage pipeline.
 *
 * <p>Each stage (1-9) applies a short-circuit rule at its entry. When a
 * stage short-circuits, subsequent stages (except stage 10 REPORT) are
 * skipped. The high-risk override is applied centrally by
 * {@link PipelineContext#resolvedShortCircuitDecision()} so each rule
 * here returns its "natural" decision; the override forces
 * {@link ValidationDecision#REQUEST_CONFIRMATION} for high-risk actions
 * (except {@link ValidationDecision#SYSTEM_ERROR}).</p>
 *
 * <p>The short-circuit rules are pure functions over the pipeline state.
 * They do NOT mutate the context; the pipeline orchestrator calls
 * {@link PipelineContext#shortCircuitAt(PipelineStage, ValidationDecision, String)}
 * with the returned decision.</p>
 *
 * <p>Rules (per spec "Short-Circuit Strategy"):</p>
 * <ul>
 *   <li><strong>Malformed JSON</strong> (stage 1 failure) →
 *       {@link ValidationDecision#SYSTEM_ERROR} or
 *       {@link ValidationDecision#REJECT}; executionStatus=error.</li>
 *   <li><strong>Unknown tool name</strong> (stage 2 failure) →
 *       {@link ValidationDecision#REJECT} with the list of known
 *       contracts attached.</li>
 *   <li><strong>Missing required parameter</strong> (stage 3 failure) →
 *       {@link ValidationDecision#AUTO_REPAIR} or
 *       {@link ValidationDecision#CLARIFY}; stages 4-8 skipped;
 *       reasoner not started.</li>
 *   <li><strong>Unknown device</strong> (stage 4 or 5 failure) →
 *       {@link ValidationDecision#CLARIFY} or
 *       {@link ValidationDecision#REJECT}; full SHACL skipped.</li>
 *   <li><strong>OWL contradiction</strong> (stage 6 failure) →
 *       {@link ValidationDecision#REJECT} or
 *       {@link ValidationDecision#AUTO_REPAIR}; stage 7 skipped.</li>
 *   <li><strong>SHACL Violation</strong> (stage 7) →
 *       {@link ValidationDecision#REJECT} or
 *       {@link ValidationDecision#AUTO_REPAIR} based on severity;
 *       {@code Warning} does not block.</li>
 *   <li><strong>Reasoner timeout</strong> (stage 6) →
 *       {@link ValidationDecision#SYSTEM_ERROR};
 *       executionStatus=timeout; SHALL NOT be treated as UNKNOWN.</li>
 * </ul>
 */
public final class ShortCircuitStrategy {

    private ShortCircuitStrategy() {
        // utility class; no instances
    }

    /**
     * Stage 1 (Parse) short-circuit rule.
     *
     * <p>When the raw tool call input is not valid JSON, the pipeline
     * short-circuits with {@link ValidationDecision#SYSTEM_ERROR} (per
     * the "Malformed JSON short-circuit" scenario). The high-risk
     * override does NOT apply because the contract could not be loaded.</p>
     *
     * @param parseError the parse error message (non-null indicates failure)
     * @return a non-null {@link ShortCircuitResult} when the parse failed;
     *         {@link Optional#empty()} when the parse succeeded
     */
    public static Optional<ShortCircuitResult> forParseFailure(String parseError) {
        if (parseError == null || parseError.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ShortCircuitResult(
            PipelineStage.PARSE,
            ValidationDecision.SYSTEM_ERROR,
            "Malformed tool call JSON: " + parseError,
            ErrorCode.INVALID_ARGUMENTS,
            ToolCallExecutionStatusForPipeline.ERROR
        ));
    }

    /**
     * Stage 2 (Load Contract) short-circuit rule for unknown tool name.
     *
     * <p>When the tool name has no registered contract, the pipeline
     * short-circuits with {@link ValidationDecision#REJECT} and attaches
     * the list of known contracts. The high-risk override does NOT apply
     * because no contract means no riskLevel to check.</p>
     *
     * @param toolName           the requested tool name
     * @param knownContracts     the list of known tool names (for the
     *                           "list of known contracts attached" hint)
     * @return a non-null {@link ShortCircuitResult} when the tool name
     *         is unknown; {@link Optional#empty()} when the contract
     *         was loaded successfully
     */
    public static Optional<ShortCircuitResult> forUnknownToolName(
            String toolName, List<String> knownContracts) {
        if (knownContracts != null && knownContracts.contains(toolName)) {
            return Optional.empty();
        }
        String hint = knownContracts == null || knownContracts.isEmpty()
            ? "(no contracts registered)"
            : knownContracts.toString();
        return Optional.of(new ShortCircuitResult(
            PipelineStage.LOAD_CONTRACT,
            ValidationDecision.REJECT,
            "Unknown tool name '" + toolName + "'. Known contracts: " + hint,
            ErrorCode.TOOL_CONTRACT_NOT_FOUND,
            ToolCallExecutionStatusForPipeline.ERROR
        ));
    }

    /**
     * Stage 2 (Load Contract) short-circuit rule for an unregistered
     * shapeSetId referenced by the contract.
     *
     * <p>Per the "Unregistered shapeSetId in contract short-circuits at
     * stage 2" scenario, when the loaded contract references a
     * shapeSetId that is not registered, the pipeline short-circuits
     * with {@link ValidationDecision#REJECT} and an error code
     * {@link ErrorCode#SHAPE_SET_NOT_FOUND}.</p>
     */
    public static Optional<ShortCircuitResult> forUnregisteredShapeSetId(
            String shapeSetId) {
        if (shapeSetId == null || shapeSetId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ShortCircuitResult(
            PipelineStage.LOAD_CONTRACT,
            ValidationDecision.REJECT,
            "Contract references unregistered shapeSetId '" + shapeSetId + "'",
            ErrorCode.SHAPE_SET_NOT_FOUND,
            ToolCallExecutionStatusForPipeline.ERROR
        ));
    }

    /**
     * Stage 3 (JSON Schema) short-circuit rule.
     *
     * <p>When the JSON Schema validator produces a non-empty violation
     * list, the pipeline short-circuits with the decision suggested by
     * {@link JsonSchemaPreValidator#suggestShortCircuitDecision(List)}.
     * The high-risk override (applied by
     * {@link PipelineContext#resolvedShortCircuitDecision()}) forces
     * {@link ValidationDecision#REQUEST_CONFIRMATION} when the contract
     * marks the tool as high-risk.</p>
     *
     * <p>Stages 4-8 are skipped (reasoner not started). The
     * {@code repairSpace} is populated with the suggested repairs so
     * the high-risk confirmer (or AUTO_REPAIR LLM loop) can review them.</p>
     */
    public static Optional<ShortCircuitResult> forJsonSchemaFailure(
            List<JsonSchemaViolation> violations,
            JsonSchemaPreValidator validator) {
        if (violations == null || violations.isEmpty()) {
            return Optional.empty();
        }
        ValidationDecision suggested = validator == null
            ? ValidationDecision.AUTO_REPAIR
            : validator.suggestShortCircuitDecision(violations);
        String summary = JsonSchemaPreValidator.summarize(violations);
        return Optional.of(new ShortCircuitResult(
            PipelineStage.JSON_SCHEMA,
            suggested,
            "JSON Schema violations: " + summary,
            null,  // no error code — this is a repairable failure, not a system error
            ToolCallExecutionStatusForPipeline.OK
        ));
    }

    /**
     * Stage 4 (Build Overlay) short-circuit rule for an invalid
     * EnvironmentSnapshot.
     *
     * <p>Per the "Invalid EnvironmentSnapshot short-circuits at stage 4"
     * scenario, when the snapshot fails validation (e.g. snapshotId is
     * not a UUID, capturedAt is null, or checksum is malformed), the
     * pipeline short-circuits with
     * {@link ValidationDecision#SYSTEM_ERROR}. The high-risk override
     * does NOT apply because this is a SYSTEM_ERROR.</p>
     */
    public static Optional<ShortCircuitResult> forInvalidSnapshot(String reason) {
        if (reason == null || reason.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ShortCircuitResult(
            PipelineStage.BUILD_OVERLAY,
            ValidationDecision.SYSTEM_ERROR,
            "Invalid EnvironmentSnapshot: " + reason,
            ErrorCode.INVALID_ARGUMENTS,
            ToolCallExecutionStatusForPipeline.ERROR
        ));
    }

    /**
     * Stage 4 (Build Overlay) short-circuit rule for a base ontology
     * load failure.
     *
     * <p>Per the "Base ontology load failure short-circuits at stage 4"
     * scenario, when {@code OntologyCache.getOrCreate(baseOntologyId)}
     * fails, the pipeline short-circuits with
     * {@link ValidationDecision#SYSTEM_ERROR} and an error code
     * {@link ErrorCode#ONTOLOGY_NOT_FOUND}.</p>
     */
    public static Optional<ShortCircuitResult> forOntologyLoadFailure(String ontologyId) {
        return Optional.of(new ShortCircuitResult(
            PipelineStage.BUILD_OVERLAY,
            ValidationDecision.SYSTEM_ERROR,
            "Failed to load base ontology '" + ontologyId + "'",
            ErrorCode.ONTOLOGY_NOT_FOUND,
            ToolCallExecutionStatusForPipeline.ERROR
        ));
    }

    /**
     * Stage 4 (Build Overlay) short-circuit rule for a
     * DynamicStateParser parse failure.
     */
    public static Optional<ShortCircuitResult> forDynamicStateParseFailure(String reason) {
        return Optional.of(new ShortCircuitResult(
            PipelineStage.BUILD_OVERLAY,
            ValidationDecision.SYSTEM_ERROR,
            "DynamicStateParser failed: " + reason,
            ErrorCode.INVALID_ARGUMENTS,
            ToolCallExecutionStatusForPipeline.ERROR
        ));
    }

    /**
     * Stage 4 (Build Overlay) short-circuit rule for a createOverlay
     * failure.
     *
     * <p>Per the "createOverlay failure short-circuits at stage 4"
     * scenario, when
     * {@code TransientOntologyOverlayService.createOverlay(...)} returns
     * a {@link ServiceResult} error, the pipeline short-circuits with
     * {@link ValidationDecision#SYSTEM_ERROR}.</p>
     */
    public static Optional<ShortCircuitResult> forOverlayCreationFailure(ServiceError error) {
        if (error == null) {
            return Optional.empty();
        }
        return Optional.of(new ShortCircuitResult(
            PipelineStage.BUILD_OVERLAY,
            ValidationDecision.SYSTEM_ERROR,
            "Overlay creation failed: " + error.code().code() + " " + error.message(),
            error.code(),
            ToolCallExecutionStatusForPipeline.ERROR
        ));
    }

    /**
     * Stage 5 (Claim Decomposition) short-circuit rule for an unknown
     * target device.
     *
     * <p>Per the "Unknown device short-circuits SHACL" scenario, when
     * the {@code targetEntity} is not present in the overlay ontology,
     * the pipeline short-circuits with
     * {@link ValidationDecision#CLARIFY} (or
     * {@link ValidationDecision#REJECT} at the caller's discretion).
     * SHACL is skipped. The high-risk override forces
     * {@link ValidationDecision#REQUEST_CONFIRMATION} when the contract
     * marks the tool as high-risk.</p>
     */
    public static Optional<ShortCircuitResult> forUnknownDevice(String targetEntity) {
        return Optional.of(new ShortCircuitResult(
            PipelineStage.CLAIM_DECOMPOSITION,
            ValidationDecision.CLARIFY,
            "Unknown target entity '" + targetEntity
                + "' not present in the overlay ontology",
            null,
            ToolCallExecutionStatusForPipeline.OK
        ));
    }

    /**
     * Stage 6 (OWL Batch) short-circuit rule for an OWL contradiction.
     *
     * <p>Per the "OWL contradiction short-circuits SHACL" scenario, when
     * stage 6 detects an OWL inconsistency (e.g. target entity asserted
     * as two disjoint classes), stage 7 (SHACL) is skipped because
     * running SHACL on an inconsistent ontology is meaningless. The
     * decision is {@link ValidationDecision#REJECT} (or
     * {@link ValidationDecision#AUTO_REPAIR} when a unique fix exists).
     * The high-risk override forces
     * {@link ValidationDecision#REQUEST_CONFIRMATION} for high-risk
     * actions.</p>
     */
    public static Optional<ShortCircuitResult> forOwlContradiction(String evidence) {
        return Optional.of(new ShortCircuitResult(
            PipelineStage.OWL_BATCH,
            ValidationDecision.REJECT,
            "OWL contradiction detected: " + evidence,
            null,
            ToolCallExecutionStatusForPipeline.OK
        ));
    }

    /**
     * Stage 6 (OWL Batch) short-circuit rule for a reasoner timeout.
     *
     * <p>Per the "Reasoner timeout is SYSTEM_ERROR not UNKNOWN" scenario,
     * when the reasoner exceeds its timeout, the pipeline short-circuits
     * with {@link ValidationDecision#SYSTEM_ERROR} and
     * executionStatus=timeout. The high-risk override does NOT apply
     * because SYSTEM_ERROR is not a confirmable state.</p>
     */
    public static Optional<ShortCircuitResult> forReasonerTimeout() {
        return Optional.of(new ShortCircuitResult(
            PipelineStage.OWL_BATCH,
            ValidationDecision.SYSTEM_ERROR,
            "Reasoner exceeded timeout during OWL batch verification",
            ErrorCode.REASONER_TIMEOUT,
            ToolCallExecutionStatusForPipeline.TIMEOUT
        ));
    }

    /**
     * Stage 7 (SHACL) short-circuit rule for a Violation-severity result.
     *
     * <p>Per the "SHACL Violation short-circuits" rule, when stage 7
     * produces any Violation-severity results, the decision is
     * {@link ValidationDecision#REJECT} (or
     * {@link ValidationDecision#AUTO_REPAIR} when the violation has a
     * {@code repairHint}). {@code Warning}-severity results do NOT
     * short-circuit (per "SHACL Warning does not block"). The high-risk
     * override forces
     * {@link ValidationDecision#REQUEST_CONFIRMATION} for high-risk
     * actions.</p>
     */
    public static Optional<ShortCircuitResult> forShaclViolation(ShaclValidationReport report) {
        if (report == null || report.violations().isEmpty()) {
            return Optional.empty();
        }
        // If any violation has a repairHint, AUTO_REPAIR is possible.
        boolean anyRepairable = report.violations().stream()
            .anyMatch(v -> v != null && v.repairHint() != null && !v.repairHint().isBlank());
        ValidationDecision decision = anyRepairable
            ? ValidationDecision.AUTO_REPAIR
            : ValidationDecision.REJECT;
        StringBuilder sb = new StringBuilder("SHACL Violation-severity results: ");
        for (int i = 0; i < report.violations().size(); i++) {
            if (i > 0) sb.append("; ");
            ShaclViolation v = report.violations().get(i);
            sb.append(v.message() != null ? v.message() : "(no message)");
        }
        return Optional.of(new ShortCircuitResult(
            PipelineStage.SHACL,
            decision,
            sb.toString(),
            null,
            ToolCallExecutionStatusForPipeline.OK
        ));
    }

    /**
     * Stage 7 (SHACL) short-circuit rule for a ShapeRegistry.resolve
     * failure.
     *
     * <p>Per the "ShapeRegistry.resolve failure short-circuits at stage
     * 7" scenario, when {@code ShapeRegistry.resolve(shapeSetId)} fails
     * (e.g. the shapes file was deleted from disk), the pipeline
     * short-circuits with
     * {@link ValidationDecision#SYSTEM_ERROR} and an error code
     * {@link ErrorCode#SHACL_SHAPES_LOAD_FAILED}.</p>
     */
    public static Optional<ShortCircuitResult> forShaclShapesLoadFailure(String shapeSetId) {
        return Optional.of(new ShortCircuitResult(
            PipelineStage.SHACL,
            ValidationDecision.SYSTEM_ERROR,
            "Failed to load shapes for shapeSetId '" + shapeSetId + "'",
            ErrorCode.SHACL_SHAPES_LOAD_FAILED,
            ToolCallExecutionStatusForPipeline.ERROR
        ));
    }

    // ────────────────────────────────────────────────────────────────────
    // Result record
    // ────────────────────────────────────────────────────────────────────

    /**
     * A short-circuit decision produced by one of the rules above. The
     * pipeline orchestrator applies the high-risk override (via
     * {@link PipelineContext#resolvedShortCircuitDecision()}) before
     * finalizing the decision.
     *
     * @param stage            the stage at which the short-circuit occurred
     * @param decision         the natural (pre-override) decision
     * @param reason           human-readable explanation
     * @param errorCode        optional error code (null for repairable
     *                         failures like AUTO_REPAIR/CLARIFY)
     * @param executionStatus  the pipeline execution status to set on the
     *                         report (OK / TIMEOUT / ERROR)
     */
    public record ShortCircuitResult(
        PipelineStage stage,
        ValidationDecision decision,
        String reason,
        ErrorCode errorCode,
        ToolCallExecutionStatusForPipeline executionStatus
    ) {}

    /**
     * Local mirror of {@link org.owl4agents.toolcall.ToolCallExecutionStatus}
     * to avoid a cross-package import cycle (the pipeline package is
     * nested inside the toolcall package, but using the outer enum
     * directly would couple the strategy to the report format).
     *
     * <p>The pipeline orchestrator converts this to
     * {@link org.owl4agents.toolcall.ToolCallExecutionStatus} when
     * building the report.</p>
     */
    public enum ToolCallExecutionStatusForPipeline {
        OK,
        TIMEOUT,
        ERROR;

        public org.owl4agents.toolcall.ToolCallExecutionStatus toReportStatus() {
            return switch (this) {
                case OK -> org.owl4agents.toolcall.ToolCallExecutionStatus.OK;
                case TIMEOUT -> org.owl4agents.toolcall.ToolCallExecutionStatus.TIMEOUT;
                case ERROR -> org.owl4agents.toolcall.ToolCallExecutionStatus.ERROR;
            };
        }
    }
}
