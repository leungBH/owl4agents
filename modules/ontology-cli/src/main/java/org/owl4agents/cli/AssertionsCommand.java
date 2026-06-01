package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.PropertyAxiomsResult;

/**
 * CLI command for getting property assertions for an individual.
 */
@Command(name = "assertions", description = "Get property assertions for an individual (object or data).")
public class AssertionsCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--individual"}, required = true, description = "Individual IRI")
    private String individualIri;

    @Option(names = {"--type"}, required = true, description = "Assertion type: object or data")
    private String assertionType;

    @Option(names = {"--include-inferred"}, description = "Include inferred assertions")
    private boolean includeInferred = false;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        OntologyId ontId = new OntologyId(ontologyId);

        ServiceResult<PropertyAxiomsResult> result;

        if ("object".equalsIgnoreCase(assertionType)) {
            result = factory.getSemanticDeepeningService()
                .getObjectPropertyAssertions(ontId, individualIri, includeInferred);
        } else if ("data".equalsIgnoreCase(assertionType)) {
            result = factory.getSemanticDeepeningService()
                .getDataPropertyAssertions(ontId, individualIri, includeInferred);
        } else {
            System.err.println("Error: Invalid assertion type '" + assertionType + "'. Use 'object' or 'data'.");
            return 1;
        }

        if (result.isSuccess()) {
            PropertyAxiomsResult data = ((ServiceResult.Success<PropertyAxiomsResult>) result).data();
            System.out.println("Assertions for '" + individualIri + "' in ontology '" + ontologyId + "':");
            System.out.println("  Property type: " + data.propertyType());
            System.out.println("  Relation type: " + data.relationType());
            System.out.println("  Source: " + data.source());
            System.out.println("  Assertions:");
            for (String assertion : data.relatedPropertyIRIs()) {
                System.out.println("    - " + assertion);
            }
            if (data.relatedPropertyIRIs().isEmpty()) {
                System.out.println("    (No assertions found)");
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<PropertyAxiomsResult>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}