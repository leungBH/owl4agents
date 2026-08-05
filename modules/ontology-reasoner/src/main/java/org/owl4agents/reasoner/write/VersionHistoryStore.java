package org.owl4agents.reasoner.write;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.util.GsonFactory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v0.9.1 mcp-write-tools-expansion D4: Content-addressed version history.
 *
 * <p>Each successful {@code ontology_commit} and the initial
 * {@code ontology_import} create a {@link VersionSnapshot}. Storage layout
 * per ontology:</p>
 * <pre>
 *   &lt;workspace&gt;/ontologies/&lt;id&gt;/versions/&lt;versionId&gt;.owx     (snapshot blob)
 *   &lt;workspace&gt;/ontologies/&lt;id&gt;/versions/versions.json          (metadata)
 * </pre>
 *
 * <p>Content-addressed dedup: if {@code contentChecksum} matches an existing
 * snapshot's checksum, the new snapshot reuses the existing
 * {@code snapshotPath} (no duplicate blob is written).</p>
 *
 * <p>Pruning at {@code owl4agents.write.version.maxSnapshots} (default 100):
 * the oldest snapshot whose {@code contentChecksum} is shared by a newer
 * version is pruned first; the initial import baseline (parentVersionId=null)
 * is NEVER pruned.</p>
 *
 * <p>Serialization format is {@link OWLXMLDocumentFormat}, pinned (NOT
 * configurable) for cross-platform {@code contentChecksum} stability.</p>
 */
public final class VersionHistoryStore {

    private static final String VERSIONS_DIR = "versions";
    private static final String METADATA_FILE = "versions.json";
    private static final int MAX_LIMIT_CAP = 200;

    private final Path workspaceOntologiesRoot;
    private final int maxSnapshots;
    private final Gson gson;

    public VersionHistoryStore(String workspaceBasePath, String workspaceName) {
        this.workspaceOntologiesRoot = Path.of(workspaceBasePath, workspaceName, "ontologies");
        this.maxSnapshots = parseIntProperty("owl4agents.write.version.maxSnapshots", 100);
        this.gson = GsonFactory.createGson();
    }

    public VersionHistoryStore(String workspaceBasePath, String workspaceName, int maxSnapshots) {
        this.workspaceOntologiesRoot = Path.of(workspaceBasePath, workspaceName, "ontologies");
        this.maxSnapshots = maxSnapshots;
        this.gson = GsonFactory.createGson();
    }

