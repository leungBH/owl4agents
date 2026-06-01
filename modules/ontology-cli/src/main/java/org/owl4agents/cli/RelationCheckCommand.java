package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.Optional;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.RelationAssertionResult;

/**
 * CLI command for relation assertion checking.
 */
@Command(name = "relation-check", description = "Check whether an object property relation is asserted between two individuals.")
public class RelationCheckCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--source"}, required = true, description = "Source individual IRI")
    private String sourceIri;

    @Option(names = {"--property"}, required = true, description = "Object property IRI")
    private String propertyIri;

    @Option(names = {"--target"}, required = true, description = "Target individual IRI")
    private String targetIri;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        OntologyId ontId = new OntologyId(ontologyId);

        ServiceResult<RelationAssertionResult> result = factory.getConsistencyAnalysisService()
            .checkRelationAssertion(ontId, sourceIri, propertyIri, targetIri, Optional.empty());

        if (result.isSuccess()) {
            RelationAssertionResult data = ((ServiceResult.Success<RelationAssertionResult>) result).data();
            System.out.println("Relation assertion check for ontology '" + ontologyId + "':");
            System.out.println("  Source individual: " + data.sourceIndividualIRI());
            System.out.println("  Property: " + data.propertyIRI());
            System.out.println("  Target individual: " + data.targetIndividualIRI());
            System.out.println("  Is asserted: " + data.isAsserted());
            System.out.println("  Assertion type: " + (data.assertionType() != null ? data.assertionType() : "none"));
            if (data.reasonerName() != null) {
                System.out.println("  Reasoner: " + data.reasonerName());
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<RelationAssertionResult>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}