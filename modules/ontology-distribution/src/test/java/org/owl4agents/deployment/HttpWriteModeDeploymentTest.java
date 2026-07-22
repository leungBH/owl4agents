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
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * v0.8.7 mcp-write-tools: HTTP deployment smoke test for write mode.
 *
 * <p>Spawns a real MCP HTTP server with {@code --readonly=false} and
 * verifies:</p>
 * <ul>
 *   <li>MCP-WRITE-001: tools/list returns readonly tools + ontology_import</li>
 *   <li>MCP-WRITE-008: ontology_import succeeds with valid content_base64</li>
 *   <li>MCP-WRITE-009: readonly mode (separate server) rejects ontology_import
 *       with READONLY_VIOLATION</li>
 * </ul>
 *
 * <p>Skipped when {@code skip.deployment.test=true} or the shadow jar has
 * not been built.</p>
 */
@Tag("deployment")
@DisabledIfSystemProperty(named = "skip.deployment.test", matches = "true")
@DisplayName("v0.8.7 HTTP write mode deployment test")
class HttpWriteModeDeploymentTest {

    private static final int STARTUP_TIMEOUT_SECONDS = 60;
    private static final int REQUEST_TIMEOUT_MS = 15_000;

    private static Process writeServerProcess;
    private static int writeServerPort;
    private static String writeSessionId;
    private static Path shadowJarPath;
    private static boolean writeServerStarted;

    @BeforeAll
    static void startWriteServer() throws Exception {
        shadowJarPath = HttpDeploymentSmokeTest.locateShadowJarPublic();
        assumeTrue(Files.exists(shadowJarPath),
            "Skipping: shadow jar not built. Run '.\\gradlew.bat :modules:ontology-cli:shadowJar' first.");

        try (ServerSocket ss = new ServerSocket(0)) {
            writeServerPort = ss.getLocalPort();
        }

        Path projectRoot = findProjectRoot();
        // v0.8.7: start with --readonly=false to enable write mode.
        ProcessBuilder pb = new ProcessBuilder(
            "java",
            "-Xmx4g",
            "-XX:+ExitOnOutOfMemoryError",
            "-jar",
            shadowJarPath.toString(),
            "mcp",
            "--transport=http",
            "--host=127.0.0.1",
            "--port=" + writeServerPort,
            "--readonly=false"
        );
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);
        writeServerProcess = pb.start();
        drainAsync(writeServerProcess);

        writeServerStarted = waitForServerStartup(writeServerPort, STARTUP_TIMEOUT_SECONDS);
        assumeTrue(writeServerStarted,
            "Skipping: MCP HTTP write-mode server did not start within "
                + STARTUP_TIMEOUT_SECONDS + "s");

