package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * CLI command for workspace initialization.
 */
@Command(name = "init", description = "Initialize a local workspace.")
public class InitCommand implements Callable<Integer> {

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        var result = factory.getWorkspaceInitializer().initializeIdempotent(
            new org.owl4agents.core.WorkspaceId(workspaceName));

        if (result.isSuccess()) {
            System.out.println("Workspace '" + workspaceName + "' initialized successfully.");
            return 0;
        } else {
            var error = ((org.owl4agents.core.ServiceResult.Error<Void>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}