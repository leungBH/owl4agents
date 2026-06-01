package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.Optional;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.RealizationResult;
import org.owl4agents.core.model.InferredTypeEntry;

/**
 * CLI command for ontology realization.
 */
@Command(name = "realize", description = "Realize ontology: compute inferred individual types.")
public class RealizeCommand implements Callable<Integer> {

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

        ServiceResult<RealizationResult> result = factory.getReasonerService()
            .realize(ontId, Optional.of(reasoner));

        if (result.isSuccess()) {
            RealizationResult data = ((ServiceResult.Success<RealizationResult>) result).data();
            System.out.println("Realization result for ontology '" + ontologyId + "':");
            System.out.println("  Reasoner: " + data.reasonerName());
            System.out.println("  Complete type entries: " + data.completeTypes().size());
            System.out.println("  Delta (new inferred) entries: " + data.delta().size());
            System.out.println();
            System.out.println("Inferred individual types (delta):");
            for (InferredTypeEntry entry : data.delta()) {
                System.out.println("  " + entry.subjectIRI() + " : " + entry.objectIRI()
                    + " (source: " + entry.source() + ", reasoner: " + entry.reasoner() + ")");
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<RealizationResult>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}