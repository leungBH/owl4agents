package org.owl4agents.overlay;

import java.time.Instant;

/**
 * v0.8.7 OV-004 / D11: Identifier and metadata for a captured dynamic
 * state snapshot.
 *
 * <p>Every dynamic state input (RDF string or structured Java object)
 * passed to the overlay pipeline SHALL be associated with a
 * {@code StateSnapshotId} that records <em>when</em> the snapshot was
 * captured, <em>where</em> it came from, its logical <em>version</em>
 * within that source, and a <em>checksum</em> over the canonical axiom
 * serialization. The pipeline records the {@code StateSnapshotId} in
 * {@code ToolCallValidationReport.stateVersion} so callers can detect
 * version drift between validation and execution (TOCTOU protection).</p>
 *
 * <p>Fields:</p>
 * <ul>
 *   <li>{@code snapshotId} — UUID v4 string identifying this snapshot.</li>
 *   <li>{@code capturedAt} — {@link Instant} the snapshot was captured.</li>
 *   <li>{@code source} — short tag identifying the producer, e.g.
 *       {@code "mcp"}, {@code "cli"}, {@code "api"}.</li>
 *   <li>{@code version} — monotonically increasing within the same
 *       {@code source}; starts at 0 for the first snapshot from a source.</li>
 *   <li>{@code checksum} — 64-character lowercase hex SHA256 of the
 *       canonical N-Triples serialization of the snapshot's axiom set,
 *       sorted by subject-predicate-object (blank node IDs replaced with
 *       a stable placeholder so the checksum is invariant to blank node
 *       identity).</li>
 * </ul>
 *
 * @param snapshotId UUID identifying the snapshot
 * @param capturedAt capture time (UTC)
 * @param source     producer tag (e.g. "mcp", "cli", "api")
 * @param version    monotonic version within the source
 * @param checksum   64-char hex SHA256 of canonical axiom N-Triples
 */
public record StateSnapshotId(
    String snapshotId,
    Instant capturedAt,
    String source,
    long version,
    String checksum
) {
    public StateSnapshotId {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
        if (capturedAt == null) {
            throw new IllegalArgumentException("capturedAt must not be null");
        }
        if (source == null || source.isBlank()) {
            source = "api";
        }
        if (version < 0) {
            version = 0;
        }
        if (checksum == null) {
            checksum = "";
        }
    }
}
