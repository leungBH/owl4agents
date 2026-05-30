package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * CLI command for importing an ontology file.
 */
@Command(name = "import", description = "Import a local OWL/RDF ontology file.")
public class ImportCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the OWL/RDF ontology file")
    private String filePath;

    @Parameters(index = "1", description = "Ontology ID (short name for the imported ontology)")
    private String ontologyId;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);

        // Ensure workspace is initialized
        factory.getWorkspaceInitializer().initializeIdempotent(
            new org.owl4agents.core.WorkspaceId(workspaceName));

        var result = factory.getOntologyImporter().importOntology(
            new org.owl4agents.core.OntologyId(ontologyId),
            Path.of(filePath),
            new org.owl4agents.core.WorkspaceId(workspaceName));

        if (result.isSuccess()) {
            System.out.println("Ontology '" + ontologyId + "' imported successfully from: " + filePath);
            return 0;
        } else {
            var error = ((org.owl4agents.core.ServiceResult.Error<Void>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            if (!error.details().isEmpty()) {
                System.err.println("Details: " + error.details());
            }
            return 1;
        }
    }
}