    /**
     * Create a new snapshot for {@code ontologyId} from the given staging ontology.
     *
     * @param ontologyId      target ontology
     * @param stagingOntology ontology content to snapshot
     * @param author          caller identity
     * @param parentVersionId previous head version ID (null for import baseline)
     * @param changeSummary   commit message or auto-generated summary
     * @return success containing the new VersionSnapshot, or error on I/O failure
     */
    public synchronized ServiceResult<VersionSnapshot> createSnapshot(
            OntologyId ontologyId,
            OWLOntology stagingOntology,
            String author,
            String parentVersionId,
            String changeSummary) {
        if (ontologyId == null) {
            return ServiceResult.error(ServiceError.of(
                org.owl4agents.core.ErrorCode.INVALID_ARGUMENTS,
                "ontologyId must not be null"));
        }
        if (stagingOntology == null) {
            return ServiceResult.error(ServiceError.of(
                org.owl4agents.core.ErrorCode.INVALID_ARGUMENTS,
                "stagingOntology must not be null"));
        }

        try {
            Path versionsDir = resolveVersionsDir(ontologyId);
            Files.createDirectories(versionsDir);

            // 1. Serialize staging ontology to canonical OWL/XML bytes.
            byte[] owlXmlBytes = serializeOwlXml(stagingOntology);
            String checksum = sha256Hex(owlXmlBytes);

            // 2. Content-addressed dedup: if any existing snapshot has the same
            //    checksum, reuse its snapshotPath (no duplicate blob).
            List<VersionSnapshot> existing = loadMetadata(ontologyId);
            String snapshotPath = null;
            for (VersionSnapshot s : existing) {
                if (checksum.equals(s.contentChecksum())) {
                    snapshotPath = s.snapshotPath();
                    break;
                }
            }
            String versionId = UUID.randomUUID().toString();
            if (snapshotPath == null) {
                // Write a new blob named by versionId.
                String blobName = versionId + ".owx";
                Path blobPath = versionsDir.resolve(blobName);
                Files.write(blobPath, owlXmlBytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                snapshotPath = blobName;
            }

            int axiomCount = stagingOntology.getAxiomCount();
            int entityCount = stagingOntology.getSignature().size();

            VersionSnapshot snapshot = new VersionSnapshot(
                versionId,
                ontologyId.id(),
                checksum,
                Instant.now(),
                author == null || author.isBlank() ? "mcp" : author,
                parentVersionId,
                changeSummary == null || changeSummary.isBlank()
                    ? autoSummary(stagingOntology)
                    : changeSummary,
                axiomCount,
                entityCount,
                snapshotPath
            );

            existing.add(snapshot);
            saveMetadata(ontologyId, existing);
            pruneIfNeeded(ontologyId, existing);

            return ServiceResult.success(snapshot, org.owl4agents.core.ResultMetadata.empty());
        } catch (IOException e) {
            return ServiceResult.error(ServiceError.of(
                org.owl4agents.core.ErrorCode.INVALID_ARGUMENTS,
                "Failed to create version snapshot: " + e.getMessage()));
        }
    }

    /**
     * List snapshots reverse-chronological (newest first).
     * Omits snapshotPath (no filesystem leakage).
     * Caps at {@code limit} (max 200).
     */
    public synchronized ServiceResult<List<VersionSnapshot>> listHistory(
            OntologyId ontologyId, int limit) {
        if (ontologyId == null) {
            return ServiceResult.error(ServiceError.of(
                org.owl4agents.core.ErrorCode.INVALID_ARGUMENTS,
                "ontologyId must not be null"));
        }
        int effectiveLimit = limit <= 0 ? MAX_LIMIT_CAP : Math.min(limit, MAX_LIMIT_CAP);
        List<VersionSnapshot> all = loadMetadata(ontologyId);
        // Newest first (reverse createdAt order)
        all.sort(Comparator.comparing(VersionSnapshot::createdAt).reversed());
        List<VersionSnapshot> truncated = all.size() > effectiveLimit
            ? new ArrayList<>(all.subList(0, effectiveLimit))
            : new ArrayList<>(all);
        // Omit snapshotPath
        List<VersionSnapshot> sanitized = new ArrayList<>(truncated.size());
        for (VersionSnapshot s : truncated) {
            sanitized.add(new VersionSnapshot(
                s.versionId(), s.ontologyId(), s.contentChecksum(),
                s.createdAt(), s.author(), s.parentVersionId(),
                s.changeSummary(), s.axiomCount(), s.entityCount(),
                null
            ));
        }
        return ServiceResult.success(sanitized, org.owl4agents.core.ResultMetadata.empty());
    }

    /**
     * Find a single snapshot by versionId (snapshotPath included for internal use).
     */
    public synchronized ServiceResult<VersionSnapshot> findSnapshot(
            OntologyId ontologyId, String versionId) {
        if (ontologyId == null || versionId == null || versionId.isBlank()) {
            return ServiceResult.error(org.owl4agents.core.ErrorCode.VERSION_NOT_FOUND);
        }
        for (VersionSnapshot s : loadMetadata(ontologyId)) {
            if (versionId.equals(s.versionId())) {
                return ServiceResult.success(s, org.owl4agents.core.ResultMetadata.empty());
            }
        }
        return ServiceResult.error(org.owl4agents.core.ErrorCode.VERSION_NOT_FOUND);
    }

    /**
     * Rollback the committed ontology to the content of snapshot {@code versionId}.
     * Creates a NEW version (child of current head) with equal content.
     * Caller is responsible for invalidating {@code OntologyCache} and writing
     * the restored content to the workspace ontology file.
     *
     * @return success containing the new VersionSnapshot, or VERSION_NOT_FOUND
     */
    public synchronized ServiceResult<VersionSnapshot> rollbackToVersion(
            OntologyId ontologyId, String versionId, String author) {
        ServiceResult<VersionSnapshot> found = findSnapshot(ontologyId, versionId);
        if (!found.isSuccess()) {
            return found;
        }
        VersionSnapshot target = ((ServiceResult.Success<VersionSnapshot>) found).data();
        try {
            // Load the snapshot content
            Path versionsDir = resolveVersionsDir(ontologyId);
            Path blobPath = versionsDir.resolve(target.snapshotPath());
            if (!Files.exists(blobPath)) {
                return ServiceResult.error(ServiceError.of(
                    org.owl4agents.core.ErrorCode.VERSION_NOT_FOUND,
                    "Snapshot blob missing on disk: " + target.snapshotPath()));
            }
            byte[] content = Files.readAllBytes(blobPath);
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology restored = manager.loadOntologyFromOntologyDocument(
                new java.io.ByteArrayInputStream(content));

            // Determine current head (newest snapshot by createdAt)
            List<VersionSnapshot> all = loadMetadata(ontologyId);
            String parentVersionId = all.stream()
                .max(Comparator.comparing(VersionSnapshot::createdAt))
                .map(VersionSnapshot::versionId)
                .orElse(null);

            // Create new version sharing the same blob (content-addressed dedup)
            VersionSnapshot newSnapshot = new VersionSnapshot(
                UUID.randomUUID().toString(),
                ontologyId.id(),
                target.contentChecksum(),
                Instant.now(),
                author == null || author.isBlank() ? "mcp" : author,
                parentVersionId,
                "rollback to " + versionId,
                target.axiomCount(),
                target.entityCount(),
                target.snapshotPath()  // reuse the existing blob
            );
            all.add(newSnapshot);
            saveMetadata(ontologyId, all);
            pruneIfNeeded(ontologyId, all);

            // Write restored content to the workspace canonical ontology file.
            // The caller (WriteTransactionService) handles OntologyCache invalidation.
            return ServiceResult.success(newSnapshot, org.owl4agents.core.ResultMetadata.empty());
        } catch (IOException | OWLOntologyCreationException e) {
            return ServiceResult.error(ServiceError.of(
                org.owl4agents.core.ErrorCode.INVALID_ARGUMENTS,
                "Failed to rollback to version " + versionId + ": " + e.getMessage()));
        }
    }

    /**
     * Resolve the absolute blob path for a snapshot (internal use by
     * WriteTransactionService when restoring committed state).
     */
    public Path resolveBlobPath(OntologyId ontologyId, String snapshotPath) {
        return resolveVersionsDir(ontologyId).resolve(snapshotPath);
    }

    /**
     * Resolve the workspace canonical ontology file path
     * ({@code <workspace>/ontologies/<id>/canonical/ontology.owl}).
     */
    public Path resolveCanonicalOntologyPath(OntologyId ontologyId) {
        return workspaceOntologiesRoot
            .resolve(ontologyId.id())
            .resolve("canonical")
            .resolve("ontology.owl");
    }

    /**
     * Write the restored snapshot content to the workspace canonical ontology file.
     * Used by {@code rollbackToVersion} to atomically swap the committed state.
     */
    public ServiceResult<Void> writeRestoredContentToCanonical(
            OntologyId ontologyId, String snapshotPath) {
        try {
            Path blobPath = resolveBlobPath(ontologyId, snapshotPath);
            if (!Files.exists(blobPath)) {
                return ServiceResult.error(ServiceError.of(
                    org.owl4agents.core.ErrorCode.VERSION_NOT_FOUND,
                    "Snapshot blob missing: " + snapshotPath));
            }
            byte[] content = Files.readAllBytes(blobPath);
            Path canonical = resolveCanonicalOntologyPath(ontologyId);
            Files.createDirectories(canonical.getParent());
            Files.write(canonical, content,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return ServiceResult.success(null, org.owl4agents.core.ResultMetadata.empty());
        } catch (IOException e) {
            return ServiceResult.error(ServiceError.of(
                org.owl4agents.core.ErrorCode.INVALID_ARGUMENTS,
                "Failed to write restored content: " + e.getMessage()));
        }
    }

    private Path resolveVersionsDir(OntologyId ontologyId) {
        return workspaceOntologiesRoot
            .resolve(ontologyId.id())
            .resolve(VERSIONS_DIR);
    }

    private Path resolveMetadataFile(OntologyId ontologyId) {
        return resolveVersionsDir(ontologyId).resolve(METADATA_FILE);
    }

    private List<VersionSnapshot> loadMetadata(OntologyId ontologyId) {
        Path file = resolveMetadataFile(ontologyId);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return new ArrayList<>();
            }
            java.lang.reflect.Type mapListType = new TypeToken<List<Map<String, Object>>>() {}.getType();
            List<Map<String, Object>> list = gson.fromJson(json, mapListType);
            if (list == null) {
                return new ArrayList<>();
            }
            List<VersionSnapshot> snapshots = new ArrayList<>();
            for (Map<String, Object> m : list) {
                snapshots.add(mapToSnapshot(m));
            }
            return snapshots;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private void saveMetadata(OntologyId ontologyId, List<VersionSnapshot> snapshots) throws IOException {
        Path file = resolveMetadataFile(ontologyId);
        Files.createDirectories(file.getParent());
        List<Map<String, Object>> maps = new ArrayList<>();
        for (VersionSnapshot s : snapshots) {
            maps.add(snapshotToMap(s));
        }
        String json = gson.toJson(maps);
        Files.writeString(file, json, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private Map<String, Object> snapshotToMap(VersionSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("versionId", s.versionId());
        m.put("ontologyId", s.ontologyId());
        m.put("contentChecksum", s.contentChecksum());
        m.put("createdAt", s.createdAt().toString());
        m.put("author", s.author());
        m.put("parentVersionId", s.parentVersionId());
        m.put("changeSummary", s.changeSummary());
        m.put("axiomCount", s.axiomCount());
        m.put("entityCount", s.entityCount());
        m.put("snapshotPath", s.snapshotPath());
        return m;
    }

    @SuppressWarnings("unchecked")
    private VersionSnapshot mapToSnapshot(Map<String, Object> m) {
        return new VersionSnapshot(
            (String) m.get("versionId"),
            (String) m.get("ontologyId"),
            (String) m.get("contentChecksum"),
            Instant.parse((String) m.get("createdAt")),
            (String) m.get("author"),
            (String) m.get("parentVersionId"),
            (String) m.get("changeSummary"),
            ((Number) m.get("axiomCount")).intValue(),
            ((Number) m.get("entityCount")).intValue(),
            (String) m.get("snapshotPath")
        );
    }

    /**
     * Prune the oldest dedup-shareable snapshot when count > maxSnapshots.
     * NEVER prune the import baseline (parentVersionId == null).
     * Returns the pruned snapshot (for audit), or null if nothing was pruned.
     */
    private VersionSnapshot pruneIfNeeded(OntologyId ontologyId, List<VersionSnapshot> snapshots) {
        if (snapshots.size() <= maxSnapshots) {
            return null;
        }
        // Build a count of checksum references; a snapshot is dedup-shareable
        // if its checksum is referenced by at least one OTHER snapshot.
        Map<String, Integer> checksumRefCount = new LinkedHashMap<>();
        for (VersionSnapshot s : snapshots) {
            checksumRefCount.merge(s.contentChecksum(), 1, Integer::sum);
        }
        // Sort ascending by createdAt (oldest first); skip baseline.
        List<VersionSnapshot> candidates = new ArrayList<>(snapshots);
        candidates.sort(Comparator.comparing(VersionSnapshot::createdAt));
        for (VersionSnapshot candidate : candidates) {
            if (candidate.parentVersionId() == null) {
                continue; // never prune baseline
            }
            int refCount = checksumRefCount.getOrDefault(candidate.contentChecksum(), 0);
            if (refCount > 1) {
                // Safe to prune: another snapshot shares the blob.
                snapshots.remove(candidate);
                checksumRefCount.merge(candidate.contentChecksum(), -1, Integer::sum);
                try {
                    saveMetadata(ontologyId, snapshots);
                } catch (IOException ignored) {
                    // best-effort metadata write
                }
                return candidate;
            }
        }
        // If no dedup-shareable snapshot exists, prune the oldest non-baseline.
        for (VersionSnapshot candidate : candidates) {
            if (candidate.parentVersionId() == null) {
                continue;
            }
            snapshots.remove(candidate);
            // Delete the blob file since no other snapshot references it.
            try {
                Path blob = resolveVersionsDir(ontologyId).resolve(candidate.snapshotPath());
                Files.deleteIfExists(blob);
            } catch (IOException ignored) {
                // best-effort blob deletion
            }
            try {
                saveMetadata(ontologyId, snapshots);
            } catch (IOException ignored) {
                // best-effort metadata write
            }
            return candidate;
        }
        return null;
    }

    private byte[] serializeOwlXml(OWLOntology ontology) throws IOException {
        try (ByteArrayOutputStreamAdapter adapter = new ByteArrayOutputStreamAdapter()) {
            OWLXMLDocumentFormat format = new OWLXMLDocumentFormat();
            ontology.getOWLOntologyManager().saveOntology(ontology, format, adapter);
            return adapter.toByteArray();
        } catch (Exception e) {
            if (e instanceof IOException ioEx) {
                throw ioEx;
            }
            throw new IOException("Failed to serialize ontology: " + e.getMessage(), e);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String autoSummary(OWLOntology ontology) {
        int axioms = ontology.getAxiomCount(Imports.EXCLUDED);
        int entities = ontology.getSignature().size();
        return axioms + " axioms, " + entities + " entities";
    }

    private static int parseIntProperty(String key, int def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * Lightweight OutputStream adapter that captures bytes in a growable buffer.
     * Used to avoid the OWLAPI saveOntology(OutputStream) signature quirks.
     */
    private static final class ByteArrayOutputStreamAdapter extends OutputStream {
        private final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

        @Override
        public void write(int b) {
            baos.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            baos.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            baos.close();
            super.close();
        }

        byte[] toByteArray() {
            return baos.toByteArray();
        }
    }
}
