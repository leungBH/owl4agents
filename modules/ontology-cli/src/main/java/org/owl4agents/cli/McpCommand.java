package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.owl4agents.mcp.McpServerAdapter;
import org.owl4agents.storage.HomeDirectoryResolver;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * CLI command entrypoint for starting the MCP server.
 * Implements JSON-RPC 2.0 over stdin/stdout.
 */
@Command(name = "mcp", description = "Start the readonly MCP server.")
public class McpCommand implements Callable<Integer> {

    @Option(names = {"--readonly"}, description = "Start MCP in readonly mode (default)", arity = "0..1", fallbackValue = "true", defaultValue = "true")
    private boolean readonly = true;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Option(names = {"--home"}, description = "owl4agents home directory override")
    private String homeDirectory;

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private final Gson gson = GsonFactory.createGson();

    @Override
    public Integer call() {
        if (!readonly) {
            System.err.println("Error: owl4agents only supports readonly MCP mode. Use --readonly (default).");
            return 1;
        }

        // Initialize MCP adapter
        Map<String, Object> serviceContext = new HashMap<>();
        if (homeDirectory != null) {
            serviceContext.put("homeDir", homeDirectory);
        }

        HomeDirectoryResolver homeResolver = homeDirectory != null
            ? new HomeDirectoryResolver(Path.of(homeDirectory))
            : new HomeDirectoryResolver();
        String logFilePath = homeResolver.resolveWorkspaceDirectory(new org.owl4agents.core.WorkspaceId(workspaceName))
            .resolve("logs")
            .resolve("mcp-tool-calls.jsonl")
            .toString();

        McpServerAdapter adapter = new McpServerAdapter(serviceContext, logFilePath);

        // MCP server runs on stdin/stdout
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        PrintWriter writer = new PrintWriter(System.out, true);

        // Signal that server is ready (write to stderr so it doesn't interfere with protocol)
        System.err.println("owl4agents MCP server started in readonly mode");

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                    JsonObject response = handleRequest(request, adapter);
                    writer.println(gson.toJson(response));
                    writer.flush();
                } catch (Exception e) {
                    // Send parse error response
                    JsonObject error = new JsonObject();
                    error.addProperty("jsonrpc", "2.0");
                    error.add("id", null);
                    JsonObject errorObj = new JsonObject();
                    errorObj.addProperty("code", -32700);
                    errorObj.addProperty("message", "Parse error: " + e.getMessage());
                    error.add("error", errorObj);
                    writer.println(gson.toJson(error));
                    writer.flush();
                }
            }
        } catch (IOException e) {
            System.err.println("MCP server error: " + e.getMessage());
            return 1;
        }

        return 0;
    }

    private JsonObject handleRequest(JsonObject request, McpServerAdapter adapter) {
        String method = request.has("method") ? request.get("method").getAsString() : "";
        JsonElement idElement = request.has("id") ? request.get("id") : null;

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (idElement != null) {
            response.add("id", idElement);
        }

        switch (method) {
            case "initialize" -> {
                JsonObject result = new JsonObject();
                result.addProperty("protocolVersion", PROTOCOL_VERSION);
                JsonObject capabilities = new JsonObject();
                JsonObject tools = new JsonObject();
                capabilities.add("tools", tools);
                result.add("capabilities", capabilities);
                JsonObject serverInfo = new JsonObject();
                serverInfo.addProperty("name", "owl4agents");
                serverInfo.addProperty("version", "0.3.1");
                result.add("serverInfo", serverInfo);
                response.add("result", result);
            }
            case "notifications/initialized" -> {
                // No response needed for notifications
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

                // Convert JsonObject to Map
                Map<String, Object> argsMap = gson.fromJson(arguments, Map.class);

                Map<String, Object> result = adapter.handleToolCall(toolName, argsMap);

                // Convert result to MCP format
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
