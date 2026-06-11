package org.owl4agents.validation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP example validation tests (task 4.5).
 *
 * Validates MCP readonly startup, initialize response, and tools/list response
 * within 30 seconds. This covers V04-EX-004 (agent-mcp example).
 *
 * Uses bounded child-process startup with clean termination.
 * Live MCP tool-call validation is optional (task 4.6).
 */
@DisplayName("v0.4 MCP example validation")
class McpExampleValidationTest {

    private static final Path PROJECT_ROOT = findProjectRoot();
    private static final long MCP_TIMEOUT_SECONDS = 30;
    private static boolean jarAvailable;
    private static boolean nodeAvailable;

    private static Path findProjectRoot() {
        String userDir = System.getProperty("user.dir");
        Path cwd = userDir != null ? Path.of(userDir).toAbsolutePath() : Path.of("").toAbsolutePath();
        Path dir = cwd;
        for (int i = 0; i < 10 && dir != null; i++) {
            if (Files.exists(dir.resolve("build.gradle.kts")) && Files.exists(dir.resolve("test"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return cwd;
    }

    @BeforeAll
    static void checkPrerequisites() throws Exception {
        Path jarPath = PROJECT_ROOT.resolve("modules/ontology-cli/build/libs/owl4agents.jar");
        jarAvailable = Files.exists(jarPath);

        try {
            ProcessBuilder pb = new ProcessBuilder("node", "--version");
            pb.directory(PROJECT_ROOT.toFile());
            Process p = pb.start();
            boolean exited = p.waitFor(10, TimeUnit.SECONDS);
            nodeAvailable = exited && p.exitValue() == 0;
        } catch (Exception e) {
            nodeAvailable = false;
        }
    }

    static boolean prerequisitesAvailable() {
        return jarAvailable && nodeAvailable;
    }

    @Nested
    @DisplayName("MCP startup and tool list (V04-EX-004)")
    class McpStartupTests {

        @BeforeAll
        static void checkPrereqs() {
            Assumptions.assumeTrue(prerequisitesAvailable(),
                "Skipping MCP startup tests — shadow jar or Node.js not available");
        }

        @Test
        @DisplayName("MCP readonly starts and returns initialize within timeout")
        void mcpInitializeWithinTimeout() throws Exception {
            ProcessBuilder pb = new ProcessBuilder("node", "tools/npm/bin/owl4agents.js", "mcp");
            pb.directory(PROJECT_ROOT.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Send initialize request via stdin (JSON-RPC)
            String initializeRequest = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"v04-validation\",\"version\":\"1.0.0\"}}}\n";
            process.getOutputStream().write(initializeRequest.getBytes());
            process.getOutputStream().flush();

            // Read response with timeout
            StringBuilder output = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            long deadline = System.currentTimeMillis() + MCP_TIMEOUT_SECONDS * 1000;
            while (System.currentTimeMillis() < deadline) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    output.append(line).append("\n");
                    // Check if we got the initialize response
                    if (line.contains("serverInfo") || line.contains("capabilities")) {
                        break;
                    }
                }
            }

            // Terminate the MCP process
            process.destroy();
            boolean terminated = process.waitFor(5, TimeUnit.SECONDS);
            if (!terminated) {
                process.destroyForcibly();
            }

            String response = output.toString();
            assertFalse(response.isEmpty(), "MCP must produce output within " + MCP_TIMEOUT_SECONDS + " seconds");
            assertTrue(response.contains("serverInfo"), "Initialize response must contain serverInfo");
            assertTrue(response.contains("owl4agents"), "serverInfo.name must contain 'owl4agents'");
            assertTrue(response.contains("capabilities"), "Initialize response must contain capabilities");
            assertFalse(response.contains("ACCESS_VIOLATION"), "MCP output must not contain crash text");
            assertFalse(response.contains("Unhandled exception"), "MCP output must not contain exception text");
        }

        @Test
        @DisplayName("MCP tools/list returns non-empty tool list")
        void mcpToolsListNonEmpty() throws Exception {
            ProcessBuilder pb = new ProcessBuilder("node", "tools/npm/bin/owl4agents.js", "mcp");
            pb.directory(PROJECT_ROOT.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Send initialize
            String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"v04-validation\",\"version\":\"1.0.0\"}}}\n";
            process.getOutputStream().write(init.getBytes());
            process.getOutputStream().flush();

            // Read initialize response
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            long deadline = System.currentTimeMillis() + MCP_TIMEOUT_SECONDS * 1000;
            StringBuilder output = new StringBuilder();

            while (System.currentTimeMillis() < deadline) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    output.append(line).append("\n");
                    if (line.contains("serverInfo")) break;
                }
            }

            // Send initialized notification (required by MCP protocol)
            String initialized = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n";
            process.getOutputStream().write(initialized.getBytes());
            process.getOutputStream().flush();

            // Send tools/list request
            String toolsList = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}\n";
            process.getOutputStream().write(toolsList.getBytes());
            process.getOutputStream().flush();

            // Read tools/list response
            output.setLength(0);
            deadline = System.currentTimeMillis() + MCP_TIMEOUT_SECONDS * 1000;
            while (System.currentTimeMillis() < deadline) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    output.append(line).append("\n");
                    if (line.contains("ontology_verify_claim")) break;
                }
            }

            // Terminate
            process.destroy();
            boolean terminated = process.waitFor(5, TimeUnit.SECONDS);
            if (!terminated) process.destroyForcibly();

            String response = output.toString();
            // Assert explicit readonly tool names (ontology_import is a v0.8 write tool, not in READONLY_TOOLS)
            assertTrue(response.contains("ontology_verify_claim"),
                "tools/list must contain ontology_verify_claim tool. Response: " + response);
            assertTrue(response.contains("ontology_summary"),
                "tools/list must contain ontology_summary tool. Response: " + response);
            assertTrue(response.contains("ontology_classify"),
                "tools/list must contain ontology_classify tool. Response: " + response);
            assertTrue(response.contains("ontology_get_evidence_path"),
                "tools/list must contain ontology_get_evidence_path tool. Response: " + response);
            assertFalse(response.contains("ontology_import"),
                "tools/list must not expose ontology_import in readonly mode");
            assertFalse(response.contains("ACCESS_VIOLATION"),
                "tools/list response must not contain crash text");
        }
    }

    // --- Sanitized transcript validation (V04-EX-004 transcript check) ---

    @Nested
    @DisplayName("MCP transcript validation")
    class McpTranscriptValidationTests {

        @Test
        @DisplayName("Committed MCP transcript is sanitized")
        void mcpTranscriptSanitized() {
            Path transcript = PROJECT_ROOT.resolve("examples/agent-mcp/transcripts/verify-claim-transcript.md");
            assertTrue(Files.exists(transcript), "MCP transcript must exist: " + transcript);
            String content = readFileSafe(transcript);
            assertFalse(content.isEmpty(), "MCP transcript must be non-empty");

            // Verify no private content
            assertFalse(content.contains("C:\\Users"), "Transcript must not contain Windows user paths");
            assertFalse(content.matches(".*[A-Z]:\\\\Users\\\\.*"), "Transcript must not contain absolute Windows paths");
            assertFalse(content.contains("/home/"), "Transcript must not contain Unix home paths");
            assertFalse(content.contains("/Users/"), "Transcript must not contain macOS home paths");
            assertFalse(content.contains("Bearer "), "Transcript must not contain Bearer tokens");
            assertFalse(content.toLowerCase().contains("password"), "Transcript must not contain passwords");
            assertFalse(content.toLowerCase().contains("secret"), "Transcript must not contain secrets");
        }

        @Test
        @DisplayName("MCP transcript contains documented readonly tool names")
        void mcpTranscriptContainsToolNames() {
            Path transcript = PROJECT_ROOT.resolve("examples/agent-mcp/transcripts/verify-claim-transcript.md");
            String content = readFileSafe(transcript);
            // v0.4 readonly tools — ontology_import is a v0.8 write tool, not expected in transcript tools/list
            assertTrue(content.contains("ontology_verify_claim"),
                "Transcript must mention ontology_verify_claim tool");
            assertTrue(content.contains("ontology_summary"),
                "Transcript must mention ontology_summary tool");
            assertTrue(content.contains("ontology_classify"),
                "Transcript must mention ontology_classify tool");
            assertFalse(content.contains("ontology_import"),
                "Transcript must NOT contain ontology_import (v0.8 write tool, not in readonly tools/list)");
        }

        @Test
        @DisplayName("MCP transcript contains initialize response")
        void mcpTranscriptContainsInitialize() {
            Path transcript = PROJECT_ROOT.resolve("examples/agent-mcp/transcripts/verify-claim-transcript.md");
            String content = readFileSafe(transcript);
            assertTrue(content.contains("initialize") || content.contains("Initialize"),
                "Transcript must contain initialize request/response");
            assertTrue(content.contains("owl4agents"),
                "Transcript initialize response must mention owl4agents serverInfo");
        }
    }

    private String readFileSafe(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            return "";
        }
    }
}