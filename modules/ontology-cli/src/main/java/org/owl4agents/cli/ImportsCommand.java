package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.ImportClosureResult;
import org.owl4agents.core.model.ImportEntry;

/**
 * CLI command for getting import closure.
 */
@Command(name = "imports", description = "Get ontology import closure with metadata.")
public class ImportsCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        OntologyId ontId = new OntologyId(ontologyId);

        ServiceResult<ImportClosureResult> result = factory.getSemanticDeepeningService().getImportClosure(ontId);

        if (result.isSuccess()) {
            ImportClosureResult data = ((ServiceResult.Success<ImportClosureResult>) result).data();
            System.out.println("Import closure for ontology '" + ontologyId + "':");
            System.out.println("  Has cycles: " + data.hasCycles());
            if (!data.cycleWarning().isEmpty()) {
                System.out.println("  Cycle warnings: " + data.cycleWarning());
            }
            System.out.println("  Imports:");
            for (ImportEntry entry : data.imports()) {
                System.out.println("    IRI: " + entry.ontologyIRI());
                if (entry.versionIRI() != null) {
                    System.out.println("      Version: " + entry.versionIRI());
                }
                if (entry.owlProfile() != null) {
                    System.out.println("      Profile: " + entry.owlProfile());
                }
                System.out.println("      Direct: " + entry.isDirect());
                System.out.println("      Transitive: " + entry.isTransitive());
            }
            if (data.imports().isEmpty()) {
                System.out.println("    (No imports found)");
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<ImportClosureResult>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}