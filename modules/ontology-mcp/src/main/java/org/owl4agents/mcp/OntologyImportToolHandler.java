package org.owl4agents.mcp;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.CatalogEntry;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.owlapi.OntologyImporter;
import org.owl4agents.storage.CatalogStore;
import org.owl4agents.storage.HomeDirectoryResolver;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.8.7 mcp-write-tools: Handler for the {@code ontology_import} write tool.
 *
 * <p>Per design.md D4/D5 and mcp-write-tools spec, this handler:</p>
 * <ul>
 *   <li>Accepts {@code ontology_id} (required), {@code content_base64} (optional),
 *       {@code file_path} (optional), {@code overwrite} (optional, default false)</li>
 *   <li>When both {@code content_base64} and {@code file_path} are provided,
 *       {@code content_base64} takes precedence</li>
 *   <li>Enforces a per-import size limit (default 50 MB, configurable via
 *       {@code --max-import-size-mb})</li>
 *   <li>Resolves {@code file_path} against the configured allowed roots and
 *       rejects parent-directory traversal and out-of-roots paths</li>
 *   <li>When {@code overwrite=false} and {@code ontology_id} already exists,
 *       returns {@code IMPORT_ID_CONFLICT}</li>
 *   <li>When {@code overwrite=true} and {@code ontology_id} exists, invalidates
 *       the {@link OntologyCache} entry, removes the catalog entry, then re-imports</li>
 *   <li>Returns a result payload with {@code ontologyId}, {@code entityCount},
 *       {@code axiomCount}, {@code sourcePath}, and {@code checksum}</li>
 * </ul>
 */
public class OntologyImportToolHandler {

    private static final int BYTES_PER_MB = 1024 * 1024;

    private final HomeDirectoryResolver homeResolver;
    private final CatalogStore catalogStore;
    private final OntologyImporter importer;
    private final OntologyCache ontologyCache;
    private final WorkspaceId workspaceId;
    private final long maxSizeBytes;
    private final List<Path> allowedRoots;

    /**
     * Construct the handler.
     *
     * @param homeResolver   home directory resolver (used to compute the
     *                       default allowed root: {@code <workspace>/imports/})
     * @param catalogStore   catalog store for entry lookup/removal
     * @param importer       the underlying OntologyImporter
     * @param ontologyCache  ontology cache (may be {@code null} in unit tests;
     *                       invalidation is skipped when null)
     * @param workspaceId    workspace ID for catalog operations
     * @param maxImportSizeMb per-import size cap in MB (default 50)
     * @param allowedRootsCsv comma-separated list of allowed root directories;
     *                       when null/blank, defaults to
     *                       {@code <workspace>/imports/}
     */
    public OntologyImportToolHandler(HomeDirectoryResolver homeResolver,
                                     CatalogStore catalogStore,
                                     OntologyImporter importer,
                                     OntologyCache ontologyCache,
                                     WorkspaceId workspaceId,
                                     int maxImportSizeMb,
                                     String allowedRootsCsv) {
        this.homeResolver = homeResolver;
        this.catalogStore = catalogStore;
        this.importer = importer;
        this.ontologyCache = ontologyCache;
        this.workspaceId = workspaceId;
        this.maxSizeBytes = (long) maxImportSizeMb * BYTES_PER_MB;
        this.allowedRoots = resolveAllowedRoots(allowedRootsCsv);
    }

