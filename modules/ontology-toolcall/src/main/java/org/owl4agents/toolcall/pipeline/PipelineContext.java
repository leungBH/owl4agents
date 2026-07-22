package org.owl4agents.toolcall.pipeline;

import org.owl4agents.overlay.EnvironmentSnapshot;
import org.owl4agents.overlay.StateSnapshotId;
import org.owl4agents.overlay.ToolCallCandidate;
import org.owl4agents.overlay.TransientOverlay;
import org.owl4agents.shacl.ShaclValidationReport;
import org.owl4agents.toolcall.JsonSchemaViolation;
import org.owl4agents.toolcall.RiskLevel;
import org.owl4agents.toolcall.ToolContract;
import org.owl4agents.toolcall.ValidationDecision;
import org.owl4agents.toolcall.decomposition.ToolCallClaimBatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v0.8.7 PL-001 / D12: Mutable context carried through the 10 pipeline
 * stages. Each stage reads from the context (set by earlier stages) and
 * writes its outputs back. The final stage ({@link PipelineStage#REPORT})
 * reads all accumulated state to build a
 * {@link org.owl4agents.toolcall.ToolCallValidationReport}.
 *
 * <p>The context also carries the per-stage timing map and the
 * short-circuit state: when a stage calls
 * {@link #shortCircuitAt(PipelineStage, ValidationDecision, String)},
 * subsequent stages (except {@link PipelineStage#REPORT}) are skipped.</p>
 *
 * <p>This class is NOT thread-safe. A pipeline run is single-threaded
 * per invocation; concurrent invocations must each construct their own
 * {@code PipelineContext}.</p>
 */
public final class PipelineContext {

    // ── Stage outputs ──
    private ToolCallCandidate candidate;
    private ToolContract contract;
    private List<JsonSchemaViolation> jsonSchemaViolations = new ArrayList<>();
    private TransientOverlay overlay;
    private EnvironmentSnapshot environmentSnapshot;
    private ToolCallClaimBatch claimBatch;
    private org.owl4agents.core.model.AnswerVerificationReport owlReport;
    private ShaclValidationReport shaclReport;
    private RiskLevel riskLevel = RiskLevel.LOW;
    private ValidationDecision decision = ValidationDecision.EXECUTE;
    private org.owl4agents.core.ErrorCode errorCode;
    private String errorMessage;

    // ── Pipeline state ──
    private final Map<String, Long> timing = new LinkedHashMap<>();
    private PipelineStage shortCircuitedAt;
    private ValidationDecision shortCircuitDecision;
    private String shortCircuitReason;
    private org.owl4agents.toolcall.ToolCallExecutionStatus shortCircuitExecutionStatus;
    private boolean highRisk;
    private final List<String> repairSpace = new ArrayList<>();
    private final List<Map<String, Object>> evidence = new ArrayList<>();
    private final long pipelineStartNanos;

    public PipelineContext() {
        this.pipelineStartNanos = System.nanoTime();
        for (PipelineStage stage : PipelineStage.values()) {
            timing.put(stage.timingKey(), 0L);
        }
    }

    // ── Stage output setters ──

    public void setCandidate(ToolCallCandidate candidate) {
        this.candidate = candidate;
    }

    public void setContract(ToolContract contract) {
        this.contract = contract;
    }

    public void setJsonSchemaViolations(List<JsonSchemaViolation> violations) {
        this.jsonSchemaViolations = violations == null ? List.of() : new ArrayList<>(violations);
    }

    public void setOverlay(TransientOverlay overlay) {
        this.overlay = overlay;
    }

    public void setEnvironmentSnapshot(EnvironmentSnapshot snapshot) {
        this.environmentSnapshot = snapshot;
    }

    public void setClaimBatch(ToolCallClaimBatch batch) {
        this.claimBatch = batch;
    }

    public void setOwlReport(org.owl4agents.core.model.AnswerVerificationReport report) {
        this.owlReport = report;
    }

    public void setShaclReport(ShaclValidationReport report) {
        this.shaclReport = report;
    }

    public void setRiskLevel(RiskLevel level) {
        this.riskLevel = level == null ? RiskLevel.LOW : level;
    }

    public void setDecision(ValidationDecision decision) {
        this.decision = decision == null ? ValidationDecision.SYSTEM_ERROR : decision;
    }

    public void setError(org.owl4agents.core.ErrorCode code, String message) {
        this.errorCode = code;
        this.errorMessage = message;
    }

    public void setHighRisk(boolean highRisk) {
        this.highRisk = highRisk;
    }

    public void addRepair(String suggestion) {
        if (suggestion != null && !suggestion.isBlank()) {
            repairSpace.add(suggestion);
        }
    }

    public void addEvidence(Map<String, Object> entry) {
        if (entry != null) {
            evidence.add(entry);
        }
    }

    // ── Stage output getters ──

    public ToolCallCandidate candidate() {
        return candidate;
    }

    public ToolContract contract() {
        return contract;
    }

    public List<JsonSchemaViolation> jsonSchemaViolations() {
        return jsonSchemaViolations;
    }

    public TransientOverlay overlay() {
        return overlay;
    }

    public EnvironmentSnapshot environmentSnapshot() {
        return environmentSnapshot;
    }

    public ToolCallClaimBatch claimBatch() {
        return claimBatch;
    }

    public org.owl4agents.core.model.AnswerVerificationReport owlReport() {
        return owlReport;
    }

    public ShaclValidationReport shaclReport() {
        return shaclReport;
    }

    public RiskLevel riskLevel() {
        return riskLevel;
    }

    public ValidationDecision decision() {
        return decision;
    }

    public Optional<org.owl4agents.core.ErrorCode> errorCode() {
        return Optional.ofNullable(errorCode);
    }

    public Optional<String> errorMessage() {
        return Optional.ofNullable(errorMessage);
    }

    public boolean isHighRisk() {
        return highRisk;
    }

    public List<String> repairSpace() {
        return repairSpace;
    }

    public List<Map<String, Object>> evidence() {
        return evidence;
    }

    // ── Timing ──

    /**
     * Record the elapsed milliseconds for a stage. Called by the pipeline
     * after each stage completes (or short-circuits).
     */
    public void recordTiming(PipelineStage stage, long elapsedMs) {
        if (stage != null) {
            timing.put(stage.timingKey(), Math.max(0L, elapsedMs));
        }
    }

    public Map<String, Long> timing() {
        return new LinkedHashMap<>(timing);
    }

    /**
     * Total wall-clock elapsed time in milliseconds since the context was
     * created (i.e. since the pipeline started).
     */
    public long totalElapsedMs() {
        return Math.max(0L, (System.nanoTime() - pipelineStartNanos) / 1_000_000);
    }

    // ── Short-circuit state ──

    /**
     * Mark the pipeline as short-circuited at the given stage with the
     * given decision and reason. Subsequent stages (except REPORT) MUST
     * check {@link #isShortCircuited()} and skip themselves.
     *
     * <p>This overload defaults the execution status to
     * {@link org.owl4agents.toolcall.ToolCallExecutionStatus#OK}. It is
     * retained for direct callers that produce
     * {@link ValidationDecision#SYSTEM_ERROR} (the report-level status
     * is then derived from the decision by
     * {@code ToolCallValidationPipeline.computeExecutionStatus}). Callers
     * that have an explicit execution status (e.g. from a
     * {@link ShortCircuitStrategy.ShortCircuitResult}) SHOULD use
     * {@link #shortCircuitAt(PipelineStage, ValidationDecision, String,
     * org.owl4agents.toolcall.ToolCallExecutionStatus)} instead.</p>
     *
     * <p>The {@code highRisk} flag (set by the pipeline after stage 2)
     * is consulted by {@link #resolvedShortCircuitDecision()} to force
     * {@link ValidationDecision#REQUEST_CONFIRMATION} for high-risk
     * actions, except when the short-circuit decision is
     * {@link ValidationDecision#SYSTEM_ERROR}.</p>
     */
    public void shortCircuitAt(PipelineStage stage,
                                ValidationDecision decision,
                                String reason) {
        shortCircuitAt(stage, decision, reason,
            org.owl4agents.toolcall.ToolCallExecutionStatus.OK);
    }

    /**
     * Mark the pipeline as short-circuited at the given stage with the
     * given decision, reason, and explicit execution status. The
     * execution status is propagated verbatim to the final
     * {@link org.owl4agents.toolcall.ToolCallValidationReport} (subject
     * to the high-risk override, which only affects the decision, not
     * the execution status).
     */
    public void shortCircuitAt(PipelineStage stage,
                                ValidationDecision decision,
                                String reason,
                                org.owl4agents.toolcall.ToolCallExecutionStatus executionStatus) {
        this.shortCircuitedAt = stage;
        this.shortCircuitDecision = decision;
        this.shortCircuitReason = reason;
        this.shortCircuitExecutionStatus = executionStatus == null
            ? org.owl4agents.toolcall.ToolCallExecutionStatus.OK
            : executionStatus;
    }

    public boolean isShortCircuited() {
        return shortCircuitedAt != null;
    }

    public Optional<PipelineStage> shortCircuitedAt() {
        return Optional.ofNullable(shortCircuitedAt);
    }

    public Optional<ValidationDecision> shortCircuitDecision() {
        return Optional.ofNullable(shortCircuitDecision);
    }

    public Optional<String> shortCircuitReason() {
        return Optional.ofNullable(shortCircuitReason);
    }

    /**
     * The execution status captured at short-circuit time (or empty if
     * the pipeline has not short-circuited). The pipeline's
     * {@code computeExecutionStatus} consults this before falling back
     * to decision/errorCode inference.
     */
    public Optional<org.owl4agents.toolcall.ToolCallExecutionStatus> shortCircuitExecutionStatus() {
        return Optional.ofNullable(shortCircuitExecutionStatus);
    }

    /**
     * The effective short-circuit decision after applying the high-risk
     * override. Per spec "High-risk short-circuit override", when the
     * candidate is high-risk AND the short-circuit decision is
     * {@link ValidationDecision#AUTO_REPAIR} or
     * {@link ValidationDecision#CLARIFY}, the effective decision is
     * forced to {@link ValidationDecision#REQUEST_CONFIRMATION}.
     * {@link ValidationDecision#SYSTEM_ERROR} is never overridden.
     */
    public ValidationDecision resolvedShortCircuitDecision() {
        if (shortCircuitDecision == null) {
            return decision;
        }
        if (highRisk
            && shortCircuitDecision != ValidationDecision.SYSTEM_ERROR
            && shortCircuitDecision != ValidationDecision.REJECT
            && shortCircuitDecision != ValidationDecision.RETRY_VALIDATION) {
            return ValidationDecision.REQUEST_CONFIRMATION;
        }
        return shortCircuitDecision;
    }

    /**
     * The {@link StateSnapshotId} for the report's {@code stateVersion}
     * field. Empty when the pipeline short-circuited before stage 4
     * (no snapshot was loaded).
     */
    public Optional<StateSnapshotId> stateVersion() {
        if (environmentSnapshot != null) {
            return Optional.of(environmentSnapshot.toSnapshotId());
        }
        return Optional.empty();
    }

    /**
     * Release the overlay (if any) to reclaim heap. Called by the pipeline
     * in a finally block or try-with-resources equivalent.
     */
    public void releaseOverlay() {
        if (overlay != null) {
            try {
                overlay.release();
            } catch (RuntimeException ignored) {
                // Best-effort cleanup; the overlay's manager will be GC'd.
            }
            overlay = null;
        }
    }
}
