package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.owl4agents.mcp.HttpMcpServer;
import org.owl4agents.mcp.McpServerAdapter;
import org.owl4agents.storage.HomeDirectoryResolver;

import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * CLI command entrypoint for starting the MCP server.
 * Dispatches to either the stdio transport (default) or the HTTP transport
 * (--transport=http) per spec.md §"Transport selection".
 *
 * <p>stdio path: reads JSON-RPC 2.0 lines from stdin, writes responses to
 * stdout. JSON-RPC routing is delegated to {@link McpServerAdapter#handleJsonRpc}.</p>
 *
 * <p>http path: delegates to {@code HttpMcpServer.start(host, port, adapter)}
 * and blocks until SIGTERM/SIGINT. The HTTP server registers a JVM shutdown
 * hook (HttpMcpServer constructor) to call {@code stop()} cleanly.</p>
 */
@Command(name = "mcp", description = "Start the MCP server (readonly by default; use --readonly=false to enable write tools).")
public class McpCommand implements Callable<Integer> {

    @Option(names = {"--readonly"}, description = "Start MCP in readonly mode (default)", arity = "0..1", fallbackValue = "true", defaultValue = "true")
    private boolean readonly = true;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Option(names = {"--home"}, description = "owl4agents home directory override")
    private String homeDirectory;

    @Option(names = {"--transport"}, description = "Transport to use: stdio (default) or http", defaultValue = "stdio")
    private String transport = "stdio";

    @Option(names = {"--host"}, description = "HTTP host (only used with --transport=http)", defaultValue = "127.0.0.1")
    private String host = "127.0.0.1";

    @Option(names = {"--port"}, description = "HTTP port (only used with --transport=http); use 0 for ephemeral", defaultValue = "8080")
    private int port = 8080;

    @Option(names = {"--max-sse-connections"},
        description = "Cap on concurrent SSE streams (only used with --transport=http); must be >= 1. Each stream holds a thread, so this is also a soft OS-thread budget. Default: ${DEFAULT-VALUE}",
        defaultValue = "100")
    private int maxSseConnections = HttpMcpServer.DEFAULT_MAX_SSE_CONNECTIONS;

    @Option(names = {"--session-ttl-minutes"},
        description = "Session idle TTL in minutes (only used with --transport=http); must be >= 1. Default: ${DEFAULT-VALUE}",
        defaultValue = "30")
    private long sessionTtlMinutes = HttpMcpServer.DEFAULT_SESSION_TTL.toMinutes();

    @Option(names = {"--sse-heartbeat-seconds"},
        description = "SSE heartbeat interval in seconds (only used with --transport=http); must be >= 1. Default: ${DEFAULT-VALUE}",
        defaultValue = "15")
    private long sseHeartbeatSeconds = HttpMcpServer.DEFAULT_HEARTBEAT_INTERVAL.toSeconds();

    // v0.8.7 mcp-write-tools: write-mode configuration options.
    // --max-import-size-mb caps the per-import payload size (default 50 MB).
    // --allowed-import-roots restricts which server-local directories the
    // ontology_import tool can read from (defaults to the workspace imports/
    // subdirectory). Both are only consulted when --readonly=false.
    @Option(names = {"--max-import-size-mb"},
        description = "Maximum size in MB for a single ontology_import payload (only used with --readonly=false). Default: ${DEFAULT-VALUE}",
        defaultValue = "50")
    private int maxImportSizeMb = 50;

    @Option(names = {"--allowed-import-roots"},
        description = "Comma-separated list of allowed root directories for ontology_import file_path argument (only used with --readonly=false). Defaults to the workspace imports/ subdirectory.")
    private String allowedImportRoots;

    private final Gson gson = GsonFactory.createGson();

    @Override
    public Integer call() {
        // v0.8.7 mcp-write-tools D3: --readonly=false is now an opt-in write mode.
        // Previously the CLI hard-rejected readonly=false. Now we forward the
        // flag to McpServerAdapter so the adapter can conditionally register
        // write tools (currently ontology_import). The hard-rejection code path
        // is removed per tasks.md §2.1.
        //
        // Write mode startup warning is emitted here (stderr only) so it does
        // not pollute the JSON-RPC stream on stdout. Per spec.md "Write mode
        // startup warning" the warning must mention: (1) write mode enabled,
        // (2) readonly guarantee no longer in effect, (3) ontology_import can
        // write to the workspace, (4) configured allowed import roots,
        // (5) per-import size limit.
        if (!readonly) {
            String roots = (allowedImportRoots != null && !allowedImportRoots.isBlank())
                ? allowedImportRoots
                : "workspace imports/ subdirectory (default)";
            System.err.println("WARNING: WRITE mode is enabled (--readonly=false).");
            System.err.println("WARNING: The readonly guarantee is no longer in effect for the duration of this server process.");
            System.err.println("WARNING: The ontology_import tool can write to the workspace.");
            System.err.println("WARNING: Allowed import roots: " + roots);
            System.err.println("WARNING: Size limit per import: " + maxImportSizeMb + " MB");
        }

        // Validate transport value early. Reject unknown values with a deterministic
        // error and a non-zero exit code (picocli will also validate, but we want
        // a stable message independent of picocli's wording).
        if (!"stdio".equals(transport) && !"http".equals(transport)) {
            System.err.println("Error: --transport must be 'stdio' or 'http', got '" + transport + "'");
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

        // v0.8.7 mcp-write-tools D3: forward readonly flag + write-mode
        // configuration into the adapter so it can conditionally register
        // write tools (ontology_import) and enforce size/path limits.
        McpServerAdapter adapter = new McpServerAdapter(
            serviceContext, logFilePath, readonly, maxImportSizeMb, allowedImportRoots);

        if ("http".equals(transport)) {
            return runHttp(adapter);
        }
        return runStdio(adapter);
    }

    /**
     * Run the stdio transport loop. Reads JSON-RPC 2.0 lines from stdin and
     * writes responses to stdout. Delegates routing to
     * {@link McpServerAdapter#handleJsonRpc}.
     */
    private int runStdio(McpServerAdapter adapter) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        PrintWriter writer = new PrintWriter(System.out, true);

        // Signal that server is ready (write to stderr so it doesn't interfere with protocol).
        // v0.8.7: message reflects the actual mode (readonly vs write) per tasks.md §2.8.
        System.err.println("owl4agents MCP server started in "
            + (readonly ? "readonly" : "WRITE")
            + " mode (transport=stdio)");

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                    JsonObject response = adapter.handleJsonRpc(request);
                    // Notifications return null — do not write anything to stdout
                    if (response != null) {
                        writer.println(gson.toJson(response));
                        writer.flush();
                    }
                } catch (Exception e) {
                    // Send parse error response (-32700). spec.md §"Parse error"
                    // requires the error response to be sent on a parse failure.
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

    /**
     * Run the HTTP transport. Delegates to {@code HttpMcpServer.start(host, port, adapter)}
     * and blocks until the server is stopped (e.g. via SIGTERM, which triggers
     * the JVM shutdown hook registered in HttpMcpServer's constructor).
     */
    private int runHttp(McpServerAdapter adapter) {
        // D-001 fix: wire the three v0.8 CLI options into the HttpMcpServer
        // constructor. Previously the CLI parsed these flags but discarded
        // them, so the server always started with the defaults
        // (max_sse=100, session_ttl_min=5 (v0.8.6), heartbeat_sec=15)
        // regardless of what the user passed on the command line.
        HttpMcpServer server;
        try {
            server = new HttpMcpServer(
                adapter,
                maxSseConnections,
                Duration.ofMinutes(sessionTtlMinutes),
                Duration.ofSeconds(sseHeartbeatSeconds));
        } catch (IllegalArgumentException iae) {
            // Constructor validation failure (e.g. max-sse-connections < 1,
            // session-ttl-minutes < 1, heartbeat-seconds < 1). Emit a
            // single-line diagnostic and exit with EX_USAGE (64) so the
            // operator sees a clear message, not a Java stack trace.
            System.err.println("MCP HTTP configuration error: " + iae.getMessage());
            return 64;
        }
        try {
            server.start(host, port);
            // Block the main thread. Shutdown is driven by the JVM shutdown hook
            // that HttpMcpServer constructor registers (Runtime.getRuntime().addShutdownHook).
            server.getShutdownLatch().await();
            return 0;
        } catch (java.net.BindException be) {
            // spec.md §"Port already in use" — exit code 78 (sysexits.h EX_CONFIG)
            System.err.println("port " + host + ":" + port + " already in use");
            System.exit(78);
            return 78; // unreachable, but keeps the compiler happy
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return 130; // 128 + SIGINT(2)
        } catch (IOException ioe) {
            System.err.println("MCP HTTP server error: " + ioe.getMessage());
            return 1;
        }
    }
}