    /**
     * Execute the ontology_import tool.
     *
     * @param arguments tool arguments (ontology_id, content_base64, file_path, overwrite)
     * @return success payload with ontologyId/entityCount/axiomCount/sourcePath/checksum,
     *         or error response with the appropriate error code
     */
    public Map<String, Object> execute(Map<String, Object> arguments) {
        String ontologyIdStr = (String) arguments.get("ontology_id");
        if (ontologyIdStr == null || ontologyIdStr.isBlank()) {
            return errorResponse(ServiceError.of(ErrorCode.INVALID_IMPORT_ARGUMENTS,
                "ontology_id is required"));
        }
        OntologyId ontologyId = new OntologyId(ontologyIdStr);

        String contentBase64 = (String) arguments.get("content_base64");
        String filePathStr = (String) arguments.get("file_path");
        boolean overwrite = Boolean.TRUE.equals(arguments.get("overwrite"));

        if ((contentBase64 == null || contentBase64.isBlank())
            && (filePathStr == null || filePathStr.isBlank())) {
            return errorResponse(ServiceError.of(ErrorCode.INVALID_IMPORT_ARGUMENTS,
                "Either content_base64 or file_path must be provided"));
        }

        // Resolve the source Path and capture the byte payload for size + checksum.
        // content_base64 takes precedence over file_path per mcp-write-tools spec.
        Path sourcePath;
        byte[] payload;
        if (contentBase64 != null && !contentBase64.isBlank()) {
            try {
                payload = Base64.getDecoder().decode(contentBase64);
            } catch (IllegalArgumentException e) {
                return errorResponse(ServiceError.of(ErrorCode.INVALID_IMPORT_ARGUMENTS,
                    "content_base64 is not valid Base64: " + e.getMessage()));
            }
            if (payload.length > maxSizeBytes) {
                return errorResponse(ServiceError.of(ErrorCode.IMPORT_SIZE_LIMIT_EXCEEDED,
                    "Payload size " + payload.length + " bytes exceeds limit " + maxSizeBytes + " bytes",
                    Map.of("actualBytes", payload.length, "limitBytes", maxSizeBytes)));
            }
            // Write payload to a temp file so OntologyImporter can read it.
            try {
                Path tempDir = Files.createTempDirectory("owl4agents-import-");
                sourcePath = tempDir.resolve(ontologyId.id() + "-import.owl");
                Files.write(sourcePath, payload);
                sourcePath.toFile().deleteOnExit();
                tempDir.toFile().deleteOnExit();
            } catch (IOException e) {
                return errorResponse(ServiceError.of(ErrorCode.IMPORT_FAILED,
                    "Failed to stage content_base64 payload: " + e.getMessage()));
            }
        } else {
            // file_path branch: validate path against allowed roots before reading.
            Path requested = Path.of(filePathStr);
            Path resolved;
            try {
                resolved = requested.toRealPath();
            } catch (IOException realPathEx) {
                // toRealPath requires the file to exist. If it doesn't, fall back
                // to normalize().toAbsolutePath() and let the existence check
                // below produce a clean error.
                try {
                    resolved = requested.normalize().toAbsolutePath();
                } catch (Exception normEx) {
                    return errorResponse(ServiceError.of(ErrorCode.IMPORT_PATH_OUTSIDE_ALLOWED_ROOTS,
                        "Could not resolve file_path: " + filePathStr));
                }
            }
            // Reject any path that still contains ".." after normalization.
            if (resolved.toString().contains("..")) {
                return errorResponse(ServiceError.of(ErrorCode.IMPORT_PATH_OUTSIDE_ALLOWED_ROOTS,
                    "file_path contains '..' components after normalization: " + filePathStr,
                    Map.of("requestedPath", filePathStr, "resolvedPath", resolved.toString())));
            }
            // Verify the resolved path is inside at least one allowed root.
            if (!isInsideAllowedRoots(resolved)) {
                return errorResponse(ServiceError.of(ErrorCode.IMPORT_PATH_OUTSIDE_ALLOWED_ROOTS,
                    "file_path resolves outside the configured allowed roots: " + resolved,
                    Map.of("resolvedPath", resolved.toString(),
                           "allowedRoots", allowedRoots.stream().map(Path::toString).toList())));
            }
            if (!Files.exists(resolved)) {
                return errorResponse(ServiceError.of(ErrorCode.IMPORT_FAILED,
                    "Source file does not exist: " + resolved));
            }
            try {
                payload = Files.readAllBytes(resolved);
            } catch (IOException e) {
                return errorResponse(ServiceError.of(ErrorCode.IMPORT_FAILED,
                    "Failed to read source file: " + e.getMessage()));
            }
            if (payload.length > maxSizeBytes) {
                return errorResponse(ServiceError.of(ErrorCode.IMPORT_SIZE_LIMIT_EXCEEDED,
                    "File size " + payload.length + " bytes exceeds limit " + maxSizeBytes + " bytes",
                    Map.of("actualBytes", payload.length, "limitBytes", maxSizeBytes)));
            }
            sourcePath = resolved;
        }

        // ID conflict handling.
        ServiceResult<CatalogEntry> existing = catalogStore.findEntry(workspaceId, ontologyId);
        if (existing.isSuccess()) {
            // Entry already exists.
            if (!overwrite) {
                CatalogEntry entry = ((ServiceResult.Success<CatalogEntry>) existing).data();
                return errorResponse(ServiceError.of(ErrorCode.IMPORT_ID_CONFLICT,
                    "ontology_id '" + ontologyId.id() + "' already exists in the catalog",
                    Map.of("ontologyId", ontologyId.id(),
                           "existingSourcePath", entry.sourcePath().toString())));
            }
            // overwrite=true: invalidate cache, remove existing entry, then re-import.
            if (ontologyCache != null) {
                ontologyCache.invalidate(ontologyId);
            }
            ServiceResult<CatalogEntry> removeResult = catalogStore.removeEntry(workspaceId, ontologyId);
            if (!removeResult.isSuccess()) {
                return errorResponse(((ServiceResult.Error<CatalogEntry>) removeResult).error());
            }
            // Also remove the existing on-disk ontology directory so the new
            // import starts clean. OntologyImporter.createDirectories uses
            // Files.createDirectories which is a no-op for existing dirs, so
            // we must clear out the old source/canonical files explicitly.
            try {
                Path ontologyDir = homeResolver.resolveWorkspaceDirectory(workspaceId)
                    .resolve("ontologies").resolve(ontologyId.id());
                if (Files.exists(ontologyDir)) {
                    deleteRecursively(ontologyDir);
                }
            } catch (IOException e) {
                // Non-fatal: OntologyImporter will overwrite the canonical file.
            }
        }

        // Delegate to OntologyImporter.
        ServiceResult<Void> importResult = importer.importOntology(ontologyId, sourcePath, workspaceId);
        if (!importResult.isSuccess()) {
            return errorResponse(((ServiceResult.Error<Void>) importResult).error());
        }

        // Compute checksum + counts for the result payload.
        String checksum = sha256Hex(payload);
        CatalogEntry freshEntry = ((ServiceResult.Success<CatalogEntry>)
            catalogStore.findEntry(workspaceId, ontologyId)).data();
        int[] counts = computeEntityAxiomCounts(freshEntry.canonicalPath());

        Map<String, Object> data = new HashMap<>();
        data.put("ontologyId", ontologyId.id());
        data.put("entityCount", counts[0]);
        data.put("axiomCount", counts[1]);
        data.put("sourcePath", freshEntry.sourcePath().toString());
        data.put("checksum", checksum);
        return Map.of("status", "success", "data", data);
    }

