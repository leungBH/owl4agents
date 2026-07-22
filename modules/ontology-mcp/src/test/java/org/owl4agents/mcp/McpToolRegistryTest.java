package org.owl4agents.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-17: McpToolRegistry readonly tool count.
 *
 * <p>Verifies that {@link McpToolRegistry#listToolSchemas()} returns exactly
 * 64 readonly tools. v0.8.7 adds 3 new SHACL readonly tools
 * (ontology_validate_shacl, ontology_list_shape_sets, ontology_get_shape_set)
 * bringing the count from 56 (v0.8.6 baseline) to 59, then adds 2 new
 * ToolCall readonly tools (ontology_get_tool_contract,
 * ontology_list_tool_contracts) bringing the count to 61, then adds 3 new
 * Pipeline readonly tools (ontology_validate_tool_call,
 * ontology_explain_tool_call, ontology_preview_tool_call_effects) bringing
 * the count to 64. The count is a regression marker tracked by the CI
 * version-alignment job.</p>
 */
@DisplayName("TC-17 McpToolRegistry readonly tool count is 64 (v0.8.7)")
class McpToolRegistryTest {

    @Test
    @DisplayName("listToolSchemas() returns exactly 64 readonly tools (v0.8.7)")
    void toolCountRemains64() {
        McpToolRegistry registry = new McpToolRegistry();
        List<Map<String, Object>> schemas = registry.listToolSchemas();
        assertEquals(64, schemas.size(),
            "Readonly tool count must be 64 in v0.8.7 (56 v0.8.6 baseline + 3 SHACL + 2 ToolCall + 3 Pipeline tools)");
    }

    @Test
    @DisplayName("Each tool schema has a non-blank 'name' and 'description'")
    void eachSchemaHasNameAndDescription() {
        McpToolRegistry registry = new McpToolRegistry();
        List<Map<String, Object>> schemas = registry.listToolSchemas();
        for (Map<String, Object> schema : schemas) {
            Object name = schema.get("name");
            Object description = schema.get("description");
            assertNotNull(name, "Every schema must have a 'name' field");
            assertNotNull(description, "Every schema must have a 'description' field");
            assertTrue(name instanceof String && !((String) name).isBlank(),
                "Every schema name must be a non-blank string");
            assertTrue(description instanceof String && !((String) description).isBlank(),
                "Every schema description must be a non-blank string");
        }
    }

    @Test
    @DisplayName("All schema names start with 'ontology_'")
    void allNamesAreOntologyPrefixed() {
        McpToolRegistry registry = new McpToolRegistry();
        List<Map<String, Object>> schemas = registry.listToolSchemas();
        for (Map<String, Object> schema : schemas) {
            String name = (String) schema.get("name");
            assertTrue(name.startsWith("ontology_"),
                "Tool name '" + name + "' must start with 'ontology_'");
        }
    }

    @Test
    @DisplayName("v0.8.7: 3 SHACL tools are registered as readonly")
    void shaclToolsAreRegistered() {
        McpToolRegistry registry = new McpToolRegistry();
        assertTrue(registry.isReadonlyTool("ontology_validate_shacl"),
            "ontology_validate_shacl must be a readonly tool");
        assertTrue(registry.isReadonlyTool("ontology_list_shape_sets"),
            "ontology_list_shape_sets must be a readonly tool");
        assertTrue(registry.isReadonlyTool("ontology_get_shape_set"),
            "ontology_get_shape_set must be a readonly tool");
    }

    @Test
    @DisplayName("v0.8.7: ontology_validate_shacl schema MUST NOT accept shapes_graph")
    void validateShaclSchemaRejectsInlineShapes() {
        McpToolRegistry registry = new McpToolRegistry();
        List<Map<String, Object>> schemas = registry.listToolSchemas();
        Map<String, Object> validateShaclSchema = schemas.stream()
            .filter(s -> "ontology_validate_shacl".equals(s.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("ontology_validate_shacl schema not found"));
        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchema = (Map<String, Object>) validateShaclSchema.get("inputSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
        // MUST include shape_set_id, data_graph, options
        assertTrue(properties.containsKey("shape_set_id"), "schema must include shape_set_id");
        assertTrue(properties.containsKey("data_graph"), "schema must include data_graph");
        assertTrue(properties.containsKey("options"), "schema must include options");
        // MUST NOT include any shapes upload parameter
        assertFalse(properties.containsKey("shapes_graph"), "schema MUST NOT include shapes_graph");
        assertFalse(properties.containsKey("shapes"), "schema MUST NOT include shapes");
        assertFalse(properties.containsKey("shapes_ttl"), "schema MUST NOT include shapes_ttl");
    }

    @Test
    @DisplayName("v0.8.7: 2 ToolCall tools are registered as readonly")
    void toolCallToolsAreRegistered() {
        McpToolRegistry registry = new McpToolRegistry();
        assertTrue(registry.isReadonlyTool("ontology_get_tool_contract"),
            "ontology_get_tool_contract must be a readonly tool");
        assertTrue(registry.isReadonlyTool("ontology_list_tool_contracts"),
            "ontology_list_tool_contracts must be a readonly tool");
    }

    @Test
    @DisplayName("v0.8.7: ontology_get_tool_contract schema accepts toolName")
    void getToolContractSchemaAcceptsToolName() {
        McpToolRegistry registry = new McpToolRegistry();
        List<Map<String, Object>> schemas = registry.listToolSchemas();
        Map<String, Object> schema = schemas.stream()
            .filter(s -> "ontology_get_tool_contract".equals(s.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "ontology_get_tool_contract schema not found"));
        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchema = (Map<String, Object>) schema.get("inputSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
        assertTrue(properties.containsKey("toolName"),
            "ontology_get_tool_contract schema must accept 'toolName' parameter");
    }

    @Test
    @DisplayName("v0.8.7: ontology_list_tool_contracts schema accepts no parameters")
    void listToolContractsSchemaAcceptsNoParams() {
        McpToolRegistry registry = new McpToolRegistry();
        List<Map<String, Object>> schemas = registry.listToolSchemas();
        Map<String, Object> schema = schemas.stream()
            .filter(s -> "ontology_list_tool_contracts".equals(s.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "ontology_list_tool_contracts schema not found"));
        assertNotNull(schema.get("description"),
            "ontology_list_tool_contracts must have a description");
    }

    @Test
    @DisplayName("v0.8.7: 3 Pipeline tools are registered as readonly")
    void pipelineToolsAreRegistered() {
        McpToolRegistry registry = new McpToolRegistry();
        assertTrue(registry.isReadonlyTool("ontology_validate_tool_call"),
            "ontology_validate_tool_call must be a readonly tool");
        assertTrue(registry.isReadonlyTool("ontology_explain_tool_call"),
            "ontology_explain_tool_call must be a readonly tool");
        assertTrue(registry.isReadonlyTool("ontology_preview_tool_call_effects"),
            "ontology_preview_tool_call_effects must be a readonly tool");
    }

    @Test
    @DisplayName("v0.8.7: ontology_validate_tool_call schema accepts ontology_id, call, state")
    void validateToolCallSchemaAcceptsExpectedParams() {
        McpToolRegistry registry = new McpToolRegistry();
        List<Map<String, Object>> schemas = registry.listToolSchemas();
        Map<String, Object> schema = schemas.stream()
            .filter(s -> "ontology_validate_tool_call".equals(s.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "ontology_validate_tool_call schema not found"));
        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchema = (Map<String, Object>) schema.get("inputSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
        assertTrue(properties.containsKey("ontology_id"),
            "ontology_validate_tool_call schema must accept 'ontology_id'");
        assertTrue(properties.containsKey("call"),
            "ontology_validate_tool_call schema must accept 'call'");
        assertTrue(properties.containsKey("state"),
            "ontology_validate_tool_call schema must accept 'state'");
    }

    @Test
    @DisplayName("v0.8.7: ontology_explain_tool_call schema accepts callId")
    void explainToolCallSchemaAcceptsCallId() {
        McpToolRegistry registry = new McpToolRegistry();
        List<Map<String, Object>> schemas = registry.listToolSchemas();
        Map<String, Object> schema = schemas.stream()
            .filter(s -> "ontology_explain_tool_call".equals(s.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "ontology_explain_tool_call schema not found"));
        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchema = (Map<String, Object>) schema.get("inputSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
        assertTrue(properties.containsKey("callId"),
            "ontology_explain_tool_call schema must accept 'callId'");
    }
}
