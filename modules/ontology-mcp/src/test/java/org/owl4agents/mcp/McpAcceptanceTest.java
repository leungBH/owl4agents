package org.owl4agents.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ServiceError;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP acceptance tests for schema, behavior, logging, and readonly rejection.
 */
@DisplayName("MCP server acceptance tests")
class McpAcceptanceTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("MCP tool schema")
    class SchemaTests {

        @Test
        @DisplayName("All v0.1 readonly tools are registered")
        void allReadonlyToolsRegistered() {
            McpToolRegistry registry = new McpToolRegistry();
            List<Map<String, Object>> tools = registry.listToolSchemas();

            // v0.1 had 18 readonly tools, v0.2 adds 27 more = 45 total
            assertTrue(tools.size() >= 18, "At least v0.1 readonly tools must be registered");

            // Verify all expected v0.1 tool names
            List<String> toolNames = tools.stream()
                .map(t -> t.get("name").toString())
                .toList();

            assertTrue(toolNames.contains("ontology_list"));
            assertTrue(toolNames.contains("ontology_summary"));
            assertTrue(toolNames.contains("ontology_search_entities"));
            assertTrue(toolNames.contains("ontology_sparql_select"));
            assertTrue(toolNames.contains("ontology_get_qa_context"));

            // v0.2 reasoner tools
            assertTrue(toolNames.contains("ontology_classify"));
            assertTrue(toolNames.contains("ontology_check_consistency"));
            assertTrue(toolNames.contains("ontology_get_reasoning_report"));

            // v0.2 semantic-deepening tools
            assertTrue(toolNames.contains("ontology_get_class_restrictions"));
            assertTrue(toolNames.contains("ontology_get_imports"));
        }

        @Test
        @DisplayName("Readonly tools are identified correctly")
        void readonlyToolIdentification() {
            McpToolRegistry registry = new McpToolRegistry();

            assertTrue(registry.isReadonlyTool("ontology_summary"));
            assertTrue(registry.isReadonlyTool("ontology_search_entities"));
            assertTrue(registry.isReadonlyTool("ontology_sparql_select"));
            assertFalse(registry.isReadonlyTool("ontology_import"));
            assertFalse(registry.isReadonlyTool("ontology_edit_axiom"));
        }
    }

    @Nested
    @DisplayName("Readonly rejection")
    class ReadonlyRejectionTests {

        @Test
        @DisplayName("Write-style tool calls are rejected")
        void writeToolRejected() {
            McpServerAdapter adapter = new McpServerAdapter(Map.of(), tempDir.toString());
            Map<String, Object> result = adapter.handleToolCall("ontology_import", Map.of());

            assertEquals("error", result.get("status"));
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            assertEquals(ErrorCode.READONLY_VIOLATION.code(), error.get("code"));
        }

        @Test
        @DisplayName("Unknown tool calls are rejected")
        void unknownToolRejected() {
            McpServerAdapter adapter = new McpServerAdapter(Map.of(), tempDir.toString());
            Map<String, Object> result = adapter.handleToolCall("unknown_tool", Map.of());

            assertEquals("error", result.get("status"));
        }
    }

    @Nested
    @DisplayName("MCP tool response validation (non-placeholder)")
    class ResponseValidationTests {

        @Test
        @DisplayName("SPARQL validation returns real validation data, not placeholder")
        void sparqlValidationReturnsRealData() {
            McpServerAdapter adapter = new McpServerAdapter(Map.of(), tempDir.toString());

            // Valid SELECT query should return real validation result
            Map<String, Object> result = adapter.handleToolCall("ontology_validate_sparql",
                Map.of("query", "SELECT ?s WHERE { ?s a <http://example.org/Thing> }"));

            assertEquals("success", result.get("status"));
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            assertNotNull(data);
            assertTrue(data.containsKey("valid"));
            assertTrue(data.containsKey("queryForm"));
            assertEquals(true, data.get("valid"));
            assertEquals("SELECT", data.get("queryForm"));
        }

        @Test
        @DisplayName("SPARQL validation rejects invalid query with error, not placeholder")
        void sparqlValidationRejectsInvalidQuery() {
            McpServerAdapter adapter = new McpServerAdapter(Map.of(), tempDir.toString());

            Map<String, Object> result = adapter.handleToolCall("ontology_validate_sparql",
                Map.of("query", "INVALID QUERY"));

            // Should return error status, not a placeholder success
            assertEquals("error", result.get("status"));
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            assertNotNull(error);
            assertTrue(error.containsKey("code"));
            assertEquals("INVALID_SPARQL", error.get("code"));
        }

        @Test
        @DisplayName("SPARQL validation rejects update queries with READONLY_VIOLATION")
        void sparqlValidationRejectsUpdateQuery() {
            McpServerAdapter adapter = new McpServerAdapter(Map.of(), tempDir.toString());

            Map<String, Object> result = adapter.handleToolCall("ontology_validate_sparql",
                Map.of("query", "INSERT DATA { <x> a <y> }"));

            assertEquals("error", result.get("status"));
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            assertNotNull(error);
            assertEquals("READONLY_VIOLATION", error.get("code"));
        }

        @Test
        @DisplayName("Tool responses have proper structure (status + data for success)")
        void toolResponseStructure() {
            McpServerAdapter adapter = new McpServerAdapter(Map.of(), tempDir.toString());

            Map<String, Object> result = adapter.handleToolCall("ontology_list", Map.of());
            assertEquals("success", result.get("status"));
            assertNotNull(result.get("data"));
        }
    }

    @Nested
    @DisplayName("MCP tool call logging")
    class LoggingTests {

        @Test
        @DisplayName("Tool calls are logged with required fields")
        void toolCallLogging() throws Exception {
            Path logFile = tempDir.resolve("mcp-tool-calls.jsonl");
            McpToolCallLogger logger = new McpToolCallLogger(logFile.toString());

            logger.logCall(java.time.Instant.now(), "ontology_summary", "pizza", "success", null);

            assertTrue(Files.exists(logFile));
            String content = Files.readString(logFile);
            assertTrue(content.contains("ontology_summary"));
            assertTrue(content.contains("pizza"));
            assertTrue(content.contains("success"));
        }

        @Test
        @DisplayName("Failed tool calls are logged with error code")
        void failedCallLogging() throws Exception {
            Path logFile = tempDir.resolve("mcp-tool-calls.jsonl");
            McpToolCallLogger logger = new McpToolCallLogger(logFile.toString());

            logger.logCall(java.time.Instant.now(), "ontology_import", "pizza", "rejected", "READONLY_VIOLATION");

            String content = Files.readString(logFile);
            assertTrue(content.contains("READONLY_VIOLATION"));
            assertTrue(content.contains("rejected"));
        }
    }
}