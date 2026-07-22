package org.owl4agents.toolcall;

/**
 * v0.8.7 TC-003: Validation decision emitted by the 10-stage
 * {@code ToolCallValidationPipeline}.
 *
 * <p>The pipeline SHALL NOT use a boolean {@code valid} field; every
 * {@link ToolCallValidationReport} carries one of these seven decisions.
 * The decision is the single authoritative signal consumed by callers
 * (humans, agents, or research-side automation) to determine the next
 * action.</p>
 *
 * <p>JSON serialization uses the lowercase form via {@link #jsonName()}
 * for MCP/CLI/Java API parity.</p>
 *
 * <ul>
 *   <li>{@link #EXECUTE} — all stages passed, the call may proceed.</li>
 *   <li>{@link #AUTO_REPAIR} — structural error detected (JSON Schema
 *       violation) with a known repair; the pipeline emits a non-empty
 *       {@code repairSpace}.</li>
 *   <li>{@link #CLARIFY} — the call is ambiguous and requires user
 *       clarification before any repair can be attempted.</li>
 *   <li>{@link #REQUEST_CONFIRMATION} — high-risk action (design D14
 *       blacklist or {@link RiskLevel#isHighRisk()}); a human must
 *       explicitly confirm before execution, even when all stages
 *       passed.</li>
 *   <li>{@link #REJECT} — semantic violation that cannot be repaired
 *       (OWL contradiction, SHACL Violation-severity result).</li>
 *   <li>{@link #RETRY_VALIDATION} — TOCTOU snapshot drift detected
 *       (state checksum changed); the caller must re-validate with a
 *       fresh snapshot.</li>
 *   <li>{@link #SYSTEM_ERROR} — infrastructure failure (malformed JSON,
 *       reasoner timeout, overlay creation failure). The caller must
 *       resolve the system error before retrying.</li>
 * </ul>
 */
public enum ValidationDecision {
    EXECUTE("execute"),
    AUTO_REPAIR("auto_repair"),
    CLARIFY("clarify"),
    REQUEST_CONFIRMATION("request_confirmation"),
    REJECT("reject"),
    RETRY_VALIDATION("retry_validation"),
    SYSTEM_ERROR("system_error");

    private final String jsonName;

    ValidationDecision(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    /**
     * Parse a decision string (case-insensitive). Returns
     * {@link #SYSTEM_ERROR} for unrecognized values (defensive: an
     * unrecognized decision must never silently default to EXECUTE).
     */
    public static ValidationDecision fromString(String s) {
        if (s == null || s.isBlank()) return SYSTEM_ERROR;
        for (ValidationDecision d : values()) {
            if (d.jsonName.equalsIgnoreCase(s.trim())) return d;
        }
        return SYSTEM_ERROR;
    }
}
