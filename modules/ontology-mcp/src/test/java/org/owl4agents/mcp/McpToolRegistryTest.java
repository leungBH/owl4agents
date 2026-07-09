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
 * 56 readonly tools. v0.8.1 adds no new tools — only fixes accuracy defects
 * in existing ones — so the count MUST remain 56. The count is a regression
 * marker tracked by the CI version-alignment job.</p>
 */
@DisplayName("TC-17 McpToolRegistry readonly tool count remains 56")
class McpToolRegistryTest {

    @Test
    @DisplayName("listToolSchemas() returns exactly 56 readonly tools")
    void toolCountRemains56() {
        McpToolRegistry registry = new McpToolRegistry();
        List<Map<String, Object>> schemas = registry.listToolSchemas();
        assertEquals(56, schemas.size(),
            "Readonly tool count must remain 56 in v0.8.1 (no new tools added)");
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
}
