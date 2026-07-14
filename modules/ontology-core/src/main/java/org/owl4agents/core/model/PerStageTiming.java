package org.owl4agents.core.model;

/**
 * Per-stage timing metadata for claim verification.
 * All values are in milliseconds; null when a stage was not executed.
 */
public record PerStageTiming(
    Long axiomBuildMs,
    Long sourceConsistencyMs,
    Long entailmentMs,
    Long temporaryCopyMs,
    Long reasonerInitMs,
    Long consistencyCheckMs,
    Long explanationMs,
    Long totalMs
) {
    public static PerStageTiming empty() {
        return new PerStageTiming(null, null, null, null, null, null, null, null);
    }
}