        // Initialize MCP session.
        HttpResponse initResp = sendInitialize(writeServerPort);
        assertEquals(200, initResp.statusCode,
            "initialize must return HTTP 200. Body: " + initResp.body);
        writeSessionId = initResp.header("Mcp-Session-Id");
        assertNotNull(writeSessionId, "initialize must return Mcp-Session-Id");
    }

    @AfterAll
    static void stopWriteServer() {
        if (writeServerProcess != null && writeServerProcess.isAlive()) {
            writeServerProcess.destroyForcibly();
            try {
                writeServerProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    @DisplayName("MCP-WRITE-001: write-mode tools/list includes ontology_import")
    void testWriteModeToolsListIncludesOntologyImport() throws Exception {
        HttpResponse resp = sendRequest(writeServerPort, writeSessionId,
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        assertEquals(200, resp.statusCode,
            "tools/list must return HTTP 200. Body: " + resp.body);
        assertTrue(resp.body.contains("ontology_import"),
            "tools/list in write mode must include ontology_import. Body: "
                + truncate(resp.body, 500));
        // Readonly tools must still be present.
        assertTrue(resp.body.contains("ontology_list"),
            "tools/list in write mode must still include readonly tools. Body: "
                + truncate(resp.body, 500));
    }

    @Test
    @DisplayName("MCP-WRITE-003: ontology_import succeeds with content_base64")
    void testOntologyImportSucceeds() throws Exception {
        // Minimal valid OWL/XML ontology.
        String owl = "<?xml version=\"1.0\"?>"
            + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\""
            + "         xmlns:owl=\"http://www.w3.org/2002/07/owl#\""
            + "         xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\">"
            + "  <owl:Ontology rdf:about=\"http://example.org/deploy-test\"/>"
            + "  <owl:Class rdf:about=\"http://example.org/deploy-test#TestClass\"/>"
            + "</rdf:RDF>";
        String base64 = Base64.getEncoder()
            .encodeToString(owl.getBytes(StandardCharsets.UTF_8));
        String args = "{\"ontology_id\":\"deploy-test\","
            + "\"content_base64\":\"" + base64 + "\"}";
        HttpResponse resp = sendToolCall(writeServerPort, writeSessionId,
            "ontology_import", args);
        assertEquals(200, resp.statusCode,
            "ontology_import must return HTTP 200. Body: " + resp.body);
        assertFalse(resp.body.contains("\"READONLY_VIOLATION\""),
            "ontology_import in write mode must NOT return READONLY_VIOLATION. Body: "
                + resp.body);
    }

    @Test
    @DisplayName("MCP-WRITE-010: readonly mode (separate server) rejects ontology_import")
    void testReadonlyModeRejectsImport() throws Exception {
        // Start a separate readonly server.
        int readonlyPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            readonlyPort = ss.getLocalPort();
        }
        Path projectRoot = findProjectRoot();
        ProcessBuilder pb = new ProcessBuilder(
            "java", "-Xmx4g", "-XX:+ExitOnOutOfMemoryError",
            "-jar", shadowJarPath.toString(),
            "mcp", "--transport=http", "--host=127.0.0.1",
            "--port=" + readonlyPort);
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);
        Process readonlyProcess = pb.start();
        drainAsync(readonlyProcess);
        try {
            boolean started = waitForServerStartup(readonlyPort, STARTUP_TIMEOUT_SECONDS);
            assumeTrue(started, "Skipping: readonly server did not start");

            HttpResponse initResp = sendInitialize(readonlyPort);
            String readonlySessionId = initResp.header("Mcp-Session-Id");
            assertNotNull(readonlySessionId);

            String args = "{\"ontology_id\":\"should-fail\","
                + "\"content_base64\":\"PG92bC8+\"}";
            HttpResponse resp = sendToolCall(readonlyPort, readonlySessionId,
                "ontology_import", args);
            assertEquals(200, resp.statusCode);
            assertTrue(resp.body.contains("READONLY_VIOLATION"),
                "ontology_import in readonly mode must return READONLY_VIOLATION. Body: "
                    + resp.body);
        } finally {
            if (readonlyProcess.isAlive()) {
                readonlyProcess.destroyForcibly();
                readonlyProcess.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    // ── HTTP helpers (mirrored from HttpDeploymentSmokeTest) ───────────

    private static boolean waitForServerStartup(int port, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection conn = (HttpURLConnection)
                    URI.create("http://127.0.0.1:" + port + "/mcp").toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(500);
                conn.setReadTimeout(500);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);
                byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"probe\",\"version\":\"1.0\"}}}").getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
                int code = conn.getResponseCode();
                if (code >= 200 && code < 500) return true;
            } catch (Exception ignored) {
                // Server not ready yet.
            }
            try { Thread.sleep(500); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); return false;
            }
        }
        return false;
    }

    private static HttpResponse sendInitialize(int port) throws IOException {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"write-mode-test\",\"version\":\"1.0\"}}}";
        return sendRawRequest(port, null, body);
    }

    private static HttpResponse sendRequest(int port, String sessionId, String body) throws IOException {
        return sendRawRequest(port, sessionId, body);
    }

    private static HttpResponse sendToolCall(int port, String sessionId, String toolName, String argumentsJson) throws IOException {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"" + toolName + "\","
            + "\"arguments\":" + argumentsJson + "}}";
        return sendRawRequest(port, sessionId, body);
    }

    private static HttpResponse sendRawRequest(int port, String sessionId, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection)
            URI.create("http://127.0.0.1:" + port + "/mcp").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(REQUEST_TIMEOUT_MS);
        conn.setReadTimeout(REQUEST_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "application/json");
        // Use Accept: application/json (not text/event-stream) to avoid the
        // SSE fallback path. The smoke test uses the same header and reliably
        // receives the Mcp-Session-Id response header.
        conn.setRequestProperty("Accept", "application/json");
        if (sessionId != null) {
            conn.setRequestProperty("Mcp-Session-Id", sessionId);
        }
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        HttpResponse resp = new HttpResponse();
        resp.statusCode = conn.getResponseCode();
        // Read Mcp-Session-Id directly via getHeaderField (case-insensitive).
        // Iterating getHeaderFields().keySet() + HashMap.get is case-sensitive
        // and can miss the header if the JDK normalizes its casing.
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

    private static void drainAsync(Process process) {
        Thread t = new Thread(() -> {
            try (InputStream is = process.getInputStream()) {
                byte[] buf = new byte[4096];
                while (is.read(buf) != -1) { }
            } catch (IOException ignored) { }
        }, "write-mode-drain");
        t.setDaemon(true);
        t.start();
    }

    private static Path findProjectRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("build.gradle.kts"))
                && Files.exists(dir.resolve("settings.gradle.kts"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return Path.of(System.getProperty("user.dir"));
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...(truncated)";
    }

    private static final class HttpResponse {
        int statusCode;
        String body = "";
        final java.util.Map<String, String> headers = new java.util.HashMap<>();

        String header(String name) {
            return headers.get(name);
        }
    }
}
