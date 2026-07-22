package org.owl4agents.toolcall;

/**
 * v0.8.7 TC-004: Execution status recorded on a
 * {@link ToolCallValidationReport}.
 *
 * <p>Three values cover the three pipeline outcomes per the
 * toolcall-model spec:</p>
 * <ul>
 *   <li>{@link #OK} — pipeline completed (including short-circuits at
 *       stage 3, which are normal outcomes, not errors).</li>
 *   <li>{@link #TIMEOUT} — a stage exceeded its configured timeout
 *       (typically stage 6 OWL batch / reasoner).</li>
 *   <li>{@link #ERROR} — infrastructure failure that prevented the
 *       pipeline from completing (malformed JSON, overlay creation
 *       failure, SHACL shapes load failure).</li>
 * </ul>
 *
 * <p>JSON serialization uses the lowercase form ({@code "ok"} /
 * {@code "timeout"} / {@code "error"}) via {@link #jsonName()}.</p>
 *
 * <p>This enum is intentionally distinct from
 * {@code org.owl4agents.core.model.ExecutionStatus} (which carries
 * {@code completed/timeout/error} for single-claim verification) so
 * the two pipelines cannot accidentally exchange status values.</p>
 */
public enum ToolCallExecutionStatus {
    OK("ok"),
    TIMEOUT("timeout"),
    ERROR("error");

    private final String jsonName;

    ToolCallExecutionStatus(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    /**
     * Parse an execution status string (case-insensitive). Returns
     * {@link #ERROR} for unrecognized values (defensive: an unrecognized
     * status must never silently default to OK).
     */
    public static ToolCallExecutionStatus fromString(String s) {
        if (s == null || s.isBlank()) return ERROR;
        for (ToolCallExecutionStatus v : values()) {
            if (v.jsonName.equalsIgnoreCase(s.trim())) return v;
        }
        return ERROR;
    }
}
