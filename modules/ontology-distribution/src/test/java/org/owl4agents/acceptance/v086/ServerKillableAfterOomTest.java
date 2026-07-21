package org.owl4agents.acceptance.v086;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * v0.8.6 P0-4 acceptance test (task 4.5): verifies that the MCP HTTP server
 * JVM exits cleanly on OutOfMemoryError when launched with
 * {@code -XX:+ExitOnOutOfMemoryError}. This allows the watchdog to detect
 * the missing process and cold-start a new instance within its 30s polling
 * interval (see D5 in openspec/changes/v086-stability-fixes/design.md).
 *
 * <p>The test spawns a fresh JVM with a 128MB heap, waits for the HTTP server
 * to bind to port 9999, then triggers OOM by sending a ~200MB JSON-RPC body.
 * The HTTP handler in {@code HttpMcpServer} calls {@code is.readAllBytes()}
 * followed by {@code JsonParser.parseString(...)} — both allocate the entire
 * body on the heap, triggering OOM in the 128MB JVM. With
 * {@code -XX:+ExitOnOutOfMemoryError}, the JVM writes a heap dump and exits
 * with code 3.</p>
 *
 * <p>The test is skipped when:</p>
 * <ul>
 *   <li>{@code skip.oom.test=true} system property is set (CI environments
 *       where spawning child JVMs is undesirable).</li>
 *   <li>The shadow jar has not been built (no {@code owl4agents.jar} on disk).
 *       Run {@code .\gradlew.bat :modules:ontology-cli:shadowJar} first.</li>
 * </ul>
 *
 * <p>Tagged {@code "acceptance"} so it can be included/excluded via
 * JUnit tag filtering.</p>
 */
@Tag("acceptance")
@DisabledIfSystemProperty(named = "skip.oom.test", matches = "true")
class ServerKillableAfterOomTest {

    private static final int PORT = 9999;
    private static final int HEAP_MB = 128;
    private static final int STARTUP_TIMEOUT_SECONDS = 30;
    private static final int OOM_TRIGGER_TIMEOUT_SECONDS = 60;

    @Test
    void serverExitsCleanlyOnOutOfMemoryError() throws Exception {
        Path shadowJar = locateShadowJar();
        assumeTrue(Files.exists(shadowJar),
            "Skipping: shadow jar not built. Run '.\\gradlew.bat :modules:ontology-cli:shadowJar' first.");

        Path projectRoot = findProjectRoot();
        Path heapDumpPath = projectRoot.resolve("owl4agents-heapdump.hprof");

        // Pre-cleanup: delete any stale heap dump from prior runs.
        deleteIfExistsQuiet(heapDumpPath);

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "java",
                "-Xmx" + HEAP_MB + "m",
                "-XX:+ExitOnOutOfMemoryError",
                "-XX:+HeapDumpOnOutOfMemoryError",
                "-XX:HeapDumpPath=./owl4agents-heapdump.hprof",
                "-jar",
                shadowJar.toString(),
                "mcp",
                "--transport=http",
                "--host=127.0.0.1",
                "--port=" + PORT
            );
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            process = pb.start();

            // Drain stdout/stderr on a background thread so the child does not
            // block on a full pipe buffer while we wait for OOM.
            drainAsync(process);

            boolean started = waitForServerStartup(PORT, STARTUP_TIMEOUT_SECONDS);
            assertTrue(started,
                "MCP HTTP server did not start within " + STARTUP_TIMEOUT_SECONDS + "s");

            triggerOom(PORT);

            boolean exited = process.waitFor(OOM_TRIGGER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(exited,
                "JVM did not exit within " + OOM_TRIGGER_TIMEOUT_SECONDS
                    + "s after OOM trigger — ExitOnOutOfMemoryError may not be working");
            assertFalse(process.isAlive(),
                "Process should not be alive after ExitOnOutOfMemoryError fired");

            int code = process.exitValue();
            // ExitOnOutOfMemoryError exits with code 3. Be flexible: the contract
            // is "process dies", not a specific exit code, because future JDK
            // versions could change the code or the heap dump step could fail.
            System.out.println("[ServerKillableAfterOomTest] JVM exited with code "
                + code + " (ExitOnOutOfMemoryError typically produces code 3)");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            // Post-cleanup: remove the heap dump produced by this run so the
            // working directory is left clean. The launch scripts also delete
            // the heap dump before each launch (task 4.3/4.4).
            deleteIfExistsQuiet(heapDumpPath);
        }
    }

    /**
     * Locate the shadow jar produced by {@code :modules:ontology-cli:shadowJar}.
     * Tries multiple known paths (gradle layout has shifted between versions).
     * Returns the first match; if none exists, returns the canonical path so
     * that the caller's {@code assumeTrue(Files.exists(...))} skips the test.
     */
    private Path locateShadowJar() {
        List<Path> candidates = new ArrayList<>();
        // v0.8.x layout: build/modules/ontology-cli/libs/owl4agents.jar
        candidates.add(Paths.get("build/modules/ontology-cli/libs/owl4agents.jar"));
        // Pre-0.8 layout: modules/ontology-cli/build/libs/owl4agents.jar
        candidates.add(Paths.get("modules/ontology-cli/build/libs/owl4agents.jar"));
        // Project-root-relative variants
        Path root = findProjectRoot();
        candidates.add(root.resolve("build/modules/ontology-cli/libs/owl4agents.jar"));
        candidates.add(root.resolve("modules/ontology-cli/build/libs/owl4agents.jar"));

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath();
            }
        }
        // Return a non-existent path so the assumption fails gracefully.
        return root.resolve("build/modules/ontology-cli/libs/owl4agents.jar");
    }

    private Path findProjectRoot() {
        String userDir = System.getProperty("user.dir");
        Path cwd = userDir != null ? Path.of(userDir).toAbsolutePath() : Path.of("").toAbsolutePath();
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

    /**
     * Poll the MCP HTTP endpoint until it responds (or timeout). Sends an
     * {@code initialize} JSON-RPC request; any HTTP response (200/400/404)
     * indicates the server is up.
     */
    private boolean waitForServerStartup(int port, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        String initBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"oom-test\",\"version\":\"1\"}}}";
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

    /**
     * Trigger OOM in the child JVM by sending a ~200MB JSON-RPC body. The
     * HTTP handler reads the entire body into a {@code String} via
     * {@code is.readAllBytes()} then parses with Gson — both operations
     * allocate the body on the 128MB heap, triggering OOM.
     *
     * <p>The body is streamed to the socket in 1MB chunks so the test JVM
     * (which has a 4GB heap per {@code build.gradle.kts}) does not need to
     * hold the full 200MB byte array in memory at once.</p>
     */
    private void triggerOom(int port) {
        int bodySizeMb = 200;
        byte[] chunk = new byte[1024 * 1024];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = 'A';
        }
        String prefix = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"verify_claim\",\"arguments\":{\"nlClaim\":\"";
        String suffix = "\"}}}";

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(
                "http://127.0.0.1:" + port + "/mcp").toURL().openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(10_000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            // Disable buffering so chunks are flushed to the socket immediately.
            OutputStream os = conn.getOutputStream();
            os.write(prefix.getBytes(StandardCharsets.UTF_8));
            os.flush();
            for (int i = 0; i < bodySizeMb; i++) {
                os.write(chunk);
                os.flush();
            }
            os.write(suffix.getBytes(StandardCharsets.UTF_8));
            os.flush();
            try {
                conn.getResponseCode();
            } catch (IOException ignored) {
                // Expected — server OOMed before/during response.
            }
        } catch (IOException e) {
            // Expected — server may close the connection abruptly on OOM.
            System.out.println("[ServerKillableAfterOomTest] triggerOom IO (expected): "
                + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void drainAsync(Process process) {
        Thread t = new Thread(() -> {
            try (var is = process.getInputStream()) {
                byte[] buf = new byte[4096];
                while (is.read(buf) != -1) {
                    // Discard — we only care about process exit.
                }
            } catch (IOException ignored) {
                // Process died; drain exits.
            }
        }, "oom-test-drain");
        t.setDaemon(true);
        t.start();
    }

    private void deleteIfExistsQuiet(Path path) {
        try {
            if (Files.exists(path)) {
                Files.delete(path);
            }
        } catch (IOException ignored) {
            // Best-effort cleanup; do not fail the test on cleanup errors.
        }
    }
}
