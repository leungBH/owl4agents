package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;

import org.owl4agents.storage.HomeDirectoryResolver;

/**
 * CLI command for MCP client configuration generation.
 * Generates deterministic JSON configuration snippets for known local agent clients.
 * Supports generic, claude, and cursor clients. Codex/codex-cli is deferred.
 */
@Command(name = "mcp-config", description = "Generate MCP client configuration for local agent clients.")
public class McpConfigCommand implements Callable<Integer> {

    private static final java.util.Set<String> SUPPORTED_CLIENTS = java.util.Set.of("generic", "claude", "cursor");

    @Option(names = {"--client"}, required = true, description = "Client name: generic, claude, or cursor")
    private String clientName;

    @Option(names = {"--workspace-home"}, description = "owl4agents home directory for generated config")
    private String workspaceHome;

    @Option(names = {"--out"}, description = "Write config to file instead of stdout")
    private String outputPath;

    @Option(names = {"--workspace"}, description = "Workspace name (default: 'default')")
    private String workspaceName = "default";

    @Option(names = {"--home"}, description = "owl4agents home directory override")
    private String homeDirectory;

    private static final Gson gson = GsonFactory.createGson();

    @Override
    public Integer call() {
        // Reject unknown clients
        if (!SUPPORTED_CLIENTS.contains(clientName.toLowerCase())) {
            System.err.println("Error: Unsupported client '" + clientName + "'.");
            System.err.println("Supported clients: " + String.join(", ", SUPPORTED_CLIENTS));
            return 1;
        }

        // Resolve workspace home
        String effectiveWorkspaceHome;
        if (workspaceHome != null && !workspaceHome.isBlank()) {
            effectiveWorkspaceHome = workspaceHome;
        } else if (homeDirectory != null && !homeDirectory.isBlank()) {
            effectiveWorkspaceHome = homeDirectory;
        } else {
            HomeDirectoryResolver resolver = new HomeDirectoryResolver();
            effectiveWorkspaceHome = resolver.resolveHomeDirectory().toString();
        }

        // Find project root to determine launcher path
        java.nio.file.Path projectRoot = findProjectRoot();
        if (projectRoot == null) {
            System.err.println("Error: Cannot determine project root for MCP configuration generation.");
            System.err.println("Ensure you are running from a valid owl4agents source checkout.");
            return 1;
        }

        // Generate config based on client type
        Map<String, Object> config;
        switch (clientName.toLowerCase()) {
            case "claude":
                config = generateClaudeConfig(projectRoot, effectiveWorkspaceHome);
                break;
            case "cursor":
                config = generateCursorConfig(projectRoot, effectiveWorkspaceHome);
                break;
            case "generic":
                config = generateGenericConfig(projectRoot, effectiveWorkspaceHome);
                break;
            default:
                // Already validated above — this is unreachable
                System.err.println("Error: Unsupported client '" + clientName + "'.");
                return 1;
        }

        String jsonOutput = gson.toJson(config);

        // Validate: no placeholders, no empty command, no write flags
        if (jsonOutput.contains("TODO") || jsonOutput.contains("PLACEHOLDER") ||
            jsonOutput.contains("\"command\":\"\"") || jsonOutput.contains("--allow-write")) {
            System.err.println("Error: Generated config contains invalid content (placeholder, empty command, or write flag).");
            return 1;
        }

        // Output: stdout by default, or file with --out
        if (outputPath != null && !outputPath.isBlank()) {
            try {
                java.nio.file.Path outPath = java.nio.file.Path.of(outputPath);
                java.nio.file.Files.writeString(outPath, jsonOutput);
                System.out.println("Config written to " + outPath);
            } catch (Exception e) {
                System.err.println("Error: Cannot write config to " + outputPath + ": " + e.getMessage());
                return 1;
            }
        } else {
            System.out.println(jsonOutput);
        }

        return 0;
    }

    private Map<String, Object> generateGenericConfig(java.nio.file.Path projectRoot, String workspaceHome) {
        Map<String, Object> config = new LinkedHashMap<>();

        String nodeCmd = isWindows() ? "node" : "node";
        String launcherPath = projectRoot.resolve("npm").resolve("bin").resolve("owl4agents.js").toString();
        // Normalize path separators for the target platform
        if (isWindows()) {
            launcherPath = launcherPath.replace("/", "\\");
        }

        config.put("mcpServers", Map.of(
            "owl4agents", Map.of(
                "command", nodeCmd,
                "args", java.util.List.of(launcherPath, "mcp", "--readonly"),
                "env", Map.of("OWL4AGENTS_HOME", workspaceHome)
            )
        ));
        return config;
    }

    private Map<String, Object> generateClaudeConfig(java.nio.file.Path projectRoot, String workspaceHome) {
        // Claude uses the same format as generic but with specific guidance
        Map<String, Object> config = new LinkedHashMap<>();

        String launcherPath = projectRoot.resolve("npm").resolve("bin").resolve("owl4agents.js").toString();
        if (isWindows()) {
            launcherPath = launcherPath.replace("/", "\\");
        }

        config.put("mcpServers", Map.of(
            "owl4agents", Map.of(
                "command", "node",
                "args", java.util.List.of(launcherPath, "mcp", "--readonly"),
                "env", Map.of("OWL4AGENTS_HOME", workspaceHome)
            )
        ));
        return config;
    }

    private Map<String, Object> generateCursorConfig(java.nio.file.Path projectRoot, String workspaceHome) {
        // Cursor uses the same readonly MCP startup contract as Claude
        Map<String, Object> config = new LinkedHashMap<>();

        String launcherPath = projectRoot.resolve("npm").resolve("bin").resolve("owl4agents.js").toString();
        if (isWindows()) {
            launcherPath = launcherPath.replace("/", "\\");
        }

        config.put("mcpServers", Map.of(
            "owl4agents", Map.of(
                "command", "node",
                "args", java.util.List.of(launcherPath, "mcp", "--readonly"),
                "env", Map.of("OWL4AGENTS_HOME", workspaceHome)
            )
        ));
        return config;
    }

    private java.nio.file.Path findProjectRoot() {
        java.nio.file.Path cwd = java.nio.file.Path.of(System.getProperty("user.dir"));
        java.nio.file.Path dir = cwd;
        for (int i = 0; i < 10; i++) {
            if (java.nio.file.Files.exists(dir.resolve("build.gradle.kts")) &&
                java.nio.file.Files.exists(dir.resolve("settings.gradle.kts"))) {
                return dir;
            }
            dir = dir.getParent();
            if (dir == null) break;
        }
        return null;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }
}