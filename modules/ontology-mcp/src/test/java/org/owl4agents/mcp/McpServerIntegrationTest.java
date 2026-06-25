package org.owl4agents.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for MCP server JSON-RPC protocol.
 * Tests the full request/response cycle.
 */
@DisplayName("MCP server integration tests")
class McpServerIntegrationTest {

    @TempDir
    Path tempDir;

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
        // v0.7.0: protocolVersion upgraded from 2024-11-05 to 2025-06-18
        assertEquals("2025-06-18", result.get("protocolVersion").getAsString());
        assertTrue(result.has("capabilities"));
        assertTrue(result.has("serverInfo"));
        // v0.7.0: serverInfo.version is 0.7.0
        assertEquals("0.7.0", result.getAsJsonObject("serverInfo").get("version").getAsString());
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
     * Process a request by delegating to the public {@link McpServerAdapter#handleJsonRpc}
     * entry point. v0.7.0 removed the local switch duplication — both the stdio
     * transport and the HTTP transport now share this method, so the test
     * exercises the same code path as production.
     */
    private JsonObject processRequest(JsonObject request, McpServerAdapter adapter) {
        return adapter.handleJsonRpc(request);
    }
}
