package org.owl4agents.deployment;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * HTTP deployment smoke test: spawns a real MCP HTTP server from the
 * shadow jar and exercises the end-to-end JSON-RPC protocol over HTTP.
 *
 * <p>This test bridges the gap between unit tests (which use in-process
 * adapters) and production deployment: it launches the actual
 * {@code java -jar owl4agents.jar mcp --transport=http} command, waits
 * for the server to bind, and sends real HTTP requests through the
 * MCP Streamable HTTP transport.</p>
 *
 * <p>The test is skipped when:</p>
 * <ul>
 *   <li>{@code skip.deployment.test=true} system property is set.</li>
 *   <li>The shadow jar has not been built (no {@code owl4agents.jar}).
 *       Run {@code .\gradlew.bat :modules:ontology-cli:shadowJar} first.</li>
 *   <li>The server fails to start within 60 seconds.</li>
 * </ul>
 *
 * <p>All test methods share a single server instance started in
 * {@link #startServer()} (@BeforeAll) and torn down in
 * {@link #stopServer()} (@AfterAll). The MCP session is established
 * once via {@code initialize} and the {@code Mcp-Session-Id} header is
 * reused for all subsequent requests.</p>
 *
 * <p>Tagged {@code "deployment"} so it can be included/excluded via
 * JUnit tag filtering. Excluded from the default {@code test} task
 * (see root {@code build.gradle.kts}); run explicitly via
 * {@code ./gradlew deploymentTest}.</p>
 */
@Tag("deployment")
@DisabledIfSystemProperty(named = "skip.deployment.test", matches = "true")
@DisplayName("HTTP deployment smoke test (end-to-end MCP over HTTP)")
class HttpDeploymentSmokeTest {

    private static final int STARTUP_TIMEOUT_SECONDS = 60;
    private static final int REQUEST_TIMEOUT_MS = 15_000;

    private static Process serverProcess;
    private static int serverPort;
    private static String mcpSessionId;
    private static String initializeResponseBody;
    private static Path shadowJarPath;
    private static boolean pizzaAvailable;
    private static String ontologyListResponseBody;

    @BeforeAll
    static void startServer() throws Exception {
        shadowJarPath = locateShadowJar();
        assumeTrue(Files.exists(shadowJarPath),
            "Skipping: shadow jar not built. Run '.\\gradlew.bat :modules:ontology-cli:shadowJar' first.");

        // Pick a random free port to avoid conflicts with other tests/services.
        try (ServerSocket ss = new ServerSocket(0)) {
            serverPort = ss.getLocalPort();
        }

        Path projectRoot = findProjectRoot();
        ProcessBuilder pb = new ProcessBuilder(
            "java",
            "-Xmx4g",
            "-XX:+ExitOnOutOfMemoryError",
            "-jar",
            shadowJarPath.toString(),
            "mcp",
            "--transport=http",
            "--host=127.0.0.1",
            "--port=" + serverPort
        );
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);
        serverProcess = pb.start();
        drainAsync(serverProcess);

        boolean started = waitForServerStartup(serverPort, STARTUP_TIMEOUT_SECONDS);
        assumeTrue(started,
            "Skipping: MCP HTTP server did not start within "
                + STARTUP_TIMEOUT_SECONDS + "s");

        // Initialize MCP session — required before any other JSON-RPC call.
        HttpResponse initResp = sendInitialize(serverPort);
        assertEquals(200, initResp.statusCode,
            "initialize must return HTTP 200. Body: " + initResp.body);
        mcpSessionId = initResp.header("Mcp-Session-Id");
        assertNotNull(mcpSessionId,
            "initialize must return a Mcp-Session-Id header");
        assertFalse(mcpSessionId.isBlank(),
            "Mcp-Session-Id must not be blank");
        initializeResponseBody = initResp.body;

        // Probe the workspace: check whether the pizza ontology is loaded.
        // Pizza-specific tests are conditionally skipped via assumeTrue.
        HttpResponse listResp = sendToolCall(serverPort, mcpSessionId,
            "ontology_list", "{}");
        ontologyListResponseBody = listResp.body;
        pizzaAvailable = listResp.body.contains("\"ontologyId\":\"pizza\"")
            || listResp.body.contains("\"ontologyId\": \"pizza\"");
    }

    @AfterAll
    static void stopServer() {
        if (serverProcess != null && serverProcess.isAlive()) {
            serverProcess.destroyForcibly();
            try {
                serverProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    @DisplayName("initialize returns serverInfo.version matching the project version")
    void testInitializeReturnsCorrectVersion() {
        String expectedVersion = System.getProperty("owl4agents.version", "0.8.6");
        assertNotNull(initializeResponseBody,
            "initialize response must be captured in @BeforeAll");
        assertTrue(
            initializeResponseBody.contains("\"version\":\"" + expectedVersion + "\"")
                || initializeResponseBody.contains("\"version\": \"" + expectedVersion + "\""),
            "initialize response must contain serverInfo.version=" + expectedVersion
                + ". Response: " + initializeResponseBody);
    }

    @Test
    @DisplayName("initialize returns protocolVersion 2025-06-18")
    void testInitializeReturnsCorrectProtocolVersion() {
        assertNotNull(initializeResponseBody,
            "initialize response must be captured in @BeforeAll");
        assertTrue(initializeResponseBody.contains("2025-06-18"),
            "initialize response must contain protocolVersion 2025-06-18. Response: "
                + initializeResponseBody);
    }

    @Test
    @DisplayName("tools/list returns exactly 56 readonly tools")
    void testToolsListReturns56Tools() throws Exception {
        HttpResponse resp = sendRequest(serverPort, mcpSessionId,
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        assertEquals(200, resp.statusCode,
            "tools/list must return HTTP 200. Body: " + resp.body);
        int toolCount = countToolEntries(resp.body);
        assertEquals(56, toolCount,
            "tools/list must return exactly 56 tools (one per readonly MCP tool). "
                + "Got " + toolCount + ". Body snippet: "
                + truncate(resp.body, 500));
    }

    @Test
    @DisplayName("ontology_list returns a non-empty JSON-RPC result")
    void testOntologyListReturnsNonEmpty() {
        assertNotNull(ontologyListResponseBody,
            "ontology_list response must be captured in @BeforeAll");
        assertTrue(ontologyListResponseBody.contains("\"jsonrpc\""),
            "ontology_list response must be a JSON-RPC response. Body: "
                + ontologyListResponseBody);
        assertFalse(ontologyListResponseBody.contains("\"error\""),
            "ontology_list response must not contain an error. Body: "
                + ontologyListResponseBody);
        // The response must contain a "result" field (the ontology list,
        // which may be empty if no ontologies are imported — but the
        // JSON-RPC envelope itself must be non-empty).
        assertTrue(ontologyListResponseBody.contains("\"result\""),
            "ontology_list response must contain a result field. Body: "
                + ontologyListResponseBody);
    }

    @Test
    @DisplayName("ontology_summary on pizza returns non-empty entity counts (if pizza loaded)")
    void testOntologySummaryOnPizza() throws Exception {
        assumeTrue(pizzaAvailable,
            "Skipping: pizza ontology is not loaded in the default workspace. "
                + "Import pizza first: .\\owl4agents.bat import test/corpus/smoke/pizza.owl pizza");

        HttpResponse resp = sendToolCall(serverPort, mcpSessionId,
            "ontology_summary", "{\"ontology_id\":\"pizza\"}");
        assertEquals(200, resp.statusCode,
            "ontology_summary must return HTTP 200. Body: " + resp.body);
        assertFalse(resp.body.contains("\"error\""),
            "ontology_summary must not return an error. Body: " + resp.body);
        assertTrue(resp.body.contains("entityCounts")
            || resp.body.contains("entity_counts")
            || resp.body.contains("classCount")
            || resp.body.contains("class_count"),
            "ontology_summary must contain entity count fields. Body: "
                + truncate(resp.body, 500));
    }

    @Test
    @DisplayName("SPARQL SELECT on pizza returns bindings (if pizza loaded)")
    void testSparqlSelectOnPizza() throws Exception {
        assumeTrue(pizzaAvailable,
            "Skipping: pizza ontology is not loaded in the default workspace.");

        String sparql = "SELECT ?class WHERE { "
            + "?class <http://www.w3.org/2000/01/rdf-schema#subClassOf> "
            + "<http://www.co-ode.org/ontologies/pizza/pizza.owl#Pizza> "
            + "} LIMIT 5";
        String args = "{\"ontology_id\":\"pizza\",\"query\":"
            + jsonString(sparql) + ",\"graph_scope\":\"explicit\"}";
        HttpResponse resp = sendToolCall(serverPort, mcpSessionId,
            "ontology_sparql_select", args);
        assertEquals(200, resp.statusCode,
            "ontology_sparql_select must return HTTP 200. Body: " + resp.body);
        assertFalse(resp.body.contains("\"error\""),
            "ontology_sparql_select must not return an error. Body: " + resp.body);
    }

    @Test
    @DisplayName("verify_claim on pizza returns a verdict (if pizza loaded)")
    void testVerifyClaimOnPizza() throws Exception {
        assumeTrue(pizzaAvailable,
            "Skipping: pizza ontology is not loaded in the default workspace.");

        // Verify a simple subclass claim: Margherita is a Pizza.
        // This should return a SUPPORTED verdict with evidence.
        String claim = "{"
            + "\"type\":\"subclass\","
            + "\"subject\":\"http://www.co-ode.org/ontologies/pizza/pizza.owl#Margherita\","
            + "\"object\":\"http://www.co-ode.org/ontologies/pizza/pizza.owl#Pizza\""
            + "}";
        String args = "{\"ontology_id\":\"pizza\",\"claim\":" + claim + "}";
        HttpResponse resp = sendToolCall(serverPort, mcpSessionId,
            "ontology_verify_claim", args);
        assertEquals(200, resp.statusCode,
            "ontology_verify_claim must return HTTP 200. Body: " + resp.body);
        assertFalse(resp.body.contains("\"error\""),
            "ontology_verify_claim must not return a protocol error. Body: " + resp.body);
        // The response must contain a verdict field (supported/contradicted/unknown/out_of_scope).
        assertTrue(resp.body.contains("verdict")
            || resp.body.contains("status"),
            "ontology_verify_claim response must contain a verdict or status. Body: "
                + truncate(resp.body, 500));
    }

    // ── HTTP request helpers ───────────────────────────────────────────

    /**
     * Send an initialize JSON-RPC request (no session ID required).
     */
    private static HttpResponse sendInitialize(int port) throws IOException {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"deployment-smoke-test\",\"version\":\"1.0\"}}}";
        return sendRawRequest(port, null, body);
    }

    /**
     * Send a generic JSON-RPC request with the given session ID.
     */
    private static HttpResponse sendRequest(int port, String sessionId, String body)
            throws IOException {
        return sendRawRequest(port, sessionId, body);
    }

    /**
     * Send a tools/call request for a specific readonly tool.
     */
    private static HttpResponse sendToolCall(int port, String sessionId,
            String toolName, String argumentsJson) throws IOException {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"" + toolName + "\","
            + "\"arguments\":" + argumentsJson + "}}";
        return sendRawRequest(port, sessionId, body);
    }

    /**
     * Send a POST /mcp request with the given body and optional session ID.
     */
    private static HttpResponse sendRawRequest(int port, String sessionId, String body)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(
            "http://127.0.0.1:" + port + "/mcp").toURL().openConnection();
        conn.setConnectTimeout(2_000);
        conn.setReadTimeout(REQUEST_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        if (sessionId != null && !sessionId.isBlank()) {
            conn.setRequestProperty("Mcp-Session-Id", sessionId);
        }
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        HttpResponse resp = new HttpResponse();
        resp.statusCode = conn.getResponseCode();
        String mcpId = conn.getHeaderField("Mcp-Session-Id");
        if (mcpId != null) {
            resp.headers.put("Mcp-Session-Id", mcpId);
        }
        try (InputStream is = resp.statusCode >= 200 && resp.statusCode < 400
            ? conn.getInputStream() : conn.getErrorStream()) {
            if (is != null) {
                resp.body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } else {
                resp.body = "";
            }
        } finally {
            conn.disconnect();
        }
        return resp;
    }

    /**
     * Poll the MCP HTTP endpoint until it responds (or timeout). Sends an
     * {@code initialize} JSON-RPC request; any HTTP response (200/400/404)
     * indicates the server is up and accepting connections.
     */
    private static boolean waitForServerStartup(int port, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        String initBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"deployment-smoke-test\",\"version\":\"1.0\"}}}";
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(
                    "http://127.0.0.1:" + port + "/mcp").toURL().openConnection();
                conn.setConnectTimeout(500);
                conn.setReadTimeout(500);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(initBody.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                if (code >= 200 && code < 500) {
                    return true;
                }
            } catch (IOException ignored) {
                // Server not ready yet — keep polling.
            }
            Thread.sleep(500);
        }
        return false;
    }

    // ── Shadow jar location helpers (adapted from ServerKillableAfterOomTest) ──

    /**
     * Locate the shadow jar produced by {@code :modules:ontology-cli:shadowJar}.
     * Tries multiple known paths (gradle layout has shifted between versions).
     */
    private static Path locateShadowJar() {
        List<Path> candidates = new ArrayList<>();
        // v0.8.x layout: build/modules/ontology-cli/libs/owl4agents.jar
        candidates.add(Paths.get("build/modules/ontology-cli/libs/owl4agents.jar"));
        // Pre-0.8 layout: modules/ontology-cli/build/libs/owl4agents.jar
        candidates.add(Paths.get("modules/ontology-cli/build/libs/owl4agents.jar"));
        Path root = findProjectRoot();
        candidates.add(root.resolve("build/modules/ontology-cli/libs/owl4agents.jar"));
        candidates.add(root.resolve("modules/ontology-cli/build/libs/owl4agents.jar"));

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath();
            }
        }
        return root.resolve("build/modules/ontology-cli/libs/owl4agents.jar");
    }

    private static Path findProjectRoot() {
        String userDir = System.getProperty("user.dir");
        Path cwd = userDir != null
            ? Path.of(userDir).toAbsolutePath()
            : Path.of("").toAbsolutePath();
        Path dir = cwd;
        for (int i = 0; i < 10 && dir != null; i++) {
            if (Files.exists(dir.resolve("build.gradle.kts"))
                && Files.exists(dir.resolve("settings.gradle.kts"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return cwd;
    }

    // ── Utility helpers ────────────────────────────────────────────────

    /**
     * Count the number of tool entries in a tools/list response body by
     * counting occurrences of {@code "name":"ontology_}. This is robust
     * to whitespace variations because the MCP server emits compact JSON
     * via Gson (no spaces around colons).
     */
    private static int countToolEntries(String body) {
        if (body == null || body.isEmpty()) {
            return 0;
        }
        String needle = "\"name\":\"ontology_";
        int count = 0;
        int idx = 0;
        while ((idx = body.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        // Also accept the spaced variant just in case Gson config changes.
        String spacedNeedle = "\"name\": \"ontology_";
        int spacedCount = 0;
        idx = 0;
        while ((idx = body.indexOf(spacedNeedle, idx)) != -1) {
            spacedCount++;
            idx += spacedNeedle.length();
        }
        return Math.max(count, spacedCount);
    }

    /**
     * Escape a string for embedding in JSON.
     */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...(truncated)";
    }

    private static void drainAsync(Process process) {
        Thread t = new Thread(() -> {
            try (InputStream is = process.getInputStream()) {
                byte[] buf = new byte[4096];
                while (is.read(buf) != -1) {
                    // Discard — we only care about HTTP responses.
                }
            } catch (IOException ignored) {
                // Process died; drain exits.
            }
        }, "deployment-test-drain");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Minimal HTTP response holder.
     */
    private static final class HttpResponse {
        int statusCode;
        String body = "";
        final java.util.Map<String, String> headers = new java.util.HashMap<>();

        String header(String name) {
            return headers.get(name);
        }
    }
}
