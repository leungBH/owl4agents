package org.owl4agents.overlay;

/**
 * v0.8.7 OV-005 / D11: Result returned by {@link TocTouGuard#check}.
 *
 * @param decision       the {@link TocTouDecision} (always non-null)
 * @param reason         human-readable explanation of the decision
 *                       (e.g. "snapshot age 6200ms exceeds TTL 5000ms"
 *                       or "checksum mismatch: expected=..., actual=...")
 * @param snapshotId     the snapshot ID that was checked
 * @param expectedChecksum the checksum carried by the snapshot metadata
 * @param actualChecksum   the recomputed checksum (may be empty when the
 *                       guard short-circuits before recomputation, e.g.
 *                       on TTL expiry)
 * @param ageMillis      age of the snapshot in milliseconds at check time
 * @param ttlMillis      the TTL window in milliseconds
 */
public record TocTouResult(
    TocTouDecision decision,
    String reason,
    String snapshotId,
    String expectedChecksum,
    String actualChecksum,
    long ageMillis,
    long ttlMillis
) {
    public TocTouResult {
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        if (reason == null || reason.isBlank()) {
            reason = decision.name();
        }
        if (snapshotId == null) snapshotId = "";
        if (expectedChecksum == null) expectedChecksum = "";
        if (actualChecksum == null) actualChecksum = "";
    }

    /**
     * Convenience: was the snapshot accepted?
     */
    public boolean isValid() {
        return decision == TocTouDecision.VALID;
    }
}
