package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.Optional;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.ConsistencyResult;

/**
 * CLI command for ontology consistency checking.
 */
@Command(name = "consistency", description = "Check ontology consistency.")
public class ConsistencyCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--reasoner"}, description = "Reasoner name: auto, hermit, elk, or openllet (default: auto)")
    private String reasoner = "auto";

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        OntologyId ontId = new OntologyId(ontologyId);

        ServiceResult<ConsistencyResult> result = factory.getReasonerService()
            .checkConsistency(ontId, Optional.of(reasoner));

        if (result.isSuccess()) {
            ConsistencyResult data = ((ServiceResult.Success<ConsistencyResult>) result).data();
            System.out.println("Consistency check for ontology '" + ontologyId + "':");
            System.out.println("  Reasoner: " + data.reasonerName());
            System.out.println("  Consistent: " + data.consistent());
            if (!data.consistent()) {
                System.out.println("  Unsatisfiable classes: " + data.unsatisfiableClassIRIs());
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<ConsistencyResult>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}