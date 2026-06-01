package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.Optional;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.ClassificationResult;
import org.owl4agents.core.model.InferredHierarchyEntry;

/**
 * CLI command for ontology classification.
 */
@Command(name = "classify", description = "Classify ontology: compute inferred class hierarchy.")
public class ClassifyCommand implements Callable<Integer> {

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

        ServiceResult<ClassificationResult> result = factory.getReasonerService()
            .classify(ontId, Optional.of(reasoner));

        if (result.isSuccess()) {
            ClassificationResult data = ((ServiceResult.Success<ClassificationResult>) result).data();
            System.out.println("Classification result for ontology '" + ontologyId + "':");
            System.out.println("  Reasoner: " + data.reasonerName());
            System.out.println("  Complete hierarchy entries: " + data.completeHierarchy().size());
            System.out.println("  Delta (new inferred) entries: " + data.delta().size());
            System.out.println();
            System.out.println("Inferred SubClassOf relationships (delta):");
            for (InferredHierarchyEntry entry : data.delta()) {
                System.out.println("  " + entry.subjectIRI() + " -> " + entry.objectIRI()
                    + " (source: " + entry.source() + ", reasoner: " + entry.reasoner() + ")");
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<ClassificationResult>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}