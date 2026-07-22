package org.owl4agents.toolcall;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.8.7 TC-008: Filesystem-backed {@link ToolContract} registry.
 *
 * <p>Loads tool contracts from the filesystem convention
 * {@code ~/.owl4agents/contracts/<toolName>.json} at startup. Each JSON
 * file SHALL contain a single {@link ToolContract} record serialized
 * according to the {@code ToolContract Data Model} requirement
 * (9 fields: toolName/inputSchema/targetClass/requiredCapabilities/
 * requiredStates/effects/riskLevel/requiredPermission/shapeSetIds).</p>
 *
 * <p>Hot-reload: the registry verifies each cached entry's file mtime on
 * every {@link #get(String)} call. When the file has been modified, the
 * registry reloads the contract from disk before returning the updated
 * record to the caller (per the "Hot-reload after file modification"
 * scenario).</p>
 *
 * <p>Thread-safety: all public methods are synchronized on the registry
 * monitor to keep the in-memory cache and the mtime index consistent.
 * The cache itself is a {@link ConcurrentHashMap} so concurrent reads
 * after {@code get} return see a consistent snapshot.</p>
 *
 * <p>The {@code ontology_get_tool_contract} and
 * {@code ontology_list_tool_contracts} MCP tools (defined in the
 * toolcall-validation-pipeline capability) query this registry.</p>
 */
public class ToolContractRegistry {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Default subdirectory under the owl4agents home for contract files. */
    public static final String DEFAULT_CONTRACTS_SUBDIR = "contracts";

    private final Path contractsDir;
    private final Map<String, CachedContract> cache;

    /**
     * Default constructor: uses {@code ~/.owl4agents/contracts/} as the
     * contract directory. The directory is created lazily on first
     * write (the registry itself is read-only at startup).
     */
    public ToolContractRegistry() {
        this(defaultContractsDir());
    }

    /**
     * Construct with an explicit contracts directory (used by tests and
     * the CLI which may pass {@code --contracts-dir}).
     */
    public ToolContractRegistry(Path contractsDir) {
        this.contractsDir = contractsDir == null
            ? defaultContractsDir()
            : contractsDir.toAbsolutePath().normalize();
        this.cache = new ConcurrentHashMap<>();
        // Load all existing contracts eagerly so list() returns a complete
        // snapshot without re-scanning on every call.
        loadAll();
    }

    private static Path defaultContractsDir() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".owl4agents", DEFAULT_CONTRACTS_SUBDIR);
    }

    /**
     * Resolve a single contract by tool name.
     *
     * <p>Behavior:</p>
     * <ul>
     *   <li>If the contract is cached and the file's mtime is unchanged,
     *       return the cached record (no disk read).</li>
     *   <li>If the file's mtime has changed (or the entry is uncached),
     *       reload from disk and update the cache before returning.</li>
     *   <li>If the file does not exist, return a
     *       {@link ErrorCode#TOOL_CONTRACT_NOT_FOUND} error with a hint
     *       about the expected file path convention.</li>
     * </ul>
     */
    public synchronized ServiceResult<ToolContract> get(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS,
                "toolName must not be blank");
        }
        Path file = contractFile(toolName);
        if (!Files.exists(file)) {
            return ServiceResult.error(ErrorCode.TOOL_CONTRACT_NOT_FOUND,
                "No tool contract registered for tool name '" + toolName
                    + "'. Expected file path convention: "
                    + file + " (create the file with a ToolContract JSON record).",
                Map.of("toolName", toolName,
                       "expectedFilePath", file.toString(),
                       "contractsDir", contractsDir.toString()));
        }
        CachedContract cached = cache.get(toolName);
        FileTime currentMtime = mtimeOf(file);
        if (cached != null && cached.mtime().equals(currentMtime)) {
            return ServiceResult.success(cached.contract(), null);
        }
        // Reload: file is missing from cache OR was modified on disk.
        ServiceResult<ToolContract> loaded = loadFromFile(toolName, file, currentMtime);
        if (!loaded.isSuccess()) {
            return loaded;
        }
        ToolContract contract = ((ServiceResult.Success<ToolContract>) loaded).data();
        cache.put(toolName, new CachedContract(contract, currentMtime));
        return ServiceResult.success(contract, null);
    }

    /**
     * List metadata summaries for every contract file currently loaded
     * in memory. Per the "List registered contracts" scenario, returns
     * at minimum {@code toolName} and {@code riskLevel} for each
     * contract, and excludes any contract whose file does not exist on
     * disk (e.g. removed after startup).
     */
    public synchronized List<ToolContract> list() {
        List<ToolContract> out = new ArrayList<>();
        // Scan the contracts directory for current files so removed files
        // are excluded even when a stale cache entry remains.
        if (!Files.isDirectory(contractsDir)) {
            return out;
        }
        List<Path> files;
        try (var stream = Files.list(contractsDir)) {
            files = stream
                .filter(p -> p.toString().endsWith(".json"))
                .sorted()
                .toList();
        } catch (IOException e) {
            return out;
        }
        for (Path file : files) {
            String toolName = toolNameFromFile(file);
            if (toolName == null) continue;
            // Use get() so hot-reload and mtime check apply uniformly.
            ServiceResult<ToolContract> r = get(toolName);
            if (r.isSuccess()) {
                out.add(((ServiceResult.Success<ToolContract>) r).data());
            }
            // Files that fail to load are silently skipped by list()
            // (callers can use get() to surface the underlying error).
        }
        return out;
    }

    /**
     * Forcibly reload a single contract from disk, bypassing the mtime
     * cache. Used by the CLI when a contract file is known to have
     * changed.
     */
    public synchronized ServiceResult<ToolContract> reload(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS,
                "toolName must not be blank");
        }
        cache.remove(toolName);
        return get(toolName);
    }

    /**
     * Forcibly reload all contracts from disk. Used by the CLI after
     * bulk-editing contract files.
     */
    public synchronized void reloadAll() {
        cache.clear();
        loadAll();
    }

    /**
     * Register (or replace) a contract in memory. Mainly used by tests
     * and the CLI's {@code toolcall register} path (if any). Writes the
     * contract JSON to {@code <contractsDir>/<toolName>.json} so the
     * file system remains the source of truth.
     */
    public synchronized ServiceResult<ToolContract> register(ToolContract contract) {
        if (contract == null) {
            return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS,
                "ToolContract must not be null");
        }
        Path file = contractFile(contract.toolName());
        try {
            if (!Files.isDirectory(contractsDir)) {
                Files.createDirectories(contractsDir);
            }
            String json = GSON.toJson(ToolCallJsonSerializer.contractToMap(contract));
            Files.writeString(file, json);
        } catch (IOException e) {
            return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS,
                "Failed to write contract file " + file + ": " + e.getMessage());
        }
        FileTime mtime = mtimeOf(file);
        cache.put(contract.toolName(), new CachedContract(contract, mtime));
        return ServiceResult.success(contract, null);
    }

    /**
     * Drop a contract from the cache. Does NOT delete the file on disk
     * (callers must remove the file separately for hot-reload to take
     * effect).
     */
    public synchronized void invalidate(String toolName) {
        if (toolName != null) {
            cache.remove(toolName);
        }
    }

    /**
     * Expose the contracts directory for tests and diagnostics.
     */
    public Path contractsDirectory() {
        return contractsDir;
    }

    // ── Helpers ──

    private Path contractFile(String toolName) {
        // Defensive: toolName must be a simple file name (no path separators).
        // This also prevents path-traversal via crafted toolName values.
        String safe = sanitizeToolName(toolName);
        return contractsDir.resolve(safe + ".json");
    }

    private static String sanitizeToolName(String toolName) {
        // Allow only alphanumeric + underscore + hyphen. Strip path
        // separators AND dots (dots are reserved for the .json file
        // extension, so a toolName like "../safe_tool" sanitizes to
        // "safe_tool" — preventing path-traversal attacks via "..").
        StringBuilder sb = new StringBuilder(toolName.length());
        for (int i = 0; i < toolName.length(); i++) {
            char c = toolName.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                sb.append(c);
            }
            // Skip any other character (path separators, dots, etc.).
        }
        return sb.toString();
    }

    private static String toolNameFromFile(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".json")) return null;
        return name.substring(0, name.length() - ".json".length());
    }

    private void loadAll() {
        if (!Files.isDirectory(contractsDir)) {
            return;
        }
        try (var stream = Files.list(contractsDir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                .sorted()
                .forEach(file -> {
                    String toolName = toolNameFromFile(file);
                    if (toolName == null) return;
                    ServiceResult<ToolContract> r =
                        loadFromFile(toolName, file, mtimeOf(file));
                    if (r.isSuccess()) {
                        ToolContract c = ((ServiceResult.Success<ToolContract>) r).data();
                        cache.put(toolName, new CachedContract(c, mtimeOf(file)));
                    } else {
                        // Log to stderr so contract load failures are visible
                        // without crashing the registry. The error will surface
                        // again when get(toolName) is called explicitly.
                        ServiceError err = ((ServiceResult.Error<ToolContract>) r).error();
                        System.err.println("[ToolContractRegistry] WARN: failed to load "
                            + file + ": " + err.code().code() + " " + err.message());
                    }
                });
        } catch (IOException e) {
            System.err.println("[ToolContractRegistry] WARN: cannot list "
                + contractsDir + ": " + e.getMessage());
        }
    }

    private ServiceResult<ToolContract> loadFromFile(String toolName,
                                                      Path file,
                                                      FileTime mtime) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            return ServiceResult.error(ErrorCode.TOOL_CONTRACT_NOT_FOUND,
                "Cannot read contract file " + file + ": " + e.getMessage(),
                Map.of("toolName", toolName, "filePath", file.toString()));
        }
        if (content.isBlank()) {
            return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS,
                "Contract file " + file + " is empty.",
                Map.of("toolName", toolName, "filePath", file.toString()));
        }
        try {
            JsonElement root = JsonParser.parseString(content);
            if (!root.isJsonObject()) {
                return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS,
                    "Contract file " + file + " must contain a JSON object.",
                    Map.of("toolName", toolName, "filePath", file.toString()));
            }
            JsonObject obj = root.getAsJsonObject();
            ToolContract contract = parseContract(toolName, obj);
            // Verify the toolName field matches (or override with filename).
            if (!contract.toolName().equals(toolName)) {
                // Tolerate mismatch: use the filename as the canonical id.
                // Re-serialize with the canonical toolName so subsequent
                // get(toolName) calls return a stable record.
                contract = new ToolContract(
                    toolName,
                    contract.inputSchema(),
                    contract.targetClass(),
                    contract.requiredCapabilities(),
                    contract.requiredStates(),
                    contract.effects(),
                    contract.riskLevel(),
                    contract.requiredPermission(),
                    contract.shapeSetIds()
                );
            }
            return ServiceResult.success(contract, null);
        } catch (RuntimeException e) {
            return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS,
                "Contract file " + file + " is not a valid ToolContract JSON: "
                    + e.getMessage(),
                Map.of("toolName", toolName, "filePath", file.toString()));
        }
    }

    /**
     * Parse a {@link ToolContract} from a JSON object. Tolerates missing
     * optional fields (targetClass, requiredPermission, lists default to
     * empty, riskLevel defaults to LOW).
     */
    @SuppressWarnings("unchecked")
    static ToolContract parseContract(String defaultToolName, JsonObject obj) {
        String toolName = getStr(obj, "toolName", defaultToolName);
        Map<String, Object> inputSchema = getMap(obj, "inputSchema");
        Optional<String> targetClass = getOptionalStr(obj, "targetClass");
        List<String> requiredCapabilities = getList(obj, "requiredCapabilities");
        List<String> requiredStates = getList(obj, "requiredStates");
        List<String> effects = getList(obj, "effects");
        RiskLevel riskLevel = RiskLevel.fromString(getStr(obj, "riskLevel", "low"));
        Optional<String> requiredPermission = getOptionalStr(obj, "requiredPermission");
        List<String> shapeSetIds = getList(obj, "shapeSetIds");
        return new ToolContract(
            toolName,
            inputSchema,
            targetClass,
            requiredCapabilities,
            requiredStates,
            effects,
            riskLevel,
            requiredPermission,
            shapeSetIds
        );
    }

    private static String getStr(JsonObject obj, String key, String dflt) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return dflt;
    }

    private static Optional<String> getOptionalStr(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            String s = obj.get(key).getAsString();
            return s == null || s.isBlank() ? Optional.empty() : Optional.of(s);
        }
        if (obj.has(key) && obj.get(key).isJsonNull()) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Map<String, Object> getMap(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonObject()) {
            return GSON.fromJson(obj.get(key),
                new TypeToken<Map<String, Object>>(){}.getType());
        }
        return Map.of();
    }

    private static List<String> getList(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonArray()) {
            List<String> out = new ArrayList<>();
            for (JsonElement el : obj.getAsJsonArray(key)) {
                if (el.isJsonPrimitive()) {
                    out.add(el.getAsString());
                }
            }
            return out;
        }
        return List.of();
    }

    private static FileTime mtimeOf(Path p) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            return attrs.lastModifiedTime();
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    /**
     * Internal cached entry: holds the parsed {@link ToolContract} and
     * the file's mtime at parse time.
     */
    private record CachedContract(ToolContract contract, FileTime mtime) {}
}
