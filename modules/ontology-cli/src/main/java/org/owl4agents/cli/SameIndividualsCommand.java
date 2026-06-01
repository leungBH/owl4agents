package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.PropertyAxiomsResult;

/**
 * CLI command for getting SameAs individuals.
 */
@Command(name = "same-individuals", description = "Get SameAs individuals for a given individual.")
public class SameIndividualsCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--individual"}, required = true, description = "Individual IRI")
    private String individualIri;

    @Option(names = {"--include-inferred"}, description = "Include inferred SameAs individuals")
    private boolean includeInferred = false;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        OntologyId ontId = new OntologyId(ontologyId);

        ServiceResult<PropertyAxiomsResult> result = factory.getSemanticDeepeningService()
            .getSameIndividuals(ontId, individualIri, includeInferred);

        if (result.isSuccess()) {
            PropertyAxiomsResult data = ((ServiceResult.Success<PropertyAxiomsResult>) result).data();
            System.out.println("SameAs individuals for '" + individualIri + "' in ontology '" + ontologyId + "':");
            System.out.println("  Property type: " + data.propertyType());
            System.out.println("  Relation type: " + data.relationType());
            System.out.println("  Source: " + data.source());
            System.out.println("  SameAs individuals:");
            for (String sameAs : data.relatedPropertyIRIs()) {
                System.out.println("    - " + sameAs);
            }
            if (data.relatedPropertyIRIs().isEmpty()) {
                System.out.println("    (No SameAs individuals found)");
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<PropertyAxiomsResult>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}