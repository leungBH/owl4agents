package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.ClassCompatibilityResult;

/**
 * CLI command for class compatibility checking.
 */
@Command(name = "compatibility", description = "Check whether two classes are compatible, disjoint, or unsatisfiable together.")
public class CompatibilityCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--class1"}, required = true, description = "First class IRI")
    private String class1Iri;

    @Option(names = {"--class2"}, required = true, description = "Second class IRI")
    private String class2Iri;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        OntologyId ontId = new OntologyId(ontologyId);

        ServiceResult<ClassCompatibilityResult> result = factory.getConsistencyAnalysisService()
            .checkClassCompatibility(ontId, class1Iri, class2Iri);

        if (result.isSuccess()) {
            ClassCompatibilityResult data = ((ServiceResult.Success<ClassCompatibilityResult>) result).data();
            System.out.println("Class compatibility for ontology '" + ontologyId + "':");
            System.out.println("  Class1: " + data.class1IRI());
            System.out.println("  Class2: " + data.class2IRI());
            System.out.println("  Compatibility: " + data.compatibility());
            if (data.reasonerName() != null) {
                System.out.println("  Reasoner: " + data.reasonerName());
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<ClassCompatibilityResult>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}