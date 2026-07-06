package org.owl4agents.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit tests for McpConfigCommand — structural tests for mcp-config JSON
 * generation and validation.
 *
 * Covers: generic/claude/cursor client configs, unknown-client rejection,
 * --workspace-home propagation, --out file output, structural validation
 * (no placeholders, no empty command, no --allow-write), and JSON parseability.
 */
@DisplayName("MCP config command tests")
class McpConfigCommandTest {

    private ByteArrayOutputStream capturedOut;
    private ByteArrayOutputStream capturedErr;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void captureStreams() {
        originalOut = System.out;
        originalErr = System.err;
        capturedOut = new ByteArrayOutputStream();
        capturedErr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut));
        System.setErr(new PrintStream(capturedErr));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    /** Populates a McpConfigCommand's picocli-annotated fields from CLI arg tokens. */
    private McpConfigCommand parseAndCreate(String... args) {
        McpConfigCommand cmd = new McpConfigCommand();
        new CommandLine(cmd).parseArgs(args);
        return cmd;
    }

    /** Parses a JSON string into a Map using the project's GsonFactory. */
    private Map<String, Object> parseJson(String json) {
        return GsonFactory.createGson().fromJson(json, Map.class);
    }

    /** Navigates parsed JSON map to the owl4agents server config. */
    private Map<String, Object> extractServerConfig(Map<String, Object> root) {
        Map<String, Object> servers = (Map<String, Object>) root.get("mcpServers");
        return (Map<String, Object>) servers.get("owl4agents");
    }

    // --- 1. Generic client config ---

    @Nested
    @DisplayName("Generic client config")
    class GenericClientTests {

        @Test
        @DisplayName("--client generic generates valid JSON with command, args, env fields")
        void genericClientGeneratesValidJson() {
            McpConfigCommand cmd = parseAndCreate("--client", "generic", "--workspace-home", "/test/home");
            assertEquals(0, cmd.call());

            String json = capturedOut.toString().trim();
            Map<String, Object> root = parseJson(json);
            Map<String, Object> server = extractServerConfig(root);

            assertEquals("node", server.get("command"));
            List<String> args = (List<String>) server.get("args");
            assertNotNull(args);
            assertTrue(args.contains("mcp"), "args should contain 'mcp'");
            assertTrue(args.contains("--readonly"), "args should contain '--readonly'");

            Map<String, Object> env = (Map<String, Object>) server.get("env");
            assertNotNull(env);
            assertEquals("/test/home", env.get("OWL4AGENTS_HOME"));
        }
    }

    // --- 2. Claude client config ---

    @Nested
    @DisplayName("Claude client config")
    class ClaudeClientTests {

        @Test
        @DisplayName("--client claude generates valid JSON with source-checkout launcher")
        void claudeClientGeneratesValidJson() {
            McpConfigCommand cmd = parseAndCreate("--client", "claude", "--workspace-home", "/claude/home");
            assertEquals(0, cmd.call());

            String json = capturedOut.toString().trim();
            Map<String, Object> root = parseJson(json);
            Map<String, Object> server = extractServerConfig(root);

            assertEquals("node", server.get("command"));
            List<String> args = (List<String>) server.get("args");
            String launcherPath = args.get(0);
            assertTrue(launcherPath.endsWith("owl4agents.js"),
                "Launcher path should reference owl4agents.js source checkout");
            assertTrue(args.contains("mcp"));
            assertTrue(args.contains("--readonly"));
        }
    }

    // --- 3. Cursor client config ---

    @Nested
    @DisplayName("Cursor client config")
    class CursorClientTests {

        @Test
        @DisplayName("--client cursor generates valid JSON")
        void cursorClientGeneratesValidJson() {
            McpConfigCommand cmd = parseAndCreate("--client", "cursor", "--workspace-home", "/cursor/home");
            assertEquals(0, cmd.call());

            String json = capturedOut.toString().trim();
            Map<String, Object> root = parseJson(json);
            Map<String, Object> server = extractServerConfig(root);

            assertEquals("node", server.get("command"));
            assertNotNull(server.get("args"));
            assertNotNull(server.get("env"));
        }
    }

    // --- 4. Unknown client rejection ---

    @Nested
    @DisplayName("Unknown client rejection")
    class UnknownClientTests {

        @Test
        @DisplayName("--client unknown exits nonzero and prints supported-client diagnostics")
        void unknownClientExitsNonzero() {
            McpConfigCommand cmd = parseAndCreate("--client", "unknown");
            assertEquals(1, cmd.call());

            String stderr = capturedErr.toString();
            assertTrue(stderr.contains("Unsupported client 'unknown'"),
                "stderr should mention the rejected client name");
            assertTrue(stderr.contains("Supported clients:"),
                "stderr should list supported clients");
            assertTrue(stderr.contains("generic"));
            assertTrue(stderr.contains("claude"));
            assertTrue(stderr.contains("cursor"));
            assertTrue(stderr.contains("http"),
                "stderr should list http as a supported client");
            assertTrue(stderr.contains("trae"),
                "stderr should list trae as a supported client");
        }
    }

    // --- 5. Workspace home propagation ---

    @Nested
    @DisplayName("Workspace home propagation")
    class WorkspaceHomeTests {

        @Test
        @DisplayName("--workspace-home propagates OWL4AGENTS_HOME in generated JSON env")
        void workspaceHomePropagatesToEnv() {
            String customHome = "/custom/owl4agents/workspace";
            McpConfigCommand cmd = parseAndCreate("--client", "generic", "--workspace-home", customHome);
            assertEquals(0, cmd.call());

            String json = capturedOut.toString().trim();
            Map<String, Object> root = parseJson(json);
            Map<String, Object> env = (Map<String, Object>) extractServerConfig(root).get("env");

            assertEquals(customHome, env.get("OWL4AGENTS_HOME"));
        }
    }

    // --- 6. File output ---

    @Nested
    @DisplayName("File output")
    class FileOutputTests {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("--out writes JSON to a file")
        void outFileWritesJson() throws Exception {
            Path outFile = tempDir.resolve("mcp-config.json");
            McpConfigCommand cmd = parseAndCreate("--client", "generic",
                "--workspace-home", "/test/home", "--out", outFile.toString());
            assertEquals(0, cmd.call());

            assertTrue(Files.exists(outFile), "Output file should be created");
            String fileContent = Files.readString(outFile).trim();
            Map<String, Object> root = parseJson(fileContent);
            assertTrue(root.containsKey("mcpServers"), "File content should be valid MCP config JSON");

            String stdout = capturedOut.toString();
            assertTrue(stdout.contains("Config written to"), "stdout should confirm file write location");
        }

        @Test
        @DisplayName("Default (no --out) writes JSON to stdout only")
        void defaultWritesToStdout() {
            McpConfigCommand cmd = parseAndCreate("--client", "generic", "--workspace-home", "/test/home");
            assertEquals(0, cmd.call());

            String stdout = capturedOut.toString().trim();
            Map<String, Object> root = parseJson(stdout);
            assertTrue(root.containsKey("mcpServers"), "stdout should contain valid MCP config JSON");
        }
    }

    // --- 7. Structural validation ---

    @Nested
    @DisplayName("Structural validation")
    class StructuralValidationTests {

        @Test
        @DisplayName("Generated JSON contains no placeholders, no empty command, no --allow-write")
        void noPlaceholdersOrEmptyCommandOrAllowWrite() {
            McpConfigCommand cmd = parseAndCreate("--client", "generic", "--workspace-home", "/test/home");
            assertEquals(0, cmd.call());

            String json = capturedOut.toString().trim();

            // No placeholders
            assertFalse(json.contains("TODO"), "JSON should not contain TODO placeholders");
            assertFalse(json.contains("PLACEHOLDER"), "JSON should not contain PLACEHOLDER markers");

            // No empty command
            assertFalse(json.contains("\"command\":\"\""), "JSON should not have an empty command string");

            // No --allow-write flag in args
            assertFalse(json.contains("--allow-write"), "JSON args should not contain --allow-write flag");

            // Verify command is non-empty via parsed structure
            Map<String, Object> root = parseJson(json);
            Map<String, Object> server = extractServerConfig(root);
            String command = (String) server.get("command");
            assertFalse(command.isEmpty(), "command field should not be empty");
        }

        @Test
        @DisplayName("Claude config also has no --allow-write flag")
        void claudeConfigNoAllowWrite() {
            McpConfigCommand cmd = parseAndCreate("--client", "claude", "--workspace-home", "/test/home");
            assertEquals(0, cmd.call());

            String json = capturedOut.toString().trim();
            assertFalse(json.contains("--allow-write"), "Claude config args should not contain --allow-write");
        }
    }

    // --- 8. JSON validity ---

    @Nested
    @DisplayName("JSON validity")
    class JsonValidityTests {

        @Test
        @DisplayName("JSON output is valid parseable JSON")
        void jsonOutputIsParseable() {
            McpConfigCommand cmd = parseAndCreate("--client", "claude", "--workspace-home", "/test/home");
            assertEquals(0, cmd.call());

            String json = capturedOut.toString().trim();
            Map<String, Object> parsed = parseJson(json);
            assertNotNull(parsed, "Parsed JSON should not be null");
            assertFalse(parsed.isEmpty(), "Parsed JSON should not be empty");
            assertTrue(parsed.containsKey("mcpServers"), "Parsed JSON should contain mcpServers key");
        }

        @Test
        @DisplayName("All client types produce parseable JSON")
        void allClientTypesProduceParseableJson() {
            for (String client : List.of("generic", "claude", "cursor", "http", "trae")) {
                capturedOut.reset();
                McpConfigCommand cmd = parseAndCreate("--client", client, "--workspace-home", "/test/home");
                assertEquals(0, cmd.call());

                String json = capturedOut.toString().trim();
                Map<String, Object> parsed = parseJson(json);
                assertNotNull(parsed, client + " client should produce parseable JSON");
                assertTrue(parsed.containsKey("mcpServers"), client + " client JSON should have mcpServers");
            }
        }
    }

    // --- 9. HTTP client config (v0.7+) ---

    @Nested
    @DisplayName("HTTP client config (v0.7+)")
    class HttpClientTests {

        @Test
        @DisplayName("--client http generates a single url field pointing at the HTTP listener")
        void httpClientGeneratesUrl() {
            McpConfigCommand cmd = parseAndCreate("--client", "http");
            assertEquals(0, cmd.call());

            String json = capturedOut.toString().trim();
            Map<String, Object> root = parseJson(json);
            Map<String, Object> server = extractServerConfig(root);

            assertEquals("http://127.0.0.1:8080/mcp", server.get("url"));
            assertNull(server.get("command"),
                "HTTP config should not include a stdio command field");
            assertNull(server.get("args"),
                "HTTP config should not include stdio args");
            assertNull(server.get("env"),
                "HTTP config should not include stdio env");
        }

        @Test
        @DisplayName("--url override takes effect in the generated config")
        void httpClientUrlOverride() {
            McpConfigCommand cmd = parseAndCreate("--client", "http", "--url", "http://remote-host:9000/mcp");
            assertEquals(0, cmd.call());

            String json = capturedOut.toString().trim();
            Map<String, Object> root = parseJson(json);
            Map<String, Object> server = extractServerConfig(root);

            assertEquals("http://remote-host:9000/mcp", server.get("url"));
        }

        @Test
        @DisplayName("Committed fixture (configs/http-mcp-config.json) is field-level identical to the generator default")
        void httpFixtureHasMcpUrl() throws Exception {
            // Generator output for default flags
            McpConfigCommand cmd = parseAndCreate("--client", "http");
            assertEquals(0, cmd.call());
            String generatorJson = capturedOut.toString().trim();
            Map<String, Object> generatorRoot = parseJson(generatorJson);
            Map<String, Object> generatorServer = extractServerConfig(generatorRoot);

            // Committed fixture (lives next to the agent-mcp example, not in this module's resources).
            // The test JVM's working directory is the module directory, so we walk up
            // to the repository root to resolve the path.
            java.nio.file.Path fixturePath = projectRoot().resolve(
                "examples/agent-mcp/configs/http-mcp-config.json");
            assertTrue(java.nio.file.Files.exists(fixturePath),
                "Committed HTTP fixture must exist: " + fixturePath);
            String fixtureJson = java.nio.file.Files.readString(fixturePath).trim();
            Map<String, Object> fixtureRoot = parseJson(fixtureJson);
            Map<String, Object> fixtureServer = extractServerConfig(fixtureRoot);

            // Field-level contract: server only has "url" and the value points at /mcp
            assertEquals(generatorServer.get("url"), fixtureServer.get("url"),
                "Generator and fixture must agree on the url value");
            assertTrue(((String) fixtureServer.get("url")).endsWith("/mcp"),
                "Fixture url must end with /mcp");
            assertNull(fixtureServer.get("command"),
                "Fixture must not include a stdio command field");
        }
    }

    // --- 10. Trae IDE client config (v0.8+) ---

    @Nested
    @DisplayName("Trae IDE client config (v0.8+)")
    class TraeClientTests {

        @Test
        @DisplayName("--client trae generates a single url field pointing at the HTTP listener (Streamable HTTP transport)")
        void traeClientGeneratesUrl() {
            McpConfigCommand cmd = parseAndCreate("--client", "trae");
            assertEquals(0, cmd.call());

            String json = capturedOut.toString().trim();
            Map<String, Object> root = parseJson(json);
            Map<String, Object> server = extractServerConfig(root);

            assertEquals("http://127.0.0.1:8080/mcp", server.get("url"));
            assertNull(server.get("command"),
                "Trae config should not include a stdio command field");
            assertNull(server.get("args"),
                "Trae config should not include stdio args");
            assertNull(server.get("env"),
                "Trae config should not include stdio env");
        }

        @Test
        @DisplayName("--url override takes effect in the Trae config")
        void traeClientUrlOverride() {
            McpConfigCommand cmd = parseAndCreate("--client", "trae", "--url", "http://remote-host:9000/mcp");
            assertEquals(0, cmd.call());

            String json = capturedOut.toString().trim();
            Map<String, Object> root = parseJson(json);
            Map<String, Object> server = extractServerConfig(root);

            assertEquals("http://remote-host:9000/mcp", server.get("url"));
        }

        @Test
        @DisplayName("Trae config is byte-equivalent to the generic HTTP config (same transport)")
        void traeConfigIsByteEquivalentToHttpConfig() {
            McpConfigCommand trae = parseAndCreate("--client", "trae");
            assertEquals(0, trae.call());
            String traeJson = capturedOut.toString().trim();

            capturedOut.reset();
            McpConfigCommand http = parseAndCreate("--client", "http");
            assertEquals(0, http.call());
            String httpJson = capturedOut.toString().trim();

            // Trae IDE uses the MCP 2025-03-26 Streamable HTTP transport — the
            // same transport as the generic http client — so the configs must
            // be byte-equivalent. The label is for human readability only.
            assertEquals(httpJson, traeJson,
                "Trae config and HTTP config must be byte-equivalent");
        }

        @Test
        @DisplayName("Committed fixture (configs/trae-mcp-config.json) is field-level identical to the generator default")
        void traeFixtureHasMcpUrl() throws Exception {
            // Generator output for default flags
            McpConfigCommand cmd = parseAndCreate("--client", "trae");
            assertEquals(0, cmd.call());
            String generatorJson = capturedOut.toString().trim();
            Map<String, Object> generatorRoot = parseJson(generatorJson);
            Map<String, Object> generatorServer = extractServerConfig(generatorRoot);

            // Committed Trae fixture (lives next to the agent-mcp example, not in this module's resources).
            // The test JVM's working directory is the module directory, so we walk up
            // to the repository root to resolve the path.
            java.nio.file.Path fixturePath = projectRoot().resolve(
                "examples/agent-mcp/configs/trae-mcp-config.json");
            assertTrue(java.nio.file.Files.exists(fixturePath),
                "Committed Trae fixture must exist: " + fixturePath);
            String fixtureJson = java.nio.file.Files.readString(fixturePath).trim();
            Map<String, Object> fixtureRoot = parseJson(fixtureJson);
            Map<String, Object> fixtureServer = extractServerConfig(fixtureRoot);

            // Field-level contract: server only has "url" and the value points at /mcp
            assertEquals(generatorServer.get("url"), fixtureServer.get("url"),
                "Generator and fixture must agree on the url value");
            assertTrue(((String) fixtureServer.get("url")).endsWith("/mcp"),
                "Fixture url must end with /mcp");
            assertNull(fixtureServer.get("command"),
                "Fixture must not include a stdio command field");
        }
    }

    /**
     * Walk up from the test JVM's working directory until we find a
     * directory that contains an {@code examples/} subfolder. The
     * Gradle test task runs from the module directory, so we need to
     * resolve fixtures that live at the repository root relative to
     * the project root, not the module root.
     *
     * <p>Throws {@link IllegalStateException} if the project root
     * cannot be located — that is a fatal test environment error.</p>
     */
    private static java.nio.file.Path projectRoot() {
        java.nio.file.Path cwd = java.nio.file.Path.of("").toAbsolutePath();
        java.nio.file.Path p = cwd;
        for (int i = 0; i < 6 && p != null; i++) {
            if (java.nio.file.Files.isDirectory(p.resolve("examples"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException(
            "Could not locate project root (looked for examples/ from " + cwd + ")");
    }
}