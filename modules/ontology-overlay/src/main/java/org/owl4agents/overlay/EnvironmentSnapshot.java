package org.owl4agents.overlay;

import java.time.Instant;
import java.util.List;

/**
 * v0.8.7 OV-004 / D11: Snapshot of the dynamic environment at a point
 * in time.
 *
 * <p>An {@code EnvironmentSnapshot} couples the snapshot metadata
 * ({@link StateSnapshotId}) with the structured dynamic state
 * (devices, user contexts, pending tool calls). It is the canonical
 * input to the overlay pipeline: callers can either build it directly
 * from Java objects (OV-003 structured input path) or the pipeline
 * constructs a metadata-only snapshot from RDF-parsed axioms.</p>
 *
 * <p>Fields:</p>
 * <ul>
 *   <li>{@code snapshotId} — UUID identifying the snapshot.</li>
 *   <li>{@code capturedAt} — {@link Instant} the snapshot was captured.</li>
 *   <li>{@code source} — producer tag (e.g. {@code "mcp"}, {@code "cli"},
 *       {@code "api"}).</li>
 *   <li>{@code version} — monotonic version within the source.</li>
 *   <li>{@code checksum} — 64-char hex SHA256 of the canonical axiom
 *       N-Triples serialization.</li>
 *   <li>{@code devices} — list of {@link DeviceSnapshot} entries; empty
 *       for the RDF-input path.</li>
 *   <li>{@code userContexts} — list of {@link UserContext} entries; empty
 *       for the RDF-input path.</li>
 *   <li>{@code pendingToolCalls} — list of {@link ToolCallCandidate}
 *       entries; empty for the RDF-input path.</li>
 * </ul>
 *
 * <p>The {@link #toSnapshotId()} view returns the metadata subset used
 * by {@link TocTouGuard} and recorded in
 * {@code ToolCallValidationReport.stateVersion}.</p>
 */
public record EnvironmentSnapshot(
    String snapshotId,
    Instant capturedAt,
    String source,
    long version,
    String checksum,
    List<DeviceSnapshot> devices,
    List<UserContext> userContexts,
    List<ToolCallCandidate> pendingToolCalls
) {
    public EnvironmentSnapshot {
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
        if (devices == null) {
            devices = List.of();
        } else {
            devices = List.copyOf(devices);
        }
        if (userContexts == null) {
            userContexts = List.of();
        } else {
            userContexts = List.copyOf(userContexts);
        }
        if (pendingToolCalls == null) {
            pendingToolCalls = List.of();
        } else {
            pendingToolCalls = List.copyOf(pendingToolCalls);
        }
    }

    /**
     * Return the metadata subset of this snapshot as a
     * {@link StateSnapshotId}.
     */
    public StateSnapshotId toSnapshotId() {
        return new StateSnapshotId(snapshotId, capturedAt, source, version, checksum);
    }
}
