package org.owl4agents.reasoner.write;

import java.time.Instant;

/**
 * v0.9.1 mcp-write-tools-expansion D4: Version history snapshot record.
 *
 * <p>Each successful {@code ontology_commit} and the initial
 * {@code ontology_import} create a {@code VersionSnapshot}. Snapshots are
 * content-addressed: two snapshots with identical {@code contentChecksum}
 * share the same {@code snapshotPath} (deduplication). History is
 * append-only; pruning removes the oldest dedup-shareable snapshot but
 * NEVER the initial import baseline.</p>
 *
 * @param versionId        UUID identifying this snapshot
 * @param ontologyId       target ontology ID
 * @param contentChecksum  SHA-256 (64-char hex) of canonical OWL/XML
 *                         serialization (OWLXMLDocumentFormat, pinned)
 * @param createdAt        ISO-8601 instant when the snapshot was created
 * @param author           caller-supplied identity
 * @param parentVersionId  previous head version ID (null for import baseline)
 * @param changeSummary    caller-supplied commit message or auto-generated
 * @param axiomCount       total axiom count in the snapshot
 * @param entityCount      total entity count in the snapshot
 * @param snapshotPath     relative path under
 *                         {@code <workspace>/ontologies/<id>/versions/}
 */
public record VersionSnapshot(
    String versionId,
    String ontologyId,
    String contentChecksum,
    Instant createdAt,
    String author,
    String parentVersionId,
    String changeSummary,
    int axiomCount,
    int entityCount,
    String snapshotPath
) {
    public VersionSnapshot {
        if (versionId == null || versionId.isBlank()) {
            versionId = java.util.UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (author == null || author.isBlank()) {
            author = "mcp";
        }
    }
}
