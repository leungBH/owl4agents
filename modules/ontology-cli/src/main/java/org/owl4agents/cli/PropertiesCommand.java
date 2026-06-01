package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.PropertyCharacteristicsResult;

/**
 * CLI command for getting property characteristics.
 */
@Command(name = "properties", description = "Get property characteristics (functional, transitive, symmetric, etc.).")
public class PropertiesCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--property"}, required = true, description = "Property IRI")
    private String propertyIri;

    @Option(names = {"--include-inferred"}, description = "Include inferred characteristics")
    private boolean includeInferred = false;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        OntologyId ontId = new OntologyId(ontologyId);

        ServiceResult<PropertyCharacteristicsResult> result = factory.getSemanticDeepeningService()
            .getPropertyCharacteristics(ontId, propertyIri, includeInferred);

        if (result.isSuccess()) {
            PropertyCharacteristicsResult data = ((ServiceResult.Success<PropertyCharacteristicsResult>) result).data();
            System.out.println("Property characteristics for '" + propertyIri + "' in ontology '" + ontologyId + "':");
            System.out.println("  Property type: " + data.propertyType());
            System.out.println("  Functional: " + data.functional());
            System.out.println("  Inverse functional: " + data.inverseFunctional());
            System.out.println("  Transitive: " + data.transitive());
            System.out.println("  Symmetric: " + data.symmetric());
            System.out.println("  Asymmetric: " + data.asymmetric());
            System.out.println("  Reflexive: " + data.reflexive());
            System.out.println("  Irreflexive: " + data.irreflexive());
            System.out.println("  Source: " + data.source());
            return 0;
        } else {
            var error = ((ServiceResult.Error<PropertyCharacteristicsResult>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}