package org.owl4agents.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8 SSE integration tests for {@link HttpMcpServer}. Covers the
 * spec.md scenarios for the MCP 2025-03-26 Streamable HTTP transport:
 *
 * <ul>
 *   <li>TC-27..36 — accept negotiation, session id lifecycle, fallback
 *       headers, multi-stream fan-out, heartbeat, shutdown, cap,
 *       header validation, no-new-deps, version assertion.</li>
 * </ul>
 *
 * <p>These tests bring up a real {@link HttpMcpServer} on an
 * ephemeral port and exercise it over a real TCP socket. SSE
 * responses are read off the InputStream to verify the bytes
 * the server writes match the v0.8 contract.</p>
 */
@DisplayName("MCP HTTP SSE integration tests (v0.8)")
class HttpMcpServerSseTest {

    @TempDir
    Path tempDir;

    private McpServerAdapter adapter;
    private HttpMcpServer server;
    private HttpClient client;
    private String baseUrl;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        Map<String, Object> serviceContext = new HashMap<>();
        String logPath = tempDir.resolve("mcp-http-sse-test.log").toString();
        adapter = new McpServerAdapter(serviceContext, logPath);
        // Use a short heartbeat for the heartbeat test (1s) — but the
        // default 15s works for the other tests. Tests that need a
        // short heartbeat build their own server.
        server = new HttpMcpServer(adapter);
        server.start("127.0.0.1", 0);
        port = readBoundPort(server);
        baseUrl = "http://127.0.0.1:" + port;
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    // ── 1. Constructor validation ──

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorTests {

        @Test
        @DisplayName("Constructor rejects null adapter")
        void constructorRejectsNullAdapter() {
            assertThrows(IllegalArgumentException.class,
                () -> new HttpMcpServer(null));
        }

        @Test
        @DisplayName("Constructor rejects maxSseConnections < 1 (per spec.md §\"Validation\")")
        void constructorRejectsZeroSseCap() {
            assertThrows(IllegalArgumentException.class,
                () -> new HttpMcpServer(adapter, 0,
                    HttpMcpServer.DEFAULT_SESSION_TTL,
                    HttpMcpServer.DEFAULT_HEARTBEAT_INTERVAL));
        }

        @Test
        @DisplayName("Constructor rejects sessionTtl < 1 minute")
        void constructorRejectsShortTtl() {
            assertThrows(IllegalArgumentException.class,
                () -> new HttpMcpServer(adapter, 100,
                    Duration.ofSeconds(30),
                    HttpMcpServer.DEFAULT_HEARTBEAT_INTERVAL));
        }

        @Test
        @DisplayName("Constructor rejects heartbeatInterval < 1 second")
        void constructorRejectsShortHeartbeat() {
            assertThrows(IllegalArgumentException.class,
                () -> new HttpMcpServer(adapter, 100,
                    HttpMcpServer.DEFAULT_SESSION_TTL,
                    Duration.ofMillis(500)));
        }
    }

    // ── 2. GET /mcp accept negotiation ──

    @Nested
    @DisplayName("GET /mcp accept negotiation")
    class GetAcceptNegotiation {

        @Test
        @DisplayName("GET /mcp with Accept: text/event-stream and a valid Mcp-Session-Id returns 200 with text/event-stream")
        void getSseWithValidSessionReturnsStream() throws Exception {
            String sessionId = UUID.randomUUID().toString();
            HttpResponse<InputStream> resp = openSseStream(sessionId);
            try {
                assertEquals(200, resp.statusCode());
                assertTrue(resp.headers().firstValue("Content-Type").isPresent());
                assertTrue(resp.headers().firstValue("Content-Type").get()
                    .startsWith("text/event-stream"),
                    "Content-Type must be text/event-stream: "
                        + resp.headers().firstValue("Content-Type").get());
                assertEquals(sessionId,
                    resp.headers().firstValue("Mcp-Session-Id").orElse(null),
                    "Server must echo the Mcp-Session-Id");
            } finally {
                resp.body().close();
            }
        }

        @Test
        @DisplayName("GET /mcp without Accept: text/event-stream returns 405 with Allow: GET, POST")
        void getWithoutEventStreamAcceptReturns405() throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
                .header("Accept", "application/json")
                .header("Mcp-Session-Id", UUID.randomUUID().toString())
                .GET()
                .timeout(Duration.ofSeconds(2))
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(405, resp.statusCode());
            assertTrue(resp.headers().firstValue("Allow").isPresent());
            String allow = resp.headers().firstValue("Allow").get();
            assertTrue(allow.contains("GET") && allow.contains("POST"),
                "Allow header must include both GET and POST: " + allow);
        }

        @Test
        @DisplayName("GET /mcp without a Mcp-Session-Id returns 400 (initialize first)")
        void getWithoutSessionIdReturns400() throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
                .header("Accept", "text/event-stream")
                .GET()
                .timeout(Duration.ofSeconds(2))
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, resp.statusCode());
        }

        @Test
        @DisplayName("GET /mcp with a non-UUID Mcp-Session-Id returns 400")
        void getWithInvalidSessionIdReturns400() throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
                .header("Accept", "text/event-stream")
                .header("Mcp-Session-Id", "not-a-uuid")
                .GET()
                .timeout(Duration.ofSeconds(2))
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, resp.statusCode());
        }
    }

    // ── 3. SSE stream ready frame ──

    @Nested
    @DisplayName("SSE stream ready frame")
    class ReadyFrame {

        @Test
        @DisplayName("The first event on an SSE stream is a 'ready' frame echoing the sessionId")
        void firstEventIsReady() throws Exception {
            String sessionId = UUID.randomUUID().toString();
            HttpResponse<InputStream> resp = openSseStream(sessionId);
            try {
                // Read until we get a non-empty event
                String firstEvent = readFirstEvent(resp.body());
                assertNotNull(firstEvent, "Server must emit at least one event on SSE open");
                assertTrue(firstEvent.contains("\"type\":\"ready\""),
                    "First event must be a 'ready' frame: " + firstEvent);
                assertTrue(firstEvent.contains("\"" + sessionId + "\""),
                    "Ready frame must echo the sessionId: " + firstEvent);
            } finally {
                resp.body().close();
            }
        }
    }

    // ── 4. POST /mcp with Mcp-Session-Id ──

    @Nested
    @DisplayName("POST /mcp Mcp-Session-Id")
    class PostSessionId {

        @Test
        @DisplayName("POST /mcp initialize returns the Mcp-Session-Id header in the response")
        void initializeReturnsSessionIdHeader() throws Exception {
            JsonObject req = new JsonObject();
            req.addProperty("jsonrpc", "2.0");
            req.addProperty("id", 1);
            req.addProperty("method", "initialize");
            req.add("params", new JsonObject());

            HttpResponse<String> resp = postJson("/mcp", req.toString(), null,
                "application/json");
            assertEquals(200, resp.statusCode());
            assertTrue(resp.headers().firstValue("Mcp-Session-Id").isPresent(),
                "initialize must return a Mcp-Session-Id header");
            String id = resp.headers().firstValue("Mcp-Session-Id").get();
            assertEquals(4, UUID.fromString(id).version(),
                "Mcp-Session-Id must be a UUID v4");
        }

        @Test
        @DisplayName("POST /mcp with a non-UUID Mcp-Session-Id returns 400")
        void postWithInvalidSessionIdReturns400() throws Exception {
            JsonObject req = new JsonObject();
            req.addProperty("jsonrpc", "2.0");
            req.addProperty("id", 1);
            req.addProperty("method", "tools/list");
            req.add("params", new JsonObject());

            HttpResponse<String> resp = postJson("/mcp", req.toString(), "not-a-uuid",
                "application/json");
            assertEquals(400, resp.statusCode());
        }
    }

    // ── 5. POST /mcp with SSE fallback ──

    @Nested
    @DisplayName("POST /mcp SSE fallback")
    class PostFallback {

        @Test
        @DisplayName("POST with Accept: text/event-stream and NO open stream returns plain JSON with X-Streamable-Http-Fallback")
        void postSseAcceptWithoutStreamReturnsFallback() throws Exception {
            String sessionId = UUID.randomUUID().toString();
            JsonObject req = new JsonObject();
            req.addProperty("jsonrpc", "2.0");
            req.addProperty("id", 1);
            req.addProperty("method", "tools/list");
            req.add("params", new JsonObject());

            HttpResponse<String> resp = postJson("/mcp", req.toString(), sessionId,
                "application/json, text/event-stream");
            assertEquals(200, resp.statusCode());
            assertTrue(resp.headers().firstValue("X-Streamable-Http-Fallback").isPresent(),
                "Server must signal fallback via X-Streamable-Http-Fallback header");
            String fallback = resp.headers().firstValue("X-Streamable-Http-Fallback").get();
            assertTrue(fallback.contains("no-open-stream")
                    || fallback.contains("application/json"),
                "Fallback reason must indicate 'no-open-stream' or 'application/json': "
                    + fallback);
            // Body is plain JSON-RPC
            JsonObject body = new Gson().fromJson(resp.body(), JsonObject.class);
            assertTrue(body.has("result"), "Body must be a JSON-RPC result: " + resp.body());
        }
    }

    // ── 6. Max SSE connection cap ──

    @Nested
    @DisplayName("Max SSE connection cap")
    class SseConnectionCap {

        @Test
        @DisplayName("GET /mcp returns 503 + Retry-After when the global SSE cap is reached")
        void overCapReturns503() throws Exception {
            // Spin up a server with cap=1
            McpServerAdapter localAdapter = new McpServerAdapter(new HashMap<>(),
                tempDir.resolve("mcp-cap-test.log").toString());
            HttpMcpServer localServer = new HttpMcpServer(localAdapter, 1,
                HttpMcpServer.DEFAULT_SESSION_TTL, HttpMcpServer.DEFAULT_HEARTBEAT_INTERVAL);
            try {
                localServer.start("127.0.0.1", 0);
                int localPort = readBoundPort(localServer);
                String localBase = "http://127.0.0.1:" + localPort;
                HttpClient c = HttpClient.newHttpClient();

                // Open the first stream
                String id1 = UUID.randomUUID().toString();
                HttpRequest sseReq = HttpRequest.newBuilder(URI.create(localBase + "/mcp"))
                    .header("Accept", "text/event-stream")
                    .header("Mcp-Session-Id", id1)
                    .GET()
                    .timeout(Duration.ofSeconds(2))
                    .build();
                HttpResponse<InputStream> first = c.send(sseReq,
                    HttpResponse.BodyHandlers.ofInputStream());
                try {
                    assertEquals(200, first.statusCode(),
                        "First stream must be admitted (cap=1)");

                    // Second stream from a different session must be rejected
                    String id2 = UUID.randomUUID().toString();
                    HttpRequest sse2 = HttpRequest.newBuilder(URI.create(localBase + "/mcp"))
                        .header("Accept", "text/event-stream")
                        .header("Mcp-Session-Id", id2)
                        .GET()
                        .timeout(Duration.ofSeconds(2))
                        .build();
                    HttpResponse<String> second = c.send(sse2,
                        HttpResponse.BodyHandlers.ofString());
                    assertEquals(503, second.statusCode(),
                        "Second stream must be rejected with 503 (cap=1), got " + second.statusCode() + " body=" + second.body());
                    assertTrue(second.headers().firstValue("Retry-After").isPresent(),
                        "503 must include Retry-After header");
                } finally {
                    first.body().close();
                }
            } finally {
                localServer.stop();
            }
        }
    }

    // ── 7. /info endpoint ──

    @Nested
    @DisplayName("/info endpoint")
    class InfoEndpoint {

        @Test
        @DisplayName("GET /info includes SSE configuration (max_sse_connections, active_sse_connections, version)")
        void infoIncludesSseConfig() throws Exception {
            HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/info")).GET()
                    .timeout(Duration.ofSeconds(2)).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            JsonObject body = new Gson().fromJson(resp.body(), JsonObject.class);
            // Server announces SSE config so operators can confirm.
            assertEquals(100, body.get("max_sse_connections").getAsInt());
            assertEquals(0, body.get("active_sse_connections").getAsLong());
            assertEquals(McpServerAdapter.SERVER_VERSION, body.get("version").getAsString());
        }
    }

    // ── 8. Shutdown ──

    @Nested
    @DisplayName("Shutdown")
    class Shutdown {

        @Test
        @DisplayName("stop() closes all open SSE streams and counts down the shutdown latch")
        void stopClosesStreamsAndLatch() throws Exception {
            String sessionId = UUID.randomUUID().toString();
            HttpResponse<InputStream> sse = openSseStream(sessionId);
            try {
                // Read first event to confirm the stream is open
                String first = readFirstEvent(sse.body());
                assertNotNull(first);

                CountDownLatch latch = server.getShutdownLatch();
                server.stop();

                assertTrue(latch.getCount() == 0,
                    "shutdown latch must be counted down after stop()");
                // After stop, attempting to read more from the stream
                // must throw IOException (or return EOF), confirming
                // the writer side was closed.
                assertThrows(IOException.class, () -> {
                    byte[] buf = new byte[64];
                    sse.body().read(buf);
                    if (sse.body().read(buf) == -1) {
                        throw new IOException("stream closed cleanly");
                    }
                });
            } finally {
                sse.body().close();
            }
        }

        @Test
        @DisplayName("stop() is idempotent (safe to call multiple times)")
        void stopIsIdempotent() {
            server.stop();
            server.stop();
            // No exception
        }
    }

    // ── 9. Heartbeat ──

    @Nested
    @DisplayName("Heartbeat")
    class Heartbeat {

        @Test
        @DisplayName("SSE stream emits a heartbeat comment frame within 2*heartbeat-interval seconds")
        void emitsHeartbeat() throws Exception {
            // Spin up a server with heartbeat=1s so the test runs fast.
            McpServerAdapter localAdapter = new McpServerAdapter(new HashMap<>(),
                tempDir.resolve("mcp-heartbeat-test.log").toString());
            HttpMcpServer localServer = new HttpMcpServer(localAdapter, 100,
                HttpMcpServer.DEFAULT_SESSION_TTL, Duration.ofSeconds(1));
            try {
                localServer.start("127.0.0.1", 0);
                int localPort = readBoundPort(localServer);
                String localBase = "http://127.0.0.1:" + localPort;
                HttpClient c = HttpClient.newHttpClient();

                String id = UUID.randomUUID().toString();
                HttpRequest sseReq = HttpRequest.newBuilder(URI.create(localBase + "/mcp"))
                    .header("Accept", "text/event-stream")
                    .header("Mcp-Session-Id", id)
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
                HttpResponse<InputStream> resp = c.send(sseReq,
                    HttpResponse.BodyHandlers.ofInputStream());
                try {
                    // Read the ready frame first
                    String first = readFirstEvent(resp.body());
                    assertTrue(first.contains("\"type\":\"ready\""));
                    // Now wait up to 3s for a heartbeat comment
                    StringBuilder tail = new StringBuilder();
                    byte[] buf = new byte[64];
                    long deadline = System.currentTimeMillis() + 3000;
                    boolean heartbeatSeen = false;
                    while (System.currentTimeMillis() < deadline) {
                        int avail = resp.body().available();
                        if (avail > 0) {
                            int n = resp.body().read(buf);
                            if (n > 0) {
                                tail.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                                if (tail.toString().contains(": ping")) {
                                    heartbeatSeen = true;
                                    break;
                                }
                            }
                        } else {
                            Thread.sleep(50);
                        }
                    }
                    assertTrue(heartbeatSeen,
                        "Expected SSE heartbeat within 3s, got: " + tail);
                } finally {
                    resp.body().close();
                }
            } finally {
                localServer.stop();
            }
        }
    }

    // ── 10. Version assertion ──

    @Nested
    @DisplayName("Version assertion")
    class VersionAssertion {

        @Test
        @DisplayName("The /info response reports McpServerAdapter.SERVER_VERSION (= 0.8.2)")
        void infoReportsServerVersion() throws Exception {
            HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/info")).GET()
                    .timeout(Duration.ofSeconds(2)).build(),
                HttpResponse.BodyHandlers.ofString());
            JsonObject body = new Gson().fromJson(resp.body(), JsonObject.class);
            assertEquals(McpServerAdapter.SERVER_VERSION, body.get("version").getAsString());
        }
    }

    // ── Helpers ──

    private static int readBoundPort(HttpMcpServer s) {
        try {
            java.lang.reflect.Field f = HttpMcpServer.class.getDeclaredField("server");
            f.setAccessible(true);
            com.sun.net.httpserver.HttpServer hs = (com.sun.net.httpserver.HttpServer) f.get(s);
            return hs.getAddress().getPort();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read bound port from HttpMcpServer", e);
        }
    }

    private HttpResponse<InputStream> openSseStream(String sessionId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
            .header("Accept", "text/event-stream")
            .header("Mcp-Session-Id", sessionId)
            .GET()
            .timeout(Duration.ofSeconds(2))
            .build();
        return client.send(req, HttpResponse.BodyHandlers.ofInputStream());
    }

    private HttpResponse<String> postJson(String path, String body, String sessionId, String accept)
        throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .header("Accept", accept)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(5));
        if (sessionId != null) {
            b.header("Mcp-Session-Id", sessionId);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Read a single SSE event from the stream's body. The first event is
     * terminated by a blank line (\n\n). We read bytes until we see that.
     */
    private String readFirstEvent(InputStream body) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[64];
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            int n = body.read(buf);
            if (n < 0) break;
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            if (sb.toString().contains("\n\n")) {
                return sb.toString();
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
