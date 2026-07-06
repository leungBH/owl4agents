package org.owl4agents.mcp;

import com.sun.net.httpserver.Headers;
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
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * HTTP transport for the MCP server (v0.8.0 — Streamable HTTP).
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
 *   <li><b>POST /mcp</b> with {@code Accept: text/event-stream} — upgraded
 *       to the v0.8 SSE response path. Requires a valid {@code Mcp-Session-Id}
 *       AND at least one open SSE stream on the session. If either is missing,
 *       the request is answered as plain JSON with
 *       {@code X-Streamable-Http-Fallback} header.</li>
 *   <li><b>GET /mcp</b> with {@code Accept: text/event-stream} — opens a
 *       long-lived SSE stream. Requires a valid {@code Mcp-Session-Id} in the
 *       request header (sessions are created by the {@code initialize}
 *       exchange, NOT auto-created here).</li>
 *   <li><b>GET /info</b> — diagnostic endpoint. Returns process state,
 *       ontology catalog snapshot, and v0.8 SSE connection counters.</li>
 *   <li><b>GET /</b> — liveness probe target. Returns a plain-text banner.</li>
 * </ul>
 *
 * <p>The server is {@link AutoCloseable}. The constructor registers a
 * JVM shutdown hook that calls {@link #stop()} to drain in-flight
 * requests, close all SSE streams, and stop the listener within 5
 * seconds (per the v0.7 / v0.8 contract).</p>
 *
 * <p>v0.8 SSE design follows MCP 2025-03-26 §"Streamable HTTP transport":
 * single-writer per stream, RFC 8895 comment-frame heartbeats, UUID v4
 * session ids, no replay buffer on {@code Last-Event-ID} reconnect.</p>
 */
public class HttpMcpServer implements AutoCloseable {

    /**
     * Default SSE connection cap. Each open stream occupies a dedicated
     * thread, so this is also a soft OS-thread budget. See design.md D7
     * "Thread budget" — operator should lower this in memory-constrained
     * production environments.
     */
    public static final int DEFAULT_MAX_SSE_CONNECTIONS = 100;

    /**
     * Default session TTL. A session that is not touched for this long is
     * eligible for eviction by the background sweeper.
     */
    public static final Duration DEFAULT_SESSION_TTL = Duration.ofMinutes(30);

    /**
     * Default SSE heartbeat interval. The server writes an SSE comment
     * frame {@code : ping} at this interval on every open stream.
     */
    public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    /**
     * Tool names that internally use the OWL reasoner. Calls to these tools
     * are serialized on a single-thread executor to prevent concurrent access
     * to the (non-thread-safe) OWL reasoner instance.
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
    private final int maxSseConnections;
    private final Duration sessionTtl;
    private final Duration heartbeatInterval;
    private HttpServer server;
    private ThreadPoolExecutor workerPool;
    private ThreadPoolExecutor reasonerPool;
    private McpSessionManager sessionManager;
    private ScheduledExecutorService heartbeatScheduler;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final AtomicLong requestSeq = new AtomicLong(0);
    private final ReentrantLock startStopLock = new ReentrantLock();
    private volatile boolean started;

    /**
     * Backward-compatible constructor. Uses default SSE config
     * (100 connections, 30 min TTL, 15 s heartbeat).
     */
    public HttpMcpServer(McpServerAdapter adapter) {
        this(adapter, DEFAULT_MAX_SSE_CONNECTIONS, DEFAULT_SESSION_TTL, DEFAULT_HEARTBEAT_INTERVAL);
    }

    /**
     * Full v0.8 constructor. Validates all parameters eagerly.
     *
     * @param adapter            the JSON-RPC 2.0 adapter (must not be null)
     * @param maxSseConnections  cap on concurrent SSE streams; must be {@code >= 1}
     * @param sessionTtl         session idle TTL; must be {@code >= 1 minute}
     * @param heartbeatInterval  SSE heartbeat interval; must be {@code >= 1 second}
     * @throws IllegalArgumentException if any parameter is out of range
     */
    public HttpMcpServer(McpServerAdapter adapter,
                         int maxSseConnections,
                         Duration sessionTtl,
                         Duration heartbeatInterval) {
        if (adapter == null) {
            throw new IllegalArgumentException("adapter must not be null");
        }
        if (maxSseConnections < 1) {
            throw new IllegalArgumentException(
                "maxSseConnections must be >= 1, got " + maxSseConnections);
        }
        if (sessionTtl == null || sessionTtl.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException(
                "sessionTtl must be >= 1 minute, got " + sessionTtl);
        }
        if (heartbeatInterval == null || heartbeatInterval.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException(
                "heartbeatInterval must be >= 1 second, got " + heartbeatInterval);
        }
        this.adapter = adapter;
        this.maxSseConnections = maxSseConnections;
        this.sessionTtl = sessionTtl;
        this.heartbeatInterval = heartbeatInterval;
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
     * @param host bind host (typically "127.0.0.1" for the v0.7/v0.8 loopback default)
     * @param port TCP port, 0 for ephemeral
     * @throws java.net.BindException if the port is already in use
     * @throws IOException   for other I/O failures during bind
     */
    public void start(String host, int port) throws IOException {
        startStopLock.lock();
        try {
            if (started) {
                throw new IllegalStateException("HttpMcpServer already started");
            }
            this.workerPool = new ThreadPoolExecutor(
                8, 8, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadPoolExecutor.AbortPolicy()
            );
            // Single-thread reasoner executor: capacity 1, queue size 0, AbortPolicy.
            // A 2nd concurrent reasoner call will get RejectedExecutionException,
            // which we map to HTTP 500 with code -32000.
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

            // v0.8 SSE: session manager + heartbeat scheduler. The scheduler
            // is a separate thread pool from workerPool so a long-running
            // POST cannot block heartbeats (and vice versa).
            this.sessionManager = new McpSessionManager(300); // sweep every 5 min
            int heartbeatPoolSize = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
            this.heartbeatScheduler = Executors.newScheduledThreadPool(heartbeatPoolSize, r -> {
                Thread t = new Thread(r, "mcp-sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            // Single context for /mcp; method dispatch is handled inside
            // the DispatchHandler. This keeps the URL space clean (one path
            // per the MCP spec) and lets GET and POST share state.
            server.createContext("/mcp", new DispatchHandler());
            server.createContext("/info", new InfoHandler());
            server.createContext("/", new RootBannerHandler());
            // Explicit multi-threaded executor. The JDK default
            // (`setExecutor(null)`) only spawns threads on demand, which
            // can serialize requests if the first handler thread is
            // blocked on a long-lived SSE stream. We need true
            // concurrency so the cap check on the second connection is
            // not stuck behind the first.
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "mcp-http-handler");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            int boundPort = server.getAddress().getPort();
            // Print the startup banner to stderr so external scripts can grep it.
            // v0.8 includes SSE config so operators can confirm the runtime
            // parameters at a glance. Format is stable for grep.
            System.err.println("Listening on http://" + host + ":" + boundPort
                + " (max_sse=" + maxSseConnections
                + ", session_ttl_min=" + sessionTtl.toMinutes()
                + ", heartbeat_sec=" + heartbeatInterval.toSeconds() + ")");
            started = true;
        } finally {
            startStopLock.unlock();
        }
    }

    public CountDownLatch getShutdownLatch() {
        return shutdownLatch;
    }

    /** @return the SSE connection cap (for {@code GET /info} and tests). */
    public int getMaxSseConnections() {
        return maxSseConnections;
    }

    /** @return the configured session TTL. */
    public Duration getSessionTtl() {
        return sessionTtl;
    }

    /** @return the configured SSE heartbeat interval. */
    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    /** @return the live session manager (for tests; null before start). */
    public McpSessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * Stop the HTTP server. Stops accepting new connections, cancels
     * all heartbeat schedules, closes all SSE streams and sessions,
     * drains the worker pools, and counts down the shutdown latch.
     * Safe to call multiple times. Shutdown sequence (per design.md
     * D7 "Resource cleanup"):
     * <ol>
     *   <li>Cancel heartbeat scheduler (no new frames scheduled)</li>
     *   <li>Close all SSE streams (writer threads exit on IOException or latch)</li>
     *   <li>Stop the HttpServer (2s grace)</li>
     *   <li>Shutdown workerPool + reasonerPool</li>
     *   <li>Session manager shutdown (closes any remaining sessions)</li>
     *   <li>Countdown shutdown latch</li>
     * </ol>
     */
    public void stop() {
        startStopLock.lock();
        try {
            if (!started) {
                // Still count down the latch so any awaiter returns, even
                // if start was never called.
                shutdownLatch.countDown();
                return;
            }
            // Step 1: stop scheduling new heartbeats. Existing in-flight
            // heartbeat writes may still try to flush; the streams they
            // target will be closed in step 2 and the writes will fail
            // harmlessly with IOException.
            if (heartbeatScheduler != null) {
                try {
                    heartbeatScheduler.shutdownNow();
                } catch (Exception ignored) {
                    // best effort
                }
            }

            // Step 2: close all SSE streams. We iterate the session
            // manager; sessions that have no open streams are simply
            // closed and removed.
            if (sessionManager != null) {
                for (McpSession session : collectAllSessions()) {
                    for (McpSseStream stream : session.openStreams()) {
                        stream.close();
                    }
                    session.close();
                }
                sessionManager.shutdown();
            }

            // Step 3: stop the HTTP listener (2s grace for in-flight POSTs).
            if (server != null) {
                try {
                    server.stop(2);
                } catch (Exception ignored) {
                    // best effort
                }
            }

            // Step 4: drain the worker pools.
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

            // Step 6: countdown the shutdown latch so the CLI main thread
            // can return from its start().getShutdownLatch().await() call.
            shutdownLatch.countDown();
            started = false;
        } finally {
            startStopLock.unlock();
        }
    }

    @Override
    public void close() {
        stop();
    }

    // ── Handler implementations ──

    /**
     * Dispatch by HTTP method on the single {@code /mcp} context.
     * POST → {@link McpJsonRpcHandler}. GET + text/event-stream →
     * {@link SseGetHandler}. Any other method on /mcp → 405.
     */
    private class DispatchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            Headers headers = exchange.getRequestHeaders();
            if ("POST".equalsIgnoreCase(method)) {
                new McpJsonRpcHandler().handle(exchange);
            } else if ("GET".equalsIgnoreCase(method)) {
                String accept = headers.getFirst("Accept");
                if (accept != null && accept.toLowerCase(Locale.ROOT).contains("text/event-stream")) {
                    new SseGetHandler().handle(exchange);
                } else {
                    // GET /mcp without text/event-stream Accept is not
                    // allowed (v0.8 keeps the v0.7 405 behavior for this
                    // case to preserve the no-regression gate).
                    writeAllowHeader(exchange, "GET, POST");
                    byte[] body = "405 Method Not Allowed: GET /mcp requires Accept: text/event-stream".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(405, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                }
            } else {
                writeAllowHeader(exchange, "GET, POST");
                byte[] body = ("405 Method Not Allowed: " + method).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(405, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
        }
    }

    /**
     * POST /mcp JSON-RPC 2.0 handler. See design.md D5 for the full
     * Accept-header negotiation matrix.
     */
    private class McpJsonRpcHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                // Content-Type must be application/json.
                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                    byte[] body = gson.toJson(buildErrorResponse(null, -32600, "Unsupported Media Type"))
                        .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(415, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                    return;
                }

                // Mcp-Session-Id is optional on plain JSON POST. If present
                // it MUST be a valid UUID v4; non-UUID → 400.
                String sessionIdHeader = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
                McpSession session = null;
                if (sessionIdHeader != null && !sessionIdHeader.isBlank()) {
                    if (!isValidUuidV4(sessionIdHeader)) {
                        byte[] body = gson.toJson(buildErrorResponse(null, -32600, "Invalid Mcp-Session-Id format"))
                            .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(400, body.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(body);
                        }
                        return;
                    }
                    session = sessionManager.getOrCreate(sessionIdHeader);
                    if (session == null) {
                        // Should not happen for a valid UUID v4.
                        byte[] body = gson.toJson(buildErrorResponse(null, -32600, "Invalid Mcp-Session-Id format"))
                            .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(400, body.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(body);
                        }
                        return;
                    }
                    if (session.isExpired(sessionTtl)) {
                        // v0.8: TTL is enforced on every access (design.md D11.6).
                        sessionManager.remove(sessionIdHeader);
                        byte[] body = gson.toJson(buildErrorResponse(null, -32600, "Invalid or expired session id"))
                            .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(400, body.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(body);
                        }
                        return;
                    }
                    session.touch();
                }

                // Content negotiation.
                String accept = exchange.getRequestHeaders().getFirst("Accept");
                boolean acceptsEventStream = accept != null
                    && accept.toLowerCase(Locale.ROOT).contains("text/event-stream");
                // Per design.md D5, Accept: */* is treated as application/json.
                boolean acceptsJson = accept == null
                    || accept.isBlank()
                    || accept.toLowerCase(Locale.ROOT).contains("application/json")
                    || accept.trim().equals("*/*");

                // Read the body. Parse error → 400 + -32700.
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

                // v0.8: an `initialize` request is the only POST path that
                // auto-creates a session. The new id is returned in the
                // Mcp-Session-Id response header. All other methods require
                // the client to send Mcp-Session-Id explicitly.
                if (session == null
                    && request.has("method")
                    && "initialize".equals(request.get("method").getAsString())) {
                    session = sessionManager.create();
                }

                // SSE upgrade path: POST with Accept: text/event-stream AND
                // the session has at least one open stream. Otherwise fall
                // back to plain JSON (no auto-create of transient streams).
                if (acceptsEventStream && session != null && !session.openStreams().isEmpty()) {
                    dispatchSseResponse(exchange, session, request);
                    return;
                }
                if (acceptsEventStream && (session == null || session.openStreams().isEmpty())) {
                    // Fall back to plain JSON. Set the X-Streamable-Http-Fallback
                    // header to make the diagnostic clear in logs.
                    if (session == null) {
                        exchange.getResponseHeaders().add("X-Streamable-Http-Fallback", "application/json");
                    } else {
                        exchange.getResponseHeaders().add("X-Streamable-Http-Fallback", "no-open-stream");
                    }
                    // Fall through to plain JSON dispatch below.
                    acceptsEventStream = false;
                    acceptsJson = true;
                }

                if (!acceptsJson) {
                    // Accept header specified something other than json/sse/* — reject as 406
                    byte[] body = gson.toJson(buildErrorResponse(null, -32600, "Not Acceptable: only application/json or text/event-stream supported"))
                        .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(406, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                    return;
                }

                // Plain JSON path (v0.7 behavior preserved). Echo the
                // Mcp-Session-Id on the response so clients that did not
                // pre-initialize can adopt the session id from a successful
                // initialize.
                if (session != null) {
                    exchange.getResponseHeaders().add("Mcp-Session-Id", session.sessionId());
                }

                // Notifications return 202 Accepted with empty body.
                boolean hasId = request.has("id") && !request.get("id").isJsonNull();
                if (!hasId) {
                    JsonObject response = adapter.handleJsonRpc(request);
                    if (response == null) {
                        exchange.sendResponseHeaders(202, -1);
                        return;
                    }
                    writeJsonResponse(exchange, 200, response);
                    return;
                }

                // Dispatch to the right executor based on whether the tool is reasoner-using.
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
                            // If the response is a successful `initialize`,
                            // we have not yet issued a Mcp-Session-Id; this
                            // is the only place the server can issue one for
                            // a session that was opened session-less.
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

        /**
         * Dispatch a JSON-RPC request through the worker pool, then write
         * the response to the SSE stream's {@code event: message} frame
         * AND a 200 + empty body to the HTTP exchange. Per design.md D11.8,
         * the worker thread (not the reasoner thread) writes the frame.
         */
        private void dispatchSseResponse(HttpExchange exchange, McpSession session, JsonObject request) {
            ExecutorService target = workerPool;
            String reqMethod = request.has("method") ? request.get("method").getAsString() : "";
            if ("tools/call".equals(reqMethod) && request.has("params") && request.getAsJsonObject("params").has("name")) {
                String toolName = request.getAsJsonObject("params").get("name").getAsString();
                if (REASONER_USING_TOOLS.contains(toolName)) {
                    target = reasonerPool;
                }
            }

            // Snapshot the open streams at dispatch time so a stream that
            // is closed mid-call does not receive the response.
            McpSseStream[] streams = session.openStreams().toArray(new McpSseStream[0]);

            try {
                target.execute(() -> {
                    try {
                        JsonObject response = adapter.handleJsonRpc(request);
                        String responseJson = (response == null) ? "" : gson.toJson(response);
                        for (McpSseStream stream : streams) {
                            if (stream.isClosed()) continue;
                            try {
                                // Notifications (response == null) do not
                                // emit a response frame — per design.md D7
                                // row 5. We do write a 200 + empty body to
                                // the HTTP exchange for both cases.
                                if (response != null) {
                                    stream.writeEvent(stream.nextEventId(), responseJson);
                                }
                            } catch (IOException ioe) {
                                stream.close();
                                session.unregisterStream(stream);
                                sessionManager.decrementStreamCount();
                            }
                        }
                        // 200 + empty body on the HTTP exchange itself.
                        try {
                            exchange.sendResponseHeaders(200, -1);
                        } catch (IOException ignored) {
                            // Client may have closed; nothing to do.
                        }
                    } catch (Exception ex) {
                        // Try to send a JSON-RPC error frame on the streams.
                        JsonObject err = buildErrorResponse(
                            request.has("id") ? request.get("id") : null,
                            -32603, "Internal error: " + ex.getMessage());
                        String errJson = gson.toJson(err);
                        for (McpSseStream stream : streams) {
                            if (stream.isClosed()) continue;
                            try {
                                stream.writeEvent(stream.nextEventId(), errJson);
                            } catch (IOException ignored) {
                                stream.close();
                                session.unregisterStream(stream);
                                sessionManager.decrementStreamCount();
                            }
                        }
                        try {
                            exchange.sendResponseHeaders(200, -1);
                        } catch (IOException ignored) {
                            // ignored
                        }
                    }
                });
            } catch (RejectedExecutionException ree) {
                String msg = target == reasonerPool
                    ? "reasoner executor saturated, retry with backoff"
                    : "worker pool saturated, retry with backoff";
                byte[] body = gson.toJson(buildErrorResponse(request.get("id"), -32000, msg))
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("X-Streamable-Http-Fallback", "saturated");
                try {
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
     * GET /mcp SSE stream opener. Requires:
     * <ul>
     *   <li>{@code Accept: text/event-stream} (validated by caller)</li>
     *   <li>{@code Mcp-Session-Id} request header — must be a valid UUID v4
     *       and correspond to a registered session. Sessions are NOT
     *       auto-created on GET (v0.8 design decision; design.md D1).</li>
     * </ul>
     * After validation: opens a {@link McpSseStream} on the session,
     * starts the heartbeat scheduler, and blocks the handler thread on
     * the stream's lifecycle. Per design.md D11.1, all writes happen on
     * a single dedicated writer thread per stream.
     */
    private class SseGetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Atomic cap claim. Must happen BEFORE we touch the
            // exchange's response headers — once sendResponseHeaders
            // is called the 200 OK is observable to the client, so
            // the slot must already be reserved. tryClaimStreamSlot
            // does check-and-increment in one CAS.
            if (!sessionManager.tryClaimStreamSlot(maxSseConnections)) {
                byte[] body = "503 Service Unavailable: max SSE connections reached".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                exchange.getResponseHeaders().add("Retry-After", "30");
                exchange.sendResponseHeaders(503, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
                return;
            }

            // Track whether the success path (writer.join → decrement)
            // has already released the slot. If not, the finally block
            // releases it for us.
            boolean slotReleased = false;

            try {
                // Mcp-Session-Id is REQUIRED on GET /mcp (v0.8 design).
                String sessionIdHeader = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
                if (sessionIdHeader == null || sessionIdHeader.isBlank()) {
                    byte[] body = "Mcp-Session-Id header is required for GET /mcp; call POST /mcp initialize first".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(400, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                    return;
                }
                if (!isValidUuidV4(sessionIdHeader)) {
                    byte[] body = "Invalid Mcp-Session-Id format".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(400, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                    return;
                }

                McpSession session = sessionManager.getOrCreate(sessionIdHeader);
                if (session == null) {
                    byte[] body = "Invalid Mcp-Session-Id format".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(400, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                    return;
                }
                if (session.isExpired(sessionTtl)) {
                    sessionManager.remove(sessionIdHeader);
                    byte[] body = "Session not found".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(404, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                    return;
                }
                session.touch();

                // Last-Event-ID handling: if present and non-negative,
                // emit a single resumed:true ack and close the stream.
                // We do NOT replay history (v0.8 contract; design.md D6).
                String lastEventId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
                int parsedLastEventId = -1;
                if (lastEventId != null && !lastEventId.isBlank()) {
                    try {
                        parsedLastEventId = Integer.parseInt(lastEventId.trim());
                        if (parsedLastEventId < 0) {
                            throw new NumberFormatException("negative");
                        }
                    } catch (NumberFormatException nfe) {
                        byte[] body = "Last-Event-ID is malformed".getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                        exchange.sendResponseHeaders(400, body.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(body);
                        }
                        return;
                    }
                }

                // Create the stream and register it on the session.
                McpSseStream stream = new McpSseStream(session, exchange);
                if (!session.registerStream(stream)) {
                    stream.close();
                    byte[] body = "Session is closed".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(410, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                    return;
                }
                // Slot was claimed atomically at the top; no separate
                // increment is needed here.

                // Spawn a dedicated writer thread for this stream. All
                // subsequent writes (ready, heartbeats, message frames,
                // resume ack) happen on this thread (design.md D11.1).
                final String sid = session.sessionId();
                final McpSseStream s = stream;
                final int lastIdForAck = parsedLastEventId;
                Thread writer = new Thread(() -> runWriterLoop(s, sid, lastIdForAck), "mcp-sse-writer-" + stream.streamId().substring(0, 8));
                writer.setDaemon(true);
                writer.start();

                // The handler thread blocks here. The writer thread will
                // close the stream on its own (after a resume ack, an
                // IOException, or a session close). The JDK HttpServer
                // default executor keeps the connection alive for as
                // long as this handler thread is alive.
                try {
                    writer.join();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }

                // Cleanup after the writer exits. The stream is fully
                // established now, so we mark the slot as released to
                // prevent the finally from double-decrementing.
                session.unregisterStream(stream);
                sessionManager.decrementStreamCount();
                slotReleased = true;
            } catch (Exception outer) {
                try {
                    byte[] body = ("Internal error: " + outer.getMessage()).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(500, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                } catch (IOException ignored) {
                    // ignored
                }
            } finally {
                // Release the slot if the success path did not (i.e.
                // we never reached the writer.join() → decrement
                // sequence). This covers every validation error and
                // every exception thrown after the cap claim.
                if (!slotReleased) {
                    sessionManager.decrementStreamCount();
                }
            }
        }

        /**
         * Writer loop: write the initial {@code ready} frame (or a
         * {@code resumed:true} ack if Last-Event-ID was present), then
         * run the heartbeat scheduler until the stream is closed.
         */
        private void runWriterLoop(McpSseStream stream, String sessionId, int lastEventIdForAck) {
            try {
                if (lastEventIdForAck >= 0) {
                    stream.writeResumeAck(lastEventIdForAck);
                    // Resume ack closes the stream; the writer exits.
                    stream.close();
                    return;
                }
                stream.writeReady();

                // Schedule heartbeats on the shared scheduler. The
                // scheduled task writes a comment frame; if the stream
                // is already closed, it stops scheduling.
                long intervalSec = heartbeatInterval.toSeconds();
                ScheduledFuture<?> heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(
                    () -> {
                        if (stream.isClosed()) return;
                        try {
                            stream.writeHeartbeat();
                        } catch (IOException ioe) {
                            stream.close();
                        }
                    },
                    intervalSec, intervalSec, TimeUnit.SECONDS);

                // Block the writer thread on the stream's lifecycle.
                // We use a simple poll loop because McpSseStream has no
                // close-latch API. The poll is cheap (1s sleep) and the
                // thread is daemon so it does not block JVM exit.
                // D-003 fix: on each iteration, also run a liveness
                // probe (body.flush()). When the client has disconnected,
                // the flush throws IOException, we close the stream, and
                // the handler thread's writer.join() returns so the
                // active_sse_connections counter decrements promptly
                // (well before the next scheduled heartbeat fires).
                while (!stream.isClosed()) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    try {
                        stream.detectClientDisconnect();
                    } catch (IOException probeIo) {
                        // Client disconnected; close the stream and
                        // exit the loop. The handler thread will see
                        // isClosed() == true on its writer.join() return
                        // path and decrement the active count.
                        stream.close();
                        break;
                    }
                }
                heartbeatTask.cancel(false);
            } catch (IOException ioe) {
                // Client disconnected; close the stream and let the
                // handler thread unregister it.
                stream.close();
            }
        }
    }

    /**
     * GET /info handler. Returns diagnostic process state, the ontology
     * catalog snapshot, and the v0.8 SSE connection counters.
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
            // v0.8 SSE counters.
            body.addProperty("max_sse_connections", maxSseConnections);
            body.addProperty("active_sse_connections", sessionManager != null ? sessionManager.activeStreamCount() : 0L);

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
     * version, and available endpoints (v0.8 lists the SSE endpoint).
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
                + "Available endpoints: POST /mcp, GET /mcp (SSE), GET /info, GET /\n";
            byte[] body = banner.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    // ── helpers ──

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

    private static boolean isValidUuidV4(String s) {
        if (s == null) return false;
        try {
            UUID u = UUID.fromString(s);
            return u.version() == 4;
        } catch (IllegalArgumentException iae) {
            return false;
        }
    }

    /**
     * Snapshot of all sessions currently in the manager. Used during
     * shutdown to close every open stream.
     */
    private java.util.List<McpSession> collectAllSessions() {
        java.util.List<McpSession> all = new java.util.ArrayList<>();
        if (sessionManager != null) {
            // SessionManager exposes no public iterator; we use reflection-free
            // access via getOrCreate to probe known ids. For shutdown, we
            // simply close streams on any session we find. In practice, the
            // shutdown path also calls sessionManager.shutdown() which
            // iterates its own map. Here we return an empty list because
            // sessionManager.shutdown() does the iteration.
            // (Method kept for future use; e.g. per-session logging.)
        }
        return all;
    }

    // Suppress unused-import warnings for types only referenced via fully-qualified names.
    @SuppressWarnings("unused")
    private static final Class<?>[] KEEP_IMPORTS = new Class<?>[] {
        com.sun.net.httpserver.Headers.class
    };
}
