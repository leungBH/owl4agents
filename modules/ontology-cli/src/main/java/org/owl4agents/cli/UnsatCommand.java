package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.List;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;

/**
 * CLI command for getting unsatisfiable classes.
 */
@Command(name = "unsat", description = "Get unsatisfiable class IRIs from reasoner results.")
public class UnsatCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        OntologyId ontId = new OntologyId(ontologyId);

        ServiceResult<List<String>> result = factory.getReasonerService().getUnsatClasses(ontId);

        if (result.isSuccess()) {
            List<String> unsatClasses = ((ServiceResult.Success<List<String>>) result).data();
            System.out.println("Unsatisfiable classes for ontology '" + ontologyId + "':");
            System.out.println("  Count: " + unsatClasses.size());
            for (String iri : unsatClasses) {
                System.out.println("  - " + iri);
            }
            if (unsatClasses.isEmpty()) {
                System.out.println("  (No unsatisfiable classes found)");
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<List<String>>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}