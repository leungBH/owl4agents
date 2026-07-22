package org.owl4agents.reasoner.isolated;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * v0.8.7 D15 test helper: stub child JVM for {@link IsolatedReasonerWorkerTest}.
 *
 * <p>Mimics the JSON-RPC-over-stdio protocol of {@link ReasonerWorkerMain}
 * but with configurable behavior via system properties, so that failure
 * modes (timeout, crash, malformed JSON) can be tested deterministically
 * without depending on real reasoner timing.</p>
 *
 * <h2>Configuration (system properties)</h2>
 * <ul>
 *   <li><code>stub.mode</code> — one of:
 *     <ul>
 *       <li><b>normal</b> (default): responds to all operations with a
 *           synthetic success payload ({@code {"pong":true}} for ping,
 *           {@code {"operation":...,"stub":true}} for others).</li>
 *       <li><b>slow</b>: sleeps 5 seconds before responding, to trigger
 *           the parent's per-call timeout.</li>
 *       <li><b>crash</b>: exits with code 1 after reading the first
 *           request, without responding.</li>
 *       <li><b>malformed</b>: writes an invalid JSON line to stdout
 *           instead of a well-formed response.</li>
 *     </ul>
 *   </li>
 *   <li><code>stub.crash.marker</code> — path to a marker file; if it
 *       exists on startup, the stub deletes it and exits with code 1
 *       immediately (before reading any request). Used for crash-recovery
 *       testing: the first JVM crashes (marker present), the second JVM
 *       succeeds (marker deleted by first).</li>
 * </ul>
 *
 * <p>All logs go to stderr (stdout is reserved for the JSON protocol).</p>
 */
public final class StubWorkerMain {

    private static final long SLOW_DELAY_MS = 5_000L;

    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("stub.mode", "normal");
        String crashMarker = System.getProperty("stub.crash.marker");

        // Crash-marker check (for crash recovery test): if marker file
        // exists, delete it and exit immediately. The next JVM spawn
        // will not find the marker and will respond normally.
        if (crashMarker != null && !crashMarker.isBlank()) {
            Path markerPath = Path.of(crashMarker);
            if (Files.exists(markerPath)) {
                try {
                    Files.delete(markerPath);
                } catch (IOException e) {
                    System.err.println("[stub] failed to delete crash marker: " + e.getMessage());
                }
                System.err.println("[stub] crash marker present — exiting with code 1");
                System.exit(1);
            }
        }

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter writer = new PrintWriter(new BufferedWriter(
            new OutputStreamWriter(System.out, StandardCharsets.UTF_8)), false);

        String line;
        while ((line = reader.readLine()) != null) {
            // Always handle ping immediately with valid JSON, regardless of
            // mode, so the parent's health check succeeds. Only non-ping
            // operations are affected by the mode (slow/malformed/crash).
            if (line.contains("\"ping\"")) {
                writer.println("{\"result\":{\"pong\":true},\"elapsedMs\":0,\"error\":null}");
                writer.flush();
                continue;
            }

            switch (mode) {
                case "slow":
                    Thread.sleep(SLOW_DELAY_MS);
                    writer.println("{\"result\":{\"pong\":true},\"elapsedMs\":"
                        + SLOW_DELAY_MS + ",\"error\":null}");
                    break;
                case "crash":
                    // Already read the request; exit without responding.
                    writer.flush();
                    System.exit(1);
                    break;
                case "malformed":
                    writer.println("this is not valid json {{{");
                    break;
                case "normal":
                default:
                    writer.println(handleNormal(line));
                    break;
            }
            writer.flush();
        }
    }

    private static String handleNormal(String line) {
        try {
            JsonObject req = JsonParser.parseString(line).getAsJsonObject();
            String op = req.has("operation") ? req.get("operation").getAsString() : "unknown";
            JsonObject response = new JsonObject();
            JsonObject result = new JsonObject();
            if ("ping".equals(op)) {
                result.addProperty("pong", true);
            } else {
                result.addProperty("operation", op);
                result.addProperty("stub", true);
            }
            response.add("result", result);
            response.addProperty("elapsedMs", 1L);
            response.add("error", null);
            return response.toString();
        } catch (Exception e) {
            // Best-effort error response
            JsonObject err = new JsonObject();
            err.addProperty("code", "REASONER_INTERNAL_ERROR");
            err.addProperty("message", e.getMessage());
            JsonObject response = new JsonObject();
            response.add("result", null);
            response.addProperty("elapsedMs", 0L);
            response.add("error", err);
            return response.toString();
        }
    }

    private StubWorkerMain() {}
}
