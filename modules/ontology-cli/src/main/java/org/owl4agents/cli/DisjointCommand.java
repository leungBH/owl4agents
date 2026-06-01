package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.PropertyAxiomsResult;

/**
 * CLI command for getting disjoint properties.
 */
@Command(name = "disjoint", description = "Get disjoint property axioms for a given property.")
public class DisjointCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--property"}, required = true, description = "Property IRI")
    private String propertyIri;

    @Option(names = {"--include-inferred"}, description = "Include inferred disjoint properties")
    private boolean includeInferred = false;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        OntologyId ontId = new OntologyId(ontologyId);

        ServiceResult<PropertyAxiomsResult> result = factory.getSemanticDeepeningService()
            .getDisjointProperties(ontId, propertyIri, includeInferred);

        if (result.isSuccess()) {
            PropertyAxiomsResult data = ((ServiceResult.Success<PropertyAxiomsResult>) result).data();
            System.out.println("Disjoint properties for '" + propertyIri + "' in ontology '" + ontologyId + "':");
            System.out.println("  Property type: " + data.propertyType());
            System.out.println("  Relation type: " + data.relationType());
            System.out.println("  Source: " + data.source());
            System.out.println("  Disjoint properties: " + data.relatedPropertyIRIs());
            if (data.relatedPropertyIRIs().isEmpty()) {
                System.out.println("    (No disjoint properties found)");
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<PropertyAxiomsResult>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}