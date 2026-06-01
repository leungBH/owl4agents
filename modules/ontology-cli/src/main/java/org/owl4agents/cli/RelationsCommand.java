package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.PropertyAxiomsResult;

/**
 * CLI command for finding relations between two entities.
 */
@Command(name = "relations", description = "Find object property relations between two individuals.")
public class RelationsCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--source"}, required = true, description = "Source individual IRI")
    private String sourceIri;

    @Option(names = {"--target"}, required = true, description = "Target individual IRI")
    private String targetIri;

    @Option(names = {"--include-inferred"}, description = "Include inferred relations")
    private boolean includeInferred = false;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        OntologyId ontId = new OntologyId(ontologyId);

        ServiceResult<PropertyAxiomsResult> result = factory.getSemanticDeepeningService()
            .findRelationsBetweenEntities(ontId, sourceIri, targetIri, includeInferred);

        if (result.isSuccess()) {
            PropertyAxiomsResult data = ((ServiceResult.Success<PropertyAxiomsResult>) result).data();
            System.out.println("Relations between '" + sourceIri + "' and '" + targetIri + "' in ontology '" + ontologyId + "':");
            System.out.println("  Property type: " + data.propertyType());
            System.out.println("  Relation type: " + data.relationType());
            System.out.println("  Source: " + data.source());
            System.out.println("  Relations:");
            for (String relation : data.relatedPropertyIRIs()) {
                System.out.println("    - " + relation);
            }
            if (data.relatedPropertyIRIs().isEmpty()) {
                System.out.println("    (No relations found between these entities)");
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<PropertyAxiomsResult>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}