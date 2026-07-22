package org.owl4agents.shacl;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RiotException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.8.7 SH-004 / D7: Filesystem-backed ShapeRegistry.
 *
 * <p>ShapeSet metadata is persisted to {@code ~/.owl4agents/shapes/registry.json}
 * as a JSON array. The parsed Jena Model for each ShapeSet is cached in a
 * Caffeine cache ({@code maximumSize=50}). On every {@link #resolve(String)},
 * the registry verifies the source file's mtime AND SHA256 checksum against
 * the cached metadata; on mismatch, the file is reloaded and the cache is
 * replaced (and {@code registry.json} is updated with the new checksum).</p>
 *
 * <p>Thread-safety: all public methods are synchronized on the registry
 * monitor to keep the JSON file and in-memory state consistent. The
 * Caffeine cache itself is thread-safe; the synchronization guards the
 * read-modify-write sequences on {@code registry.json}.</p>
 */
public class FileShapeRegistry implements ShapeRegistry {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path registryFile;
    private final Path registryDir;
    private final Cache<String, CachedShape> cache;

    // In-memory mirror of registry.json keyed by shapeSetId.
    private final Map<String, ShapeSet> registry = new ConcurrentHashMap<>();

    /**
     * Default constructor: uses {@code ~/.owl4agents/shapes/registry.json}.
     */
    public FileShapeRegistry() {
        this(defaultRegistryFile());
    }

    /**
     * Construct with an explicit registry file path (used by tests).
     */
    public FileShapeRegistry(Path registryFile) {
        this.registryFile = registryFile.toAbsolutePath().normalize();
        this.registryDir = this.registryFile.getParent();
        this.cache = Caffeine.newBuilder()
            .maximumSize(50)
            .build();
        loadRegistryFromDisk();
    }

    private static Path defaultRegistryFile() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".owl4agents", "shapes", "registry.json");
    }

    @Override
    public synchronized ServiceResult<ShapeSet> register(String id, Path shapesFile,
                                                          String domain, boolean force,
                                                          boolean requiresInference) {
        if (id == null || id.isBlank()) {
            return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS,
                "shape_set_id must not be blank");
        }
        if (shapesFile == null) {
            return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS,
                "shapesFile must not be null");
        }
        Path absShapesFile = shapesFile.toAbsolutePath().normalize();
        if (!Files.exists(absShapesFile)) {
            return ServiceResult.error(ErrorCode.SHACL_SHAPES_MALFORMED,
                "Shapes file does not exist: " + absShapesFile);
        }

        // Read file bytes (for SHA256 + cache key).
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(absShapesFile);
        } catch (IOException e) {
            return ServiceResult.error(ErrorCode.SHACL_SHAPES_MALFORMED,
                "Cannot read shapes file: " + e.getMessage());
        }
        String checksum = sha256Hex(bytes);

        // Conflict check (unless force).
        ShapeSet existing = registry.get(id);
        if (existing != null && !existing.checksum().equals(checksum) && !force) {
            return ServiceResult.error(ErrorCode.SHAPE_SET_ID_CONFLICT,
                "ShapeSet '" + id + "' already exists with a different checksum; pass --force to overwrite.",
                Map.of("shapeSetId", id,
                       "existingChecksum", existing.checksum(),
                       "newChecksum", checksum));
        }

        // Parse the shapes file with Jena RDFDataMgr (validates RDF syntax).
        Model model;
        try {
            model = RDFDataMgr.loadModel(absShapesFile.toString());
        } catch (RiotException e) {
            return ServiceResult.error(ErrorCode.SHACL_SHAPES_MALFORMED,
                "Failed to parse shapes file: " + e.getMessage());
        } catch (RuntimeException e) {
            return ServiceResult.error(ErrorCode.SHACL_SHAPES_MALFORMED,
                "Failed to parse shapes file: " + e.getMessage());
        }

        // Build the ShapeSet record.
        ShapeSet shapeSet = new ShapeSet(
            id,
            "1.0.0",
            domain == null || domain.isBlank() ? "default" : domain,
            absShapesFile,
            checksum,
            true,
            true,
            requiresInference
        );

        // Update registry and cache.
        registry.put(id, shapeSet);
        FileTime mtime = mtimeOf(absShapesFile);
        cache.put(id, new CachedShape(model, checksum, mtime));

        // Persist to disk.
        try {
            persistRegistry();
        } catch (IOException e) {
            // Non-fatal: in-memory state is correct, disk write failed.
            // Surface as a logged warning; tests will catch via System.err.
            System.err.println("[FileShapeRegistry] WARN: failed to persist registry.json: " + e.getMessage());
        }

        return ServiceResult.success(shapeSet, null);
    }

    @Override
    public synchronized ServiceResult<Model> resolve(String shapeSetId) {
        if (shapeSetId == null || shapeSetId.isBlank()) {
            return ServiceResult.error(ErrorCode.SHAPE_SET_NOT_FOUND,
                "shape_set_id must not be blank");
        }
        ShapeSet meta = registry.get(shapeSetId);
        if (meta == null) {
            return ServiceResult.error(ErrorCode.SHAPE_SET_NOT_FOUND,
                "No ShapeSet registered with id '" + shapeSetId + "'.",
                Map.of("shapeSetId", shapeSetId));
        }

        Path source = meta.sourcePath();
        if (!Files.exists(source)) {
            return ServiceResult.error(ErrorCode.SHACL_SHAPES_LOAD_FAILED,
                "ShapeSet '" + shapeSetId + "' source file no longer exists: " + source,
                Map.of("shapeSetId", shapeSetId, "sourcePath", source.toString()));
        }

        // Verify mtime + SHA256. On mismatch, reload and update cache + registry.json.
        CachedShape cached = cache.getIfPresent(shapeSetId);
        FileTime currentMtime = mtimeOf(source);
        boolean needsReload = cached == null
            || !cached.mtime().equals(currentMtime);
        if (!needsReload) {
            // mtime matches; verify checksum lazily (cheap on cached bytes? no — we
            // recompute the file hash per the spec: "On every resolve, the registry
            // SHALL verify the source file's mtime and SHA256 checksum").
            try {
                byte[] currentBytes = Files.readAllBytes(source);
                String currentChecksum = sha256Hex(currentBytes);
                if (!currentChecksum.equals(cached.checksum())) {
                    needsReload = true;
                }
            } catch (IOException e) {
                return ServiceResult.error(ErrorCode.SHACL_SHAPES_LOAD_FAILED,
                    "Cannot read shapes file for checksum verification: " + e.getMessage(),
                    Map.of("shapeSetId", shapeSetId));
            }
        }

        if (needsReload) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(source);
            } catch (IOException e) {
                return ServiceResult.error(ErrorCode.SHACL_SHAPES_LOAD_FAILED,
                    "Cannot read shapes file: " + e.getMessage(),
                    Map.of("shapeSetId", shapeSetId));
            }
            String newChecksum = sha256Hex(bytes);
            Model model;
            try {
                model = RDFDataMgr.loadModel(source.toString());
            } catch (RiotException e) {
                return ServiceResult.error(ErrorCode.SHACL_SHAPES_MALFORMED,
                    "Failed to parse shapes file on reload: " + e.getMessage());
            } catch (RuntimeException e) {
                return ServiceResult.error(ErrorCode.SHACL_SHAPES_MALFORMED,
                    "Failed to parse shapes file on reload: " + e.getMessage());
            }
            cached = new CachedShape(model, newChecksum, currentMtime);
            cache.put(shapeSetId, cached);

            // Update registry.json with the new checksum if it changed.
            if (!newChecksum.equals(meta.checksum())) {
                ShapeSet updated = new ShapeSet(
                    meta.id(), meta.version(), meta.domain(),
                    meta.sourcePath(), newChecksum, meta.enabled(),
                    meta.trusted(), meta.requiresInference());
                registry.put(shapeSetId, updated);
                try {
                    persistRegistry();
                } catch (IOException e) {
                    System.err.println("[FileShapeRegistry] WARN: failed to persist registry.json after reload: " + e.getMessage());
                }
            }
        }

        return ServiceResult.success(cached.model(), null);
    }

    @Override
    public synchronized ServiceResult<Void> invalidate(String shapeSetId) {
        if (shapeSetId == null || shapeSetId.isBlank()) {
            return ServiceResult.error(ErrorCode.SHAPE_SET_NOT_FOUND,
                "shape_set_id must not be blank");
        }
        if (!registry.containsKey(shapeSetId)) {
            return ServiceResult.error(ErrorCode.SHAPE_SET_NOT_FOUND,
                "No ShapeSet registered with id '" + shapeSetId + "'.",
                Map.of("shapeSetId", shapeSetId));
        }
        cache.invalidate(shapeSetId);
        return ServiceResult.success(null, null);
    }

    @Override
    public synchronized List<ShapeSet> list() {
        return new ArrayList<>(registry.values());
    }

    @Override
    public synchronized Optional<ShapeSet> get(String shapeSetId) {
        if (shapeSetId == null) return Optional.empty();
        return Optional.ofNullable(registry.get(shapeSetId));
    }

    // ── Helpers ──

    private void loadRegistryFromDisk() {
        if (!Files.exists(registryFile)) {
            return;
        }
        try {
            String content = Files.readString(registryFile);
            if (content.isBlank()) return;
            JsonElement root = JsonParser.parseString(content);
            if (!root.isJsonArray()) return;
            JsonArray arr = root.getAsJsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                String id = getStr(obj, "id");
                if (id == null || id.isBlank()) continue;
                ShapeSet ss = new ShapeSet(
                    id,
                    getStr(obj, "version"),
                    getStr(obj, "domain"),
                    Path.of(getStr(obj, "sourcePath")),
                    getStr(obj, "checksum"),
                    getBool(obj, "enabled", true),
                    getBool(obj, "trusted", true),
                    getBool(obj, "requiresInference", false)
                );
                registry.put(id, ss);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[FileShapeRegistry] WARN: failed to load registry.json: " + e.getMessage());
        }
    }

    private void persistRegistry() throws IOException {
        if (!Files.exists(registryDir)) {
            Files.createDirectories(registryDir);
        }
        JsonArray arr = new JsonArray();
        for (ShapeSet ss : registry.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", ss.id());
            obj.addProperty("version", ss.version());
            obj.addProperty("domain", ss.domain());
            obj.addProperty("sourcePath", ss.sourcePath().toString());
            obj.addProperty("checksum", ss.checksum());
            obj.addProperty("enabled", ss.enabled());
            obj.addProperty("trusted", ss.trusted());
            obj.addProperty("requiresInference", ss.requiresInference());
            arr.add(obj);
        }
        // Use LinkedHashMap ordering for deterministic output.
        String json = GSON.toJson(arr);
        Files.writeString(registryFile, json);
    }

    private static String getStr(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private static boolean getBool(JsonObject obj, String key, boolean dflt) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsBoolean();
        }
        return dflt;
    }

    private static FileTime mtimeOf(Path p) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            return attrs.lastModifiedTime();
        } catch (IOException e) {
            return FileTime.fromMillis(0);
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

    /**
     * Internal cached entry: holds the parsed Model, the SHA256 of the file
     * bytes at parse time, and the file's mtime at parse time.
     */
    private record CachedShape(Model model, String checksum, FileTime mtime) {}
}
