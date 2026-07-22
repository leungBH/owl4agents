package org.owl4agents.toolcall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ServiceResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-007 unit tests for {@link ToolContractRegistry} (filesystem load,
 * mtime hot-reload, {@link ErrorCode#TOOL_CONTRACT_NOT_FOUND}, list()).
 *
 * <p>Uses {@link TempDir} so each test has an isolated contracts directory
 * and does not touch the real {@code ~/.owl4agents/contracts/}.</p>
 */
@DisplayName("TC-007 ToolContractRegistry")
class ToolContractRegistryTest {

    @TempDir
    Path tempDir;

    private Path contractsDir() {
        Path d = tempDir.resolve("contracts");
        try {
            Files.createDirectories(d);
        } catch (java.io.IOException e) {
            fail("Cannot create test contracts dir: " + e.getMessage());
        }
        return d;
    }

    private void writeContract(Path dir, String toolName, String jsonContent) {
        try {
            Files.writeString(dir.resolve(toolName + ".json"), jsonContent);
        } catch (java.io.IOException e) {
            fail("Cannot write contract " + toolName + ": " + e.getMessage());
        }
    }

    private String contractJson(String toolName, String riskLevel,
                                boolean withShapes) {
        String shapeSetIds = withShapes
            ? ", \"shapeSetIds\": [\"smart-home-shapes-v1\"]"
            : ", \"shapeSetIds\": []";
        return """
            {
              "toolName": "%s",
              "inputSchema": {
                "type": "object",
                "properties": {
                  "targetTemperature": {"type": "integer"}
                },
                "required": ["targetTemperature"]
              },
              "targetClass": "http://example.org/Thermostat",
              "requiredCapabilities": ["http://example.org/TemperatureControl"],
              "requiredStates": [],
              "effects": ["changesState(thermostat, targetTemperature)"],
              "riskLevel": "%s",
              "requiredPermission": null,
              "shapeSetIds": %s
            }
            """.formatted(toolName, riskLevel,
                withShapes ? "[\"smart-home-shapes-v1\"]" : "[]");
    }

    // ── Scenario: Registered contract is queryable ──

    @Test
    @DisplayName("Registered contract is queryable without re-reading file")
    void registeredContractQueryable() {
        Path dir = contractsDir();
        writeContract(dir, "set_temperature", contractJson(
            "set_temperature", "low", false));
        ToolContractRegistry registry = new ToolContractRegistry(dir);

        ServiceResult<ToolContract> r1 = registry.get("set_temperature");
        assertTrue(r1.isSuccess(),
            "Registered contract must be queryable");
        ToolContract c1 = ((ServiceResult.Success<ToolContract>) r1).data();
        assertEquals("set_temperature", c1.toolName());
        assertEquals(RiskLevel.LOW, c1.riskLevel());
        assertEquals("http://example.org/Thermostat", c1.targetClass().orElse(null));
        assertEquals(List.of("http://example.org/TemperatureControl"),
            c1.requiredCapabilities());

        // Second call must hit the cache (no disk read).
        ServiceResult<ToolContract> r2 = registry.get("set_temperature");
        assertSame(c1, ((ServiceResult.Success<ToolContract>) r2).data(),
            "Cached entry must be returned without re-reading from disk");
    }

    // ── Scenario: Unregistered tool returns error ──

    @Test
    @DisplayName("Unregistered tool returns TOOL_CONTRACT_NOT_FOUND")
    void unregisteredToolReturnsNotFound() {
        Path dir = contractsDir();
        ToolContractRegistry registry = new ToolContractRegistry(dir);

        ServiceResult<ToolContract> r = registry.get("nonexistent_tool");
        assertFalse(r.isSuccess(),
            "Unregistered tool must not return success");
        ServiceResult.Error<ToolContract> err =
            (ServiceResult.Error<ToolContract>) r;
        assertEquals(ErrorCode.TOOL_CONTRACT_NOT_FOUND, err.error().code());
        // Per spec: error message must include requested toolName and path hint
        String msg = err.error().message();
        assertTrue(msg.contains("nonexistent_tool"),
            "Error message must include the requested toolName");
        assertTrue(msg.contains("contracts") || msg.contains(".json"),
            "Error message must hint at the expected file path convention");
    }

    @Test
    @DisplayName("TOOL_CONTRACT_NOT_FOUND details include expectedFilePath")
    void notFoundErrorDetailsIncludePath() {
        Path dir = contractsDir();
        ToolContractRegistry registry = new ToolContractRegistry(dir);

        ServiceResult<ToolContract> r = registry.get("missing");
        ServiceResult.Error<ToolContract> err =
            (ServiceResult.Error<ToolContract>) r;
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) err.error().details();
        assertNotNull(details);
        assertEquals("missing", details.get("toolName"));
        assertNotNull(details.get("expectedFilePath"));
        assertTrue(details.get("expectedFilePath").toString().endsWith("missing.json"));
    }

    // ── Scenario: Hot-reload after file modification ──

    @Test
    @DisplayName("Hot-reload detects file modification via mtime")
    void hotReloadDetectsMtimeChange() throws Exception {
        Path dir = contractsDir();
        writeContract(dir, "set_temperature", contractJson(
            "set_temperature", "low", false));
        ToolContractRegistry registry = new ToolContractRegistry(dir);

        // Initial load: riskLevel=low
        ServiceResult<ToolContract> r1 = registry.get("set_temperature");
        assertEquals(RiskLevel.LOW,
            ((ServiceResult.Success<ToolContract>) r1).data().riskLevel());

        // Modify the file on disk with a new riskLevel
        Path file = dir.resolve("set_temperature.json");
        Files.writeString(file, contractJson("set_temperature", "high", false));
        // Bump mtime forward (some filesystems have 1-second granularity)
        Files.setLastModifiedTime(file,
            FileTime.fromMillis(System.currentTimeMillis() + 2000));

        // Next get() must detect the change and reload
        ServiceResult<ToolContract> r2 = registry.get("set_temperature");
        ToolContract reloaded =
            ((ServiceResult.Success<ToolContract>) r2).data();
        assertEquals(RiskLevel.HIGH, reloaded.riskLevel(),
            "Hot-reload must pick up the new riskLevel from disk");
    }

    @Test
    @DisplayName("reload() forces a re-read even when mtime is unchanged")
    void forcedReloadBypassesMtimeCache() {
        Path dir = contractsDir();
        writeContract(dir, "set_temperature", contractJson(
            "set_temperature", "low", false));
        ToolContractRegistry registry = new ToolContractRegistry(dir);

        // Initial load
        ServiceResult<ToolContract> r1 = registry.get("set_temperature");
        ToolContract c1 = ((ServiceResult.Success<ToolContract>) r1).data();
        assertEquals(RiskLevel.LOW, c1.riskLevel());

        // Modify file with same mtime (simulate touch)
        Path file = dir.resolve("set_temperature.json");
        FileTime originalMtime;
        try {
            originalMtime = Files.getLastModifiedTime(file);
            Files.writeString(file, contractJson("set_temperature", "high", false));
            Files.setLastModifiedTime(file, originalMtime);
        } catch (java.io.IOException e) {
            fail("Cannot manipulate mtime: " + e.getMessage());
            return;
        }

        // Normal get() returns cached (mtime unchanged)
        ServiceResult<ToolContract> r2 = registry.get("set_temperature");
        assertEquals(RiskLevel.LOW,
            ((ServiceResult.Success<ToolContract>) r2).data().riskLevel(),
            "Same mtime must return cached value");

        // reload() forces re-read regardless of mtime
        ServiceResult<ToolContract> r3 = registry.reload("set_temperature");
        assertEquals(RiskLevel.HIGH,
            ((ServiceResult.Success<ToolContract>) r3).data().riskLevel(),
            "reload() must bypass the mtime cache");
    }

    @Test
    @DisplayName("reloadAll() clears the cache and reloads all contracts")
    void reloadAllClearsCache() {
        Path dir = contractsDir();
        writeContract(dir, "tool_a", contractJson("tool_a", "low", false));
        writeContract(dir, "tool_b", contractJson("tool_b", "medium", false));
        ToolContractRegistry registry = new ToolContractRegistry(dir);

        // Initial load
        assertTrue(registry.get("tool_a").isSuccess());
        assertTrue(registry.get("tool_b").isSuccess());

        // reloadAll
        registry.reloadAll();

        // Both should still be queryable after reloadAll
        assertTrue(registry.get("tool_a").isSuccess());
        assertTrue(registry.get("tool_b").isSuccess());
    }

    // ── Scenario: List registered contracts ──

    @Test
    @DisplayName("list() returns all contracts currently on disk")
    void listReturnsAllContracts() {
        Path dir = contractsDir();
        writeContract(dir, "tool_a", contractJson("tool_a", "low", false));
        writeContract(dir, "tool_b", contractJson("tool_b", "high", false));
        writeContract(dir, "tool_c", contractJson("tool_c", "medium", true));
        ToolContractRegistry registry = new ToolContractRegistry(dir);

        List<ToolContract> all = registry.list();
        assertEquals(3, all.size(),
            "list() must return every contract file in the directory");
        // Sorted by filename
        assertEquals("tool_a", all.get(0).toolName());
        assertEquals("tool_b", all.get(1).toolName());
        assertEquals("tool_c", all.get(2).toolName());
    }

    @Test
    @DisplayName("list() excludes removed files")
    void listExcludesRemovedFiles() throws Exception {
        Path dir = contractsDir();
        writeContract(dir, "tool_a", contractJson("tool_a", "low", false));
        writeContract(dir, "tool_b", contractJson("tool_b", "high", false));
        ToolContractRegistry registry = new ToolContractRegistry(dir);
        assertEquals(2, registry.list().size());

        // Remove one file
        Files.delete(dir.resolve("tool_b.json"));

        // list() must exclude the removed file
        List<ToolContract> remaining = registry.list();
        assertEquals(1, remaining.size(),
            "list() must exclude removed files per spec");
        assertEquals("tool_a", remaining.get(0).toolName());
    }

    @Test
    @DisplayName("list() returns empty list for empty directory")
    void listEmptyDirectory() {
        Path dir = contractsDir();
        ToolContractRegistry registry = new ToolContractRegistry(dir);
        assertTrue(registry.list().isEmpty());
    }

    @Test
    @DisplayName("list() returns empty list for non-existent directory")
    void listNonExistentDirectory() {
        Path dir = tempDir.resolve("does-not-exist");
        ToolContractRegistry registry = new ToolContractRegistry(dir);
        assertTrue(registry.list().isEmpty(),
            "Non-existent directory must return empty list (not throw)");
    }

    // ── register() ──

    @Test
    @DisplayName("register() writes contract to disk and updates cache")
    void registerWritesToDisk() {
        Path dir = contractsDir();
        ToolContractRegistry registry = new ToolContractRegistry(dir);

        ToolContract c = new ToolContract("dynamic_tool",
            Map.of("type", "object"),
            java.util.Optional.of("http://ex#Target"),
            List.of(), List.of(), List.of(),
            RiskLevel.MEDIUM, java.util.Optional.empty(), List.of("ss1"));
        ServiceResult<ToolContract> r = registry.register(c);
        assertTrue(r.isSuccess());

        // File must exist on disk
        assertTrue(Files.exists(dir.resolve("dynamic_tool.json")),
            "register() must write the contract to <contractsDir>/<toolName>.json");

        // Subsequent get() must return the registered contract
        ServiceResult<ToolContract> r2 = registry.get("dynamic_tool");
        assertTrue(r2.isSuccess());
        assertEquals(RiskLevel.MEDIUM,
            ((ServiceResult.Success<ToolContract>) r2).data().riskLevel());
    }

    // ── Defensive behaviors ──

    @Test
    @DisplayName("Blank toolName rejected with INVALID_ARGUMENTS")
    void blankToolNameRejected() {
        Path dir = contractsDir();
        ToolContractRegistry registry = new ToolContractRegistry(dir);

        ServiceResult<ToolContract> r = registry.get("");
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.INVALID_ARGUMENTS,
            ((ServiceResult.Error<ToolContract>) r).error().code());
    }

    @Test
    @DisplayName("Path-traversal toolName is sanitized")
    void pathTraversalSanitized() {
        Path dir = contractsDir();
        writeContract(dir, "safe_tool", contractJson("safe_tool", "low", false));
        ToolContractRegistry registry = new ToolContractRegistry(dir);

        // Path separators must be stripped from toolName
        // "../safe_tool" should be sanitized to "safe_tool" (dots and
        // slashes stripped, underscore preserved).
        ServiceResult<ToolContract> r = registry.get("../safe_tool");
        // After sanitization, it should resolve to "safe_tool.json"
        assertTrue(r.isSuccess(),
            "Path separators in toolName must be stripped (sanitized to 'safe_tool')");
    }

    @Test
    @DisplayName("Malformed JSON file returns INVALID_ARGUMENTS")
    void malformedJsonReturnsError() {
        Path dir = contractsDir();
        writeContract(dir, "bad", "this is not valid JSON {");
        ToolContractRegistry registry = new ToolContractRegistry(dir);

        ServiceResult<ToolContract> r = registry.get("bad");
        assertFalse(r.isSuccess());
        ServiceResult.Error<ToolContract> err =
            (ServiceResult.Error<ToolContract>) r;
        // Malformed JSON -> INVALID_ARGUMENTS (parse failure)
        assertEquals(ErrorCode.INVALID_ARGUMENTS, err.error().code());
    }

    @Test
    @DisplayName("Empty file returns INVALID_ARGUMENTS")
    void emptyFileReturnsError() {
        Path dir = contractsDir();
        writeContract(dir, "empty", "");
        ToolContractRegistry registry = new ToolContractRegistry(dir);

        ServiceResult<ToolContract> r = registry.get("empty");
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.INVALID_ARGUMENTS,
            ((ServiceResult.Error<ToolContract>) r).error().code());
    }

    @Test
    @DisplayName("Filename overrides mismatched toolName field in JSON")
    void filenameOverridesToolNameField() {
        Path dir = contractsDir();
        // File is "tool_a.json" but the JSON says "toolName": "different_name"
        writeContract(dir, "tool_a", contractJson("different_name", "low", false));
        ToolContractRegistry registry = new ToolContractRegistry(dir);

        ServiceResult<ToolContract> r = registry.get("tool_a");
        assertTrue(r.isSuccess());
        ToolContract c = ((ServiceResult.Success<ToolContract>) r).data();
        // Per spec: filename is the canonical id
        assertEquals("tool_a", c.toolName(),
            "Filename must override mismatched toolName field in JSON");
    }

    @Test
    @DisplayName("contractsDirectory() exposes the configured directory")
    void contractsDirectoryExposed() {
        Path dir = contractsDir();
        ToolContractRegistry registry = new ToolContractRegistry(dir);
        assertEquals(dir.toAbsolutePath().normalize(),
            registry.contractsDirectory().toAbsolutePath().normalize());
    }
}
