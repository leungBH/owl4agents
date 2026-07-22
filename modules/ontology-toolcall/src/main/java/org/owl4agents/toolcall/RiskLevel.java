package org.owl4agents.toolcall;

/**
 * v0.8.7 TC-002 / D14: Risk level associated with a {@link ToolContract}.
 *
 * <p>The four levels are ordered by severity ({@link #LOW} < {@link #MEDIUM}
 * < {@link #HIGH} < {@link #CRITICAL}). The pipeline uses the contract's
 * risk level (combined with the high-risk action blacklist, design D14) to
 * decide whether a passing validation must still surface
 * {@link ValidationDecision#REQUEST_CONFIRMATION}.</p>
 *
 * <p>JSON serialization uses the lowercase form ({@code "low"} /
 * {@code "medium"} / {@code "high"} / {@code "critical"}) via
 * {@link #jsonName()} so MCP, CLI, and Java API outputs are byte-for-byte
 * identical.</p>
 */
public enum RiskLevel {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    CRITICAL("critical");

    private final String jsonName;

    RiskLevel(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    /**
     * Parse a risk level string (case-insensitive). Returns
     * {@link #MEDIUM} for unrecognized values (defensive default that
     * surfaces confirmation rather than silently allowing high risk).
     */
    public static RiskLevel fromString(String s) {
        if (s == null || s.isBlank()) return MEDIUM;
        for (RiskLevel r : values()) {
            if (r.jsonName.equalsIgnoreCase(s.trim())) return r;
        }
        return MEDIUM;
    }

    /**
     * Whether this risk level triggers the high-risk confirmation gate
     * (design D14: {@code HIGH} or {@code CRITICAL} -> ?force
     * {@link ValidationDecision#REQUEST_CONFIRMATION}).
     */
    public boolean isHighRisk() {
        return this == HIGH || this == CRITICAL;
    }
}
