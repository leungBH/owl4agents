package org.owl4agents.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Root CLI command for owl4agents.
 * Routes all subcommands through the shared OntologyService.
 */
@Command(
    name = "owl4agents",
    mixinStandardHelpOptions = true,
    version = "0.1.0",
    description = "Local OWL ontology reasoning and MCP server for LLM agents.",
    subcommands = {
        InitCommand.class,
        ImportCommand.class,
        ListCommand.class,
        SummaryCommand.class,
        SearchCommand.class,
        EntityCommand.class,
        QueryCommand.class,
        ContextCommand.class,
        McpCommand.class
    }
)
public class Owl4AgentsCli implements Callable<Integer> {

    @Option(names = {"--workspace"}, description = "Workspace name (default: 'default')")
    private String workspaceName = "default";

    @Option(names = {"--home"}, description = "owl4agents home directory override")
    private String homeDirectory;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Owl4AgentsCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        // No subcommand specified — show help
        new CommandLine(this).usage(System.out);
        return 0;
    }

    /**
     * Get the shared service factory based on CLI options.
     */
    CliServiceFactory getServiceFactory() {
        return new CliServiceFactory(workspaceName, homeDirectory);
    }
}
