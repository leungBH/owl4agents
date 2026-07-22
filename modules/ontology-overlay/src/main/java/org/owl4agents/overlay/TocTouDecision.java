package org.owl4agents.overlay;

/**
 * v0.8.7 OV-005 / D11: Decision returned by {@link TocTouGuard} when
 * checking whether a snapshot is still valid.
 *
 * <p>The decision maps to the pipeline's overall
 * {@code ToolCallValidationReport.decision} field when the guard rejects
 * a snapshot:</p>
 * <ul>
 *   <li>{@link #VALID} — snapshot is fresh and the checksum matches;
 *       the pipeline proceeds normally.</li>
 *   <li>{@link #SNAPSHOT_EXPIRED} — the snapshot is older than the
 *       configured TTL (default 5s). The pipeline SHALL NOT perform
 *       reasoning or SHACL validation.</li>
 *   <li>{@link #RETRY_VALIDATION} — the snapshot's checksum does not
 *       match the recomputed axiom checksum, indicating the dynamic
 *       state changed between snapshot capture and overlay build. The
 *       pipeline SHALL NOT proceed to OWL claim verification.</li>
 * </ul>
 */
public enum TocTouDecision {
    VALID,
    SNAPSHOT_EXPIRED,
    RETRY_VALIDATION
}
