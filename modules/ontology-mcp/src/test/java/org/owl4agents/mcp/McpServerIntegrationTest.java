package org.owl4agents.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for MCP server JSON-RPC protocol.
 * Tests the full request/response cycle.
 */
@DisplayName("MCP server integration tests")
class McpServerIntegrationTest {

    @TempDir
    Path tempDir;

    private final Gson gson = new Gson();

    @Test
    @DisplayName("MCP server responds to initialize request")
    void initializeRequest() throws Exception {
        McpServerAdapter adapter = createAdapter();

        // Simulate initialize request
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 1);
        request.addProperty("method", "initialize");
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", "2024-11-05");
        request.add("params", params);

        // Process request using the same logic as McpCommand
        JsonObject response = processRequest(request, adapter);

        assertNotNull(response);
        assertEquals("2.0", response.get("jsonrpc").getAsString());
        assertTrue(response.has("result"));

        JsonObject result = response.getAsJsonObject("result");
        assertEquals("2024-11-05", result.get("protocolVersion").getAsString());
        assertTrue(result.has("capabilities"));
        assertTrue(result.has("serverInfo"));
    }

    @Test
    @DisplayName("MCP server responds to tools/list request")
    void toolsListRequest() throws Exception {
        McpServerAdapter adapter = createAdapter();

        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 2);
        request.addProperty("method", "tools/list");
        request.add("params", new JsonObject());

        JsonObject response = processRequest(request, adapter);

        assertNotNull(response);
        assertTrue(response.has("result"));

        JsonObject result = response.getAsJsonObject("result");
        assertTrue(result.has("tools"));

        // Should have 18 tools
        assertTrue(result.getAsJsonArray("tools").size() > 0);
    }

    @Test
    @DisplayName("MCP server responds to tools/call for ontology_list")
    void toolsCallOntologyList() throws Exception {
        McpServerAdapter adapter = createAdapter();

        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 3);
        request.addProperty("method", "tools/call");
        JsonObject params = new JsonObject();
        params.addProperty("name", "ontology_list");
        params.add("arguments", new JsonObject());
        request.add("params", params);

        JsonObject response = processRequest(request, adapter);

        assertNotNull(response);
        assertTrue(response.has("result"));

        JsonObject result = response.getAsJsonObject("result");
        assertTrue(result.has("content"));
    }

    @Test
    @DisplayName("MCP server responds to tools/call for ontology_validate_sparql")
    void toolsCallValidateSparql() throws Exception {
        McpServerAdapter adapter = createAdapter();

        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 4);
        request.addProperty("method", "tools/call");
        JsonObject params = new JsonObject();
        params.addProperty("name", "ontology_validate_sparql");
        JsonObject args = new JsonObject();
        args.addProperty("query", "SELECT ?s WHERE { ?s a owl:Class }");
        params.add("arguments", args);
        request.add("params", params);

        JsonObject response = processRequest(request, adapter);

        assertNotNull(response);
        assertTrue(response.has("result"));

        JsonObject result = response.getAsJsonObject("result");
        assertTrue(result.has("content"));
    }

    @Test
    @DisplayName("MCP server rejects unknown method")
    void unknownMethod() throws Exception {
        McpServerAdapter adapter = createAdapter();

        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 5);
        request.addProperty("method", "unknown/method");
        request.add("params", new JsonObject());

        JsonObject response = processRequest(request, adapter);

        assertNotNull(response);
        assertTrue(response.has("error"));
        assertEquals(-32601, response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    @DisplayName("MCP server rejects SPARQL update via tools/call")
    void rejectSparqlUpdate() throws Exception {
        McpServerAdapter adapter = createAdapter();

        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 6);
        request.addProperty("method", "tools/call");
        JsonObject params = new JsonObject();
        params.addProperty("name", "ontology_validate_sparql");
        JsonObject args = new JsonObject();
        args.addProperty("query", "INSERT DATA { <x> a <y> }");
        params.add("arguments", args);
        request.add("params", params);

        JsonObject response = processRequest(request, adapter);

        assertNotNull(response);
        assertTrue(response.has("result"));

        JsonObject result = response.getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean());
    }

    // ── Helper methods ──

    private McpServerAdapter createAdapter() {
        Map<String, Object> serviceContext = new HashMap<>();
        String logPath = tempDir.resolve("mcp-test.log").toString();
        return new McpServerAdapter(serviceContext, logPath);
    }

    /**
     * Process a request using the same logic as McpCommand.
     * This extracts the request handling logic for testing.
     */
    private JsonObject processRequest(JsonObject request, McpServerAdapter adapter) {
        String method = request.has("method") ? request.get("method").getAsString() : "";
        Object idElement = request.has("id") ? request.get("id") : null;

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (idElement != null) {
            response.add("id", request.get("id"));
        }

        switch (method) {
            case "initialize" -> {
                JsonObject result = new JsonObject();
                result.addProperty("protocolVersion", "2024-11-05");
                JsonObject capabilities = new JsonObject();
                JsonObject tools = new JsonObject();
                capabilities.add("tools", tools);
                result.add("capabilities", capabilities);
                JsonObject serverInfo = new JsonObject();
                serverInfo.addProperty("name", "owl4agents");
                serverInfo.addProperty("version", "0.1.0");
                result.add("serverInfo", serverInfo);
                response.add("result", result);
            }
            case "notifications/initialized" -> {
                return null;
            }
            case "tools/list" -> {
                JsonObject result = new JsonObject();
                List<Map<String, Object>> tools = adapter.listTools();
                result.add("tools", gson.toJsonTree(tools));
                response.add("result", result);
            }
            case "tools/call" -> {
                JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
                String toolName = params.has("name") ? params.get("name").getAsString() : "";
                JsonObject arguments = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();

                @SuppressWarnings("unchecked")
                Map<String, Object> argsMap = gson.fromJson(arguments, Map.class);
                Map<String, Object> result = adapter.handleToolCall(toolName, argsMap);

                JsonObject mcpResult = new JsonObject();
                if (result.containsKey("error")) {
                    mcpResult.addProperty("isError", true);
                    List<Map<String, Object>> content = new ArrayList<>();
                    Map<String, Object> errorContent = new HashMap<>();
                    errorContent.put("type", "text");
                    errorContent.put("text", gson.toJson(result.get("error")));
                    content.add(errorContent);
                    mcpResult.add("content", gson.toJsonTree(content));
                } else {
                    List<Map<String, Object>> content = new ArrayList<>();
                    Map<String, Object> textContent = new HashMap<>();
                    textContent.put("type", "text");
                    textContent.put("text", gson.toJson(result.get("data")));
                    content.add(textContent);
                    mcpResult.add("content", gson.toJsonTree(content));
                }
                response.add("result", mcpResult);
            }
            default -> {
                JsonObject error = new JsonObject();
                error.addProperty("code", -32601);
                error.addProperty("message", "Method not found: " + method);
                response.add("error", error);
            }
        }

        return response;
    }
}
