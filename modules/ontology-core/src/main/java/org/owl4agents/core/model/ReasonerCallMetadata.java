package org.owl4agents.core.model;

/**
 * v0.8.6: Metadata attached to every reasoner call result.
 *
 * <p>Populated by {@code ReasonerCallWrapper} on every returned
 * {@code ServiceResult} (both success and error paths except
 * {@code REASONER_BUSY}, which is rejected before acquisition).
 * Propagated through the 5-stage claim verification pipeline to
 * {@code ClaimVerificationResult.metadata} (LAST reasoner call wins)
 * and {@code ConsistencyAfterAdditionResult.metadata}.</p>
 *
 * <p>Fields:</p>
 * <ul>
 *   <li>{@code reasonerName} — the reasoner that produced the final result
 *       (e.g. "ELK" when ELK fallback succeeded after HermiT timed out)</li>
 *   <li>{@code fallbackFrom} — the original reasoner that timed out and
 *       triggered fallback (null when no fallback occurred)</li>
 *   <li>{@code executorRecovered} — true if the reasoner executor was
 *       recovered via {@code shutdownNow()} + fresh executor swap
 *       (timeout recovery)</li>
 *   <li>{@code timeoutMs} — the configured timeout in milliseconds</li>
 * </ul>
 *
 * <p>JSON serialization: a 4-field JSON object when non-null;
 * {@code null} when no reasoner was invoked (e.g. Stage 1 short-circuit).</p>
 */
public record ReasonerCallMetadata(
    String reasonerName,
    String fallbackFrom,
    boolean executorRecovered,
    long timeoutMs
) {
    /**
     * Construct metadata for a normal (non-fallback) reasoner call.
     */
    public static ReasonerCallMetadata normal(String reasonerName, long timeoutMs) {
        return new ReasonerCallMetadata(reasonerName, null, false, timeoutMs);
    }

    /**
     * Construct metadata for an ELK fallback success.
     */
    public static ReasonerCallMetadata fallback(String originalReasoner, long timeoutMs) {
        return new ReasonerCallMetadata("ELK", originalReasoner, true, timeoutMs);
    }

    /**
     * Construct metadata for a timeout (primary only, no fallback).
     */
    public static ReasonerCallMetadata timeout(String reasonerName, long timeoutMs) {
        return new ReasonerCallMetadata(reasonerName, null, true, timeoutMs);
    }

    /**
     * Construct metadata for a dual timeout (primary + ELK both timed out).
     */
    public static ReasonerCallMetadata dualTimeout(String originalReasoner, long timeoutMs) {
        return new ReasonerCallMetadata("ELK", originalReasoner, true, timeoutMs);
    }
}
