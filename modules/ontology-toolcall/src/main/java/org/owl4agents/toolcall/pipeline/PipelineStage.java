package org.owl4agents.toolcall.pipeline;

/**
 * v0.8.7 PL-001 / D12: The 10 fixed stages of the
 * {@code ToolCallValidationPipeline}.
 *
 * <p>Stage order is NOT configurable (design D12 alternative (a)
 * rejected). Each stage carries its own short-circuit rule; when a
 * stage short-circuits, stages after it are skipped except stage 10
 * (REPORT) which always executes.</p>
 *
 * <p>The {@link #timingKey()} method returns the canonical key used
 * in {@code ToolCallValidationReport.perStageTiming}. The keys are
 * exactly the 10 entries of
 * {@link org.owl4agents.toolcall.ToolCallValidationReport#STAGE_NAMES}.</p>
 */
public enum PipelineStage {
    /** Stage 1: parse raw tool call JSON into {@code ToolCallCandidate}. */
    PARSE("parseToolCallMs"),
    /** Stage 2: resolve {@code toolName} → {@code ToolContract}. */
    LOAD_CONTRACT("loadToolContractMs"),
    /** Stage 3: JSON Schema pre-validation of candidate arguments. */
    JSON_SCHEMA("jsonSchemaMs"),
    /** Stage 4: build transient overlay (base + dynamic ABox). */
    BUILD_OVERLAY("buildOverlayMs"),
    /** Stage 5: decompose tool call into 6 OWL claim categories. */
    CLAIM_DECOMPOSITION("claimDecompositionMs"),
    /** Stage 6: batch OWL claim verification on the overlay ontology. */
    OWL_BATCH("owlBatchMs"),
    /** Stage 7: SHACL validation on the overlay ontology. */
    SHACL("shaclMs"),
    /** Stage 8: risk evaluation (high-risk blacklist + riskLevel). */
    RISK_EVAL("riskEvaluationMs"),
    /** Stage 9: decision matrix → final {@code ValidationDecision}. */
    DECISION("decisionMs"),
    /** Stage 10: build structured {@code ToolCallValidationReport}. */
    REPORT("structuredReportMs");

    private final String timingKey;

    PipelineStage(String timingKey) {
        this.timingKey = timingKey;
    }

    /**
     * The canonical key used in
     * {@code ToolCallValidationReport.perStageTiming}.
     */
    public String timingKey() {
        return timingKey;
    }

    /**
     * The 1-based stage number (PARSE=1, REPORT=10).
     */
    public int stageNumber() {
        return ordinal() + 1;
    }

    /**
     * Whether this stage always executes, even on short-circuit.
     * Only stage 10 (REPORT) is always executed.
     */
    public boolean alwaysExecutes() {
        return this == REPORT;
    }
}