    /**
     * Resolve the configured allowed roots. When the CSV is null/blank,
     * default to {@code <workspace>/imports/}.
     */
    private List<Path> resolveAllowedRoots(String csv) {
        if (csv == null || csv.isBlank()) {
            Path defaults = homeResolver.resolveWorkspaceDirectory(workspaceId)
                .resolve("imports");
            return List.of(defaults);
        }
        List<Path> roots = new java.util.ArrayList<>();
        for (String raw : csv.split(",")) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                roots.add(Path.of(trimmed).normalize().toAbsolutePath());
            }
        }
        return roots.isEmpty()
            ? List.of(homeResolver.resolveWorkspaceDirectory(workspaceId).resolve("imports"))
            : roots;
    }

    private boolean isInsideAllowedRoots(Path candidate) {
        Path normalized = candidate.normalize().toAbsolutePath();
        for (Path root : allowedRoots) {
            Path normalizedRoot = root.normalize().toAbsolutePath();
            if (normalized.startsWith(normalizedRoot)) {
                return true;
            }
        }
        return false;
    }

    private String sha256Hex(byte[] bytes) {
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

    private int[] computeEntityAxiomCounts(Path canonicalPath) {
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(canonicalPath.toFile());
            int entities = ontology.getSignature().size();
            int axioms = ontology.getAxiomCount();
            return new int[] { entities, axioms };
        } catch (Exception e) {
            return new int[] { 0, 0 };
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (Path child : stream.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    private static Map<String, Object> errorResponse(ServiceError error) {
        Map<String, Object> errorObj = new HashMap<>();
        errorObj.put("code", error.code().code());
        errorObj.put("message", error.message());
        if (!error.details().isEmpty()) {
            errorObj.put("details", error.details());
        }
        return Map.of("status", "error", "error", errorObj);
    }
}
