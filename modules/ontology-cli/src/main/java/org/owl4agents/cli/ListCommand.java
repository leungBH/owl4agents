package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * CLI command for listing imported ontologies.
 */
@Command(name = "list", description = "List imported ontologies in the workspace.")
public class ListCommand implements Callable<Integer> {

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        var result = factory.getCatalogStore().readCatalog(
            new org.owl4agents.core.WorkspaceId(workspaceName));

        if (result.isSuccess()) {
            var entries = ((org.owl4agents.core.ServiceResult.Success<java.util.List<
                org.owl4agents.core.model.CatalogEntry>>) result).data();

            if (entries.isEmpty()) {
                System.out.println("No ontologies imported in workspace '" + workspaceName + "'.");
            } else {
                System.out.println("Ontologies in workspace '" + workspaceName + "':");
                for (var entry : entries) {
                    System.out.println("  " + entry.ontologyId().id() +
                        " - " + entry.displayName() +
                        " (imported: " + entry.importTimestamp() + ")");
                }
            }
            return 0;
        } else {
            var error = ((org.owl4agents.core.ServiceResult.Error) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}