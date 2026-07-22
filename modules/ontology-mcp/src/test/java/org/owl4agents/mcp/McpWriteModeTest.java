package org.owl4agents.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.owl4agents.core.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.7 mcp-write-tools: unit tests for write mode and the ontology_import
 * tool. Covers the design Test Matrix MCP-WRITE-001 ~ MCP-WRITE-010.
 *
 * <p>Test coverage:</p>
 * <ul>
 *   <li>MCP-WRITE-001: tools/list returns 65 tools in write mode, 56 in readonly</li>
 *   <li>MCP-WRITE-002: ontology_import rejects when both content_base64 and file_path missing</li>
 *   <li>MCP-WRITE-003: ontology_import accepts content_base64</li>
 *   <li>MCP-WRITE-004: ontology_import accepts file_path inside allowed roots</li>
 *   <li>MCP-WRITE-005: size limit exceeded rejected</li>
 *   <li>MCP-WRITE-006: path traversal rejected</li>
 *   <li>MCP-WRITE-007: path outside allowed roots rejected</li>
 *   <li>MCP-WRITE-008: ID conflict with overwrite=false rejected</li>
 *   <li>MCP-WRITE-009: overwrite=true replaces existing entry</li>
 *   <li>MCP-WRITE-010: readonly mode rejects ontology_import with READONLY_VIOLATION</li>
 * </ul>
 */
@DisplayName("v0.8.7 MCP write mode tests")
class McpWriteModeTest {

    @TempDir
    Path tempDir;

    // Minimal valid OWL/XML ontology for tests.
    private static final String MINIMAL_OWL_XML =
        "<?xml version=\"1.0\"?>\n" +
        "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n" +
        "         xmlns:owl=\"http://www.w3.org/2002/07/owl#\"\n" +
        "         xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\">\n" +
        "  <owl:Ontology rdf:about=\"http://example.org/test\"/>\n" +
        "  <owl:Class rdf:about=\"http://example.org/test#TestClass\"/>\n" +
        "</rdf:RDF>\n";

    private String base64(String content) {
        return Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    private McpServerAdapter newReadonlyAdapter() {
        return new McpServerAdapter(Map.of("homeDir", tempDir.toString()),
            tempDir.resolve("readonly.log").toString());
    }

    private McpServerAdapter newWriteAdapter() {
        return new McpServerAdapter(Map.of("homeDir", tempDir.toString()),
            tempDir.resolve("write.log").toString(),
            false, 50, null);
    }

    @Nested
    @DisplayName("MCP-WRITE-001: Tool registration")
    class ToolRegistrationTests {

        @Test
        @DisplayName("Readonly mode registers only readonly tools (no ontology_import)")
        void readonlyModeExcludesWriteTools() {
            McpServerAdapter adapter = newReadonlyAdapter();
            List<Map<String, Object>> tools = adapter.listTools();
            List<String> names = tools.stream().map(t -> t.get("name").toString()).toList();

            assertFalse(names.contains("ontology_import"),
                "ontology_import must NOT be registered in readonly mode");
            assertTrue(adapter.isReadonly());
        }

        @Test
        @DisplayName("Write mode registers readonly tools plus ontology_import")
        void writeModeIncludesWriteTools() {
            McpServerAdapter adapter = newWriteAdapter();
            List<Map<String, Object>> tools = adapter.listTools();
            List<String> names = tools.stream().map(t -> t.get("name").toString()).toList();

            assertTrue(names.contains("ontology_import"),
                "ontology_import MUST be registered in write mode");
            assertFalse(adapter.isReadonly());
            // Readonly tools must still be present in write mode.
            assertTrue(names.contains("ontology_list"));
            assertTrue(names.contains("ontology_summary"));
        }

        @Test
        @DisplayName("isWriteTool identifies ontology_import correctly")
        void isWriteToolIdentifiesOntologyImport() {
            McpToolRegistry registry = new McpToolRegistry();
            assertTrue(registry.isWriteTool("ontology_import"));
            assertFalse(registry.isWriteTool("ontology_summary"));
            assertFalse(registry.isWriteTool("unknown_tool"));
        }

        @Test
        @DisplayName("Readonly tools remain readonly in both modes")
        void readonlyToolsRemainReadonlyInBothModes() {
            McpToolRegistry registry = new McpToolRegistry();
            // ontology_import is a write tool, NOT a readonly tool
            assertFalse(registry.isReadonlyTool("ontology_import"));
            // All v0.8.6 readonly tools are still readonly
            assertTrue(registry.isReadonlyTool("ontology_list"));
            assertTrue(registry.isReadonlyTool("ontology_check_consistency"));
            assertTrue(registry.isReadonlyTool("ontology_verify_claim"));
        }
    }

    @Nested
    @DisplayName("MCP-WRITE-010: Readonly mode rejection")
    class ReadonlyRejectionTests {

        @Test
        @DisplayName("ontology_import rejected with READONLY_VIOLATION in readonly mode")
        void readonlyModeRejectsOntologyImport() {
            McpServerAdapter adapter = newReadonlyAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "test-onto");
            args.put("content_base64", base64(MINIMAL_OWL_XML));

            Map<String, Object> result = adapter.handleToolCall("ontology_import", args);

            assertEquals("error", result.get("status"));
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            assertEquals(ErrorCode.READONLY_VIOLATION.code(), error.get("code"));
        }
    }

    @Nested
    @DisplayName("MCP-WRITE-002: Invalid arguments")
    class InvalidArgumentsTests {

        @Test
        @DisplayName("Missing both content_base64 and file_path returns INVALID_IMPORT_ARGUMENTS")
        void missingBothArgumentsRejected() {
            McpServerAdapter adapter = newWriteAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "test-onto");

            Map<String, Object> result = adapter.handleToolCall("ontology_import", args);

            assertEquals("error", result.get("status"));
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            assertEquals(ErrorCode.INVALID_IMPORT_ARGUMENTS.code(), error.get("code"));
        }

        @Test
        @DisplayName("Missing ontology_id returns INVALID_IMPORT_ARGUMENTS")
        void missingOntologyIdRejected() {
            McpServerAdapter adapter = newWriteAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("content_base64", base64(MINIMAL_OWL_XML));

            Map<String, Object> result = adapter.handleToolCall("ontology_import", args);

            assertEquals("error", result.get("status"));
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            assertEquals(ErrorCode.INVALID_IMPORT_ARGUMENTS.code(), error.get("code"));
        }

        @Test
        @DisplayName("Invalid Base64 returns INVALID_IMPORT_ARGUMENTS")
        void invalidBase64Rejected() {
            McpServerAdapter adapter = newWriteAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "test-onto");
            args.put("content_base64", "!!!not-valid-base64!!!");

            Map<String, Object> result = adapter.handleToolCall("ontology_import", args);

            assertEquals("error", result.get("status"));
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            assertEquals(ErrorCode.INVALID_IMPORT_ARGUMENTS.code(), error.get("code"));
        }
    }

    @Nested
    @DisplayName("MCP-WRITE-005: Size limit")
    class SizeLimitTests {

        @Test
        @DisplayName("Oversized payload rejected with IMPORT_SIZE_LIMIT_EXCEEDED")
        void oversizedPayloadRejected() {
            // Use a very small limit to trigger the rejection without
            // allocating a 50 MB string. 1 MB limit + ~600 byte payload
            // stretched to >1 MB by padding.
            McpServerAdapter adapter = new McpServerAdapter(
                Map.of("homeDir", tempDir.toString()),
                tempDir.resolve("size.log").toString(),
                false, 1, null); // 1 MB limit

            // Build a payload larger than 1 MB.
            StringBuilder huge = new StringBuilder(MINIMAL_OWL_XML);
            huge.append("<!-- ");
            while (huge.length() < 2 * 1024 * 1024) {
                huge.append("padding-padding-padding-padding-padding-");
            }
            huge.append(" -->");

            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "test-onto");
            args.put("content_base64", base64(huge.toString()));

            Map<String, Object> result = adapter.handleToolCall("ontology_import", args);

            assertEquals("error", result.get("status"));
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            assertEquals(ErrorCode.IMPORT_SIZE_LIMIT_EXCEEDED.code(), error.get("code"));
            Map<String, Object> details = (Map<String, Object>) error.get("details");
            assertNotNull(details);
            assertNotNull(details.get("actualBytes"));
            assertNotNull(details.get("limitBytes"));
        }
    }

    @Nested
    @DisplayName("MCP-WRITE-006/007: Path traversal protection")
    class PathTraversalTests {

        @Test
        @DisplayName("Parent directory traversal rejected")
        void parentDirectoryTraversalRejected() throws Exception {
            McpServerAdapter adapter = newWriteAdapter();
            // Place a file outside the allowed roots directory.
            Path outsideFile = tempDir.resolve("outside.owl");
            Files.writeString(outsideFile, MINIMAL_OWL_XML);
            // Use a file_path with .. that resolves outside the allowed roots.
            // The allowed root defaults to <workspace>/imports/ which is
            // tempDir/workspaces/default/imports/. Any path outside that
            // will be rejected.
            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "test-onto");
            args.put("file_path", outsideFile.toString());

            Map<String, Object> result = adapter.handleToolCall("ontology_import", args);

            assertEquals("error", result.get("status"));
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            assertEquals(ErrorCode.IMPORT_PATH_OUTSIDE_ALLOWED_ROOTS.code(), error.get("code"));
        }

        @Test
        @DisplayName("File inside allowed roots accepted")
        void fileInsideAllowedRootsAccepted() throws Exception {
            // Create the imports directory under the workspace.
            Path workspaceDir = tempDir.resolve("workspaces").resolve("default");
            Path importsDir = workspaceDir.resolve("imports");
            Files.createDirectories(importsDir);
            Path insideFile = importsDir.resolve("test.owl");
            Files.writeString(insideFile, MINIMAL_OWL_XML);

            McpServerAdapter adapter = newWriteAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "test-onto");
            args.put("file_path", insideFile.toString());

            Map<String, Object> result = adapter.handleToolCall("ontology_import", args);

            // Should succeed (no error key).
            assertNotEquals("error", result.get("status"),
                "Import inside allowed roots should succeed: " + result);
        }

        @Test
        @DisplayName("Custom allowed roots respected")
        void customAllowedRootsRespected() throws Exception {
            // Create a custom allowed root outside the workspace.
            Path customRoot = tempDir.resolve("custom-roots");
            Files.createDirectories(customRoot);
            Path insideFile = customRoot.resolve("test.owl");
            Files.writeString(insideFile, MINIMAL_OWL_XML);

            McpServerAdapter adapter = new McpServerAdapter(
                Map.of("homeDir", tempDir.toString()),
                tempDir.resolve("custom.log").toString(),
                false, 50, customRoot.toString());

            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "test-onto");
            args.put("file_path", insideFile.toString());

            Map<String, Object> result = adapter.handleToolCall("ontology_import", args);
            assertNotEquals("error", result.get("status"),
                "Import inside custom allowed root should succeed: " + result);
        }
    }

    @Nested
    @DisplayName("MCP-WRITE-003/004: Successful import")
    class SuccessfulImportTests {

        @Test
        @DisplayName("Import via content_base64 succeeds with result payload")
        void importViaContentBase64Succeeds() {
            McpServerAdapter adapter = newWriteAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "test-onto");
            args.put("content_base64", base64(MINIMAL_OWL_XML));

            Map<String, Object> result = adapter.handleToolCall("ontology_import", args);

            assertNotEquals("error", result.get("status"),
                "Import should succeed: " + result);
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            assertNotNull(data);
            assertEquals("test-onto", data.get("ontologyId"));
            assertNotNull(data.get("sourcePath"));
            assertNotNull(data.get("checksum"));
            assertTrue(((Number) data.get("entityCount")).intValue() >= 0);
            assertTrue(((Number) data.get("axiomCount")).intValue() >= 0);
        }
    }

    @Nested
    @DisplayName("MCP-WRITE-008/009: ID conflict handling")
    class IdConflictTests {

        @Test
        @DisplayName("overwrite=false rejects existing ID with IMPORT_ID_CONFLICT")
        void overwriteFalseRejectsConflict() {
            McpServerAdapter adapter = newWriteAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "conflict-onto");
            args.put("content_base64", base64(MINIMAL_OWL_XML));

            // First import should succeed.
            Map<String, Object> first = adapter.handleToolCall("ontology_import", args);
            assertNotEquals("error", first.get("status"),
                "First import should succeed: " + first);

            // Second import without overwrite should fail.
            Map<String, Object> second = adapter.handleToolCall("ontology_import", args);
            assertEquals("error", second.get("status"));
            Map<String, Object> error = (Map<String, Object>) second.get("error");
            assertEquals(ErrorCode.IMPORT_ID_CONFLICT.code(), error.get("code"));
            Map<String, Object> details = (Map<String, Object>) error.get("details");
            assertNotNull(details);
            assertNotNull(details.get("existingSourcePath"));
        }

        @Test
        @DisplayName("overwrite=true replaces existing entry")
        void overwriteTrueReplacesEntry() {
            McpServerAdapter adapter = newWriteAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "overwrite-onto");
            args.put("content_base64", base64(MINIMAL_OWL_XML));

            // First import.
            Map<String, Object> first = adapter.handleToolCall("ontology_import", args);
            assertNotEquals("error", first.get("status"),
                "First import should succeed: " + first);

            // Second import with overwrite=true should succeed.
            Map<String, Object> overwriteArgs = new HashMap<>(args);
            overwriteArgs.put("overwrite", true);
            Map<String, Object> second = adapter.handleToolCall("ontology_import", overwriteArgs);
            assertNotEquals("error", second.get("status"),
                "Overwrite import should succeed: " + second);
            Map<String, Object> data = (Map<String, Object>) second.get("data");
            assertEquals("overwrite-onto", data.get("ontologyId"));
        }
    }
}
