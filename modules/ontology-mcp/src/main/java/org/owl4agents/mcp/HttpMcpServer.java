package org.owl4agents.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP transport for the MCP server (v0.7.0).
 *
 * <p>Exposes a {@code com.sun.net.httpserver.HttpServer} on a configurable
 * host:port. Endpoints:</p>
 *
 * <ul>
 *   <li><b>POST /mcp</b> — JSON-RPC 2.0 entrypoint. Routes to
 *       {@link McpServerAdapter#handleJsonRpc}. Non-reasoner tool calls
 *       are dispatched to a fixed 8-worker pool with a 100-task bounded
 *       queue ({@code max in-flight = 108}). Reasoner-using tool calls
 *       are dispatched to a single-thread executor (capacity 1, no queue).
 *       Pool saturation returns HTTP 500 with JSON-RPC error code
 *       {@code -32000}.</li>
 *   <li><b>GET /info</b> — diagnostic endpoint. Returns process state
 *       and an ontology catalog snapshot (sourced from
 *       {@code ontology_list}).</li>
 *   <li><b>GET /</b> — liveness probe target. Returns a plain-text
 *       banner.</li>
 * </ul>
 *
 * <p>The server is {@link AutoCloseable}. The constructor registers a
 * JVM shutdown hook that calls {@link #stop()} to drain in-flight
 * requests and stop the listener within 5 seconds (spec.md
 * §"HTTP server lifecycle").</p>
 */
public class HttpMcpServer implements AutoCloseable {

    /**
     * Tool names that internally use the OWL reasoner. Calls to these tools
     * are serialized on a single-thread executor to prevent concurrent access
     * to the (non-thread-safe) OWL reasoner instance.
     *
     * <p>This set is sourced from spec.md §"Reasoner-using tool calls are
     * serialized". It MUST match the readonly tool registry's classification
     * of reasoner-using tools (see {@link McpToolRegistry}).</p>
     */
    public static final Set<String> REASONER_USING_TOOLS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "ontology_run_reasoner",
        "ontology_classify",
        "ontology_realize",
        "ontology_check_consistency",
        "ontology_explain_inconsistency",
        "ontology_explain_unsat_class",
        "ontology_get_unsat_classes",
        "ontology_get_reasoning_report",
        "ontology_get_inferred_facts",
        "ontology_check_entailment",
        "ontology_check_class_compatibility",
        "ontology_check_individual_membership",
        "ontology_check_relation_assertion"
    )));

    private final McpServerAdapter adapter;
    private final Gson gson = new Gson();
    private HttpServer server;
    private ThreadPoolExecutor workerPool;
    private ThreadPoolExecutor reasonerPool;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final AtomicLong requestSeq = new AtomicLong(0);

    public HttpMcpServer(McpServerAdapter adapter) {
        this.adapter = adapter;
        // Register a JVM shutdown hook so SIGTERM/SIGINT stops the listener
        // within 5 seconds (spec.md §"Server stops on JVM signal"). The hook
        // calls stop() which countdowns the shutdown latch that the CLI thread
        // is awaiting, so the main thread returns from the start() call cleanly.
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "http-mcp-shutdown-hook"));
    }

    /**
     * Start the HTTP server on the given host:port. If {@code port == 0},
     * an ephemeral port is assigned by the OS and printed to stderr.
     *
     * @param host bind host (typically "127.0.0.1" for v0.7.0 loopback default)
     * @param port TCP port, 0 for ephemeral
     * @throws java.net.BindException if the port is already in use
     * @throws IOException   for other I/O failures during bind
     */
    public void start(String host, int port) throws IOException {
        this.workerPool = new ThreadPoolExecutor(
            8, 8, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.AbortPolicy()
        );
        // Single-thread reasoner executor: capacity 1, queue size 0, AbortPolicy.
        // A 2nd concurrent reasoner call will get RejectedExecutionException,
        // which we map to HTTP 500 with code -32000.
        // SynchronousQueue (capacity 0) + AbortPolicy = strict serial execution
        // without any task buffering: any caller arriving while a reasoner
        // task is in-flight is rejected, not queued.
        this.reasonerPool = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "mcp-reasoner-worker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );

        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/mcp", new McpJsonRpcHandler());
        server.createContext("/info", new InfoHandler());
        server.createContext("/", new RootBannerHandler());
        server.setExecutor(null); // default per-connection executor; we fan out to workerPool in the handler
        server.start();
        int boundPort = server.getAddress().getPort();
        // Print the startup banner to stderr so external scripts can grep it.
        // Format is stable: `Listening on http://{host}:{port}`. spec.md §"Startup banner on stderr".
        System.err.println("Listening on http://" + host + ":" + boundPort);
    }

    public CountDownLatch getShutdownLatch() {
        return shutdownLatch;
    }

    /**
     * Stop the HTTP server. Stops accepting new connections, drains the
     * worker pools, and counts down the shutdown latch so the main
     * thread can return from its {@code start().getShutdownLatch().await()}
     * call. Safe to call multiple times.
     */
    public void stop() {
        try {
            if (server != null) {
                server.stop(2); // 2-second drain for in-flight connections
            }
        } catch (Exception ignored) {
            // Best effort
        }
        if (workerPool != null) {
            workerPool.shutdown();
            try {
                if (!workerPool.awaitTermination(2, TimeUnit.SECONDS)) {
                    workerPool.shutdownNow();
                }
            } catch (InterruptedException ie) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (reasonerPool != null) {
            reasonerPool.shutdown();
            try {
                if (!reasonerPool.awaitTermination(2, TimeUnit.SECONDS)) {
                    reasonerPool.shutdownNow();
                }
            } catch (InterruptedException ie) {
                reasonerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        shutdownLatch.countDown();
    }

    @Override
    public void close() {
        stop();
    }

    // ── Handlers ──

    /**
     * POST /mcp JSON-RPC 2.0 handler. See class-level Javadoc for the full
     * error matrix.
     */
    private class McpJsonRpcHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod();
                if (!"POST".equalsIgnoreCase(method)) {
                    // 405 Method Not Allowed. spec.md §"Non-POST method on /mcp"
                    writeAllowHeader(exchange, "POST");
                    byte[] body = gson.toJson(buildErrorResponse(null, -32600, "Method Not Allowed: " + method))
                        .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(405, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                    return;
                }

                // Content-Type must be application/json. spec.md §"Unsupported Content-Type".
                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
                    byte[] body = gson.toJson(buildErrorResponse(null, -32600, "Unsupported Media Type"))
                        .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(415, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                    return;
                }

                // Reject SSE Accept header. spec.md §"SSE Accept header rejected".
                String accept = exchange.getRequestHeaders().getFirst("Accept");
                if (accept != null && accept.toLowerCase().contains("text/event-stream")) {
                    byte[] body = gson.toJson(buildErrorResponse(null, -32000, "SSE streaming not implemented"))
                        .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(501, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                    return;
                }

                // Read the body. spec.md §"Malformed JSON body" — parse error → 400 + -32700.
                String requestBody;
                try (InputStream is = exchange.getRequestBody()) {
                    requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }

                JsonObject request;
                try {
                    request = JsonParser.parseString(requestBody).getAsJsonObject();
                } catch (JsonSyntaxException | IllegalStateException pe) {
                    byte[] body = gson.toJson(buildErrorResponse(null, -32700, "Parse error: " + pe.getMessage()))
                        .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(400, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                    return;
                }

                // Notifications return 202 Accepted with empty body. spec.md §"Notification produces 202 Accepted".
                boolean hasId = request.has("id") && !request.get("id").isJsonNull();
                if (!hasId) {
                    JsonObject response = adapter.handleJsonRpc(request);
                    if (response == null) {
                        exchange.sendResponseHeaders(202, -1); // -1 = no body
                        return;
                    }
                    writeJsonResponse(exchange, 200, response);
                    return;
                }

                // Dispatch to the right executor based on whether the tool is reasoner-using.
                // spec.md §"Reasoner-using tool routing decision lives in HttpMcpServer".
                ExecutorService target = workerPool;
                String reqMethod = request.has("method") ? request.get("method").getAsString() : "";
                if ("tools/call".equals(reqMethod) && request.has("params") && request.getAsJsonObject("params").has("name")) {
                    String toolName = request.getAsJsonObject("params").get("name").getAsString();
                    if (REASONER_USING_TOOLS.contains(toolName)) {
                        target = reasonerPool;
                    }
                }

                final JsonObject finalRequest = request;
                requestSeq.incrementAndGet();

                try {
                    target.execute(() -> {
                        try {
                            JsonObject response = adapter.handleJsonRpc(finalRequest);
                            if (response == null) {
                                // Defensive — only notifications have no `id`
                                exchange.sendResponseHeaders(202, -1);
                                return;
                            }
                            int status = 200;
                            if (response.has("error")) {
                                int code = response.getAsJsonObject("error").get("code").getAsInt();
                                if (code == -32000) status = 500;
                                if (code == -32603) status = 500;
                            }
                            writeJsonResponse(exchange, status, response);
                        } catch (Exception ex) {
                            try {
                                byte[] body = gson.toJson(buildErrorResponse(null, -32603, "Internal error: " + ex.getMessage()))
                                    .getBytes(StandardCharsets.UTF_8);
                                exchange.getResponseHeaders().add("Content-Type", "application/json");
                                exchange.sendResponseHeaders(500, body.length);
                                try (OutputStream os = exchange.getResponseBody()) {
                                    os.write(body);
                                }
                            } catch (IOException ignored) {
                                // Connection already closed
                            }
                        }
                    });
                } catch (RejectedExecutionException ree) {
                    // spec.md §"Bounded worker pool with explicit saturation semantics"
                    String msg = target == reasonerPool
                        ? "reasoner executor saturated, retry with backoff"
                        : "worker pool saturated, retry with backoff";
                    byte[] body = gson.toJson(buildErrorResponse(request.get("id"), -32000, msg))
                        .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                }
            } catch (Exception outer) {
                // Last-resort safety net
                try {
                    byte[] body = gson.toJson(buildErrorResponse(null, -32603, "Internal error: " + outer.getMessage()))
                        .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                } catch (IOException ignored) {
                    // ignored
                }
            }
        }
    }

    /**
     * GET /info handler. Returns diagnostic process state and the
     * ontology catalog snapshot. Degraded if ontology_list fails.
     */
    private class InfoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeAllowHeader(exchange, "GET");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            JsonObject body = new JsonObject();
            body.addProperty("status", "ok");
            body.addProperty("version", McpServerAdapter.SERVER_VERSION);
            body.addProperty("transport", "http");

            JsonArray ontologiesArray = new JsonArray();
            try {
                Map<String, Object> argsMap = new HashMap<>();
                Map<String, Object> result = adapter.handleToolCall("ontology_list", argsMap);
                if (result.containsKey("data")) {
                    Object dataObj = result.get("data");
                    if (dataObj instanceof Map) {
                        Object ontologies = ((Map<?, ?>) dataObj).get("ontologies");
                        if (ontologies instanceof List) {
                            for (Object o : (List<?>) ontologies) {
                                ontologiesArray.add(gson.toJsonTree(o));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                body.addProperty("status", "degraded");
                body.addProperty("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            body.add("ontologies", ontologiesArray);
            writeJsonResponse(exchange, 200, body);
        }
    }

    /**
     * GET / liveness banner. Returns plain text with the server name,
     * version, and available endpoints.
     */
    private class RootBannerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (!"/".equals(path)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String banner = "owl4agents MCP HTTP server v" + McpServerAdapter.SERVER_VERSION + "\n"
                + "Available endpoints: POST /mcp, GET /info, GET /\n";
            byte[] body = banner.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private void writeJsonResponse(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void writeAllowHeader(HttpExchange exchange, String allow) {
        exchange.getResponseHeaders().add("Allow", allow);
    }

    private JsonObject buildErrorResponse(JsonElement idElement, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (idElement != null && !idElement.isJsonNull()) {
            response.add("id", idElement);
        } else {
            response.add("id", null);
        }
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        return response;
    }
}
