package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.ScopeDescription;

/**
 * CLI command for getting ontology scope description.
 */
@Command(name = "scope", description = "Get ontology scope: covered domains, known gaps, profile limitations.")
public class ScopeCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        OntologyId ontId = new OntologyId(ontologyId);

        ServiceResult<ScopeDescription> result = factory.getConsistencyAnalysisService().getScope(ontId);

        if (result.isSuccess()) {
            ScopeDescription data = ((ServiceResult.Success<ScopeDescription>) result).data();
            System.out.println("Scope description for ontology '" + ontologyId + "':");
            System.out.println("  Covered domains: " + data.coveredDomains());
            System.out.println("  Known gaps: " + data.knownGaps());
            System.out.println("  Profile limitations: " + data.profileLimitations());
            System.out.println("  Unsupported feature types: " + data.unsupportedFeatureTypes());
            return 0;
        } else {
            var error = ((ServiceResult.Error<ScopeDescription>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}