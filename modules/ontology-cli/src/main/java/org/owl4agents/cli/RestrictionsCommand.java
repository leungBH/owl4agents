package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.ClassRestrictionsResult;
import org.owl4agents.core.model.ClassRestriction;

/**
 * CLI command for getting class restrictions.
 */
@Command(name = "restrictions", description = "Get class restrictions (someValuesFrom, allValuesFrom, cardinality, etc.).")
public class RestrictionsCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--class"}, required = true, description = "Class IRI")
    private String classIri;

    @Option(names = {"--include-inferred"}, description = "Include inferred restrictions")
    private boolean includeInferred = false;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        OntologyId ontId = new OntologyId(ontologyId);

        ServiceResult<ClassRestrictionsResult> result = factory.getSemanticDeepeningService()
            .getClassRestrictions(ontId, classIri, includeInferred);

        if (result.isSuccess()) {
            ClassRestrictionsResult data = ((ServiceResult.Success<ClassRestrictionsResult>) result).data();
            System.out.println("Class restrictions for '" + classIri + "' in ontology '" + ontologyId + "':");
            System.out.println("  Restriction count: " + data.restrictions().size());
            for (ClassRestriction r : data.restrictions()) {
                System.out.println("  - Type: " + r.restrictionType());
                System.out.println("    On property: " + r.onProperty());
                if (r.filler() != null) {
                    System.out.println("    Filler: " + r.filler());
                }
                if (r.cardinality() != null) {
                    System.out.println("    Cardinality: " + r.cardinality());
                }
                System.out.println("    Source: " + r.source());
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<ClassRestrictionsResult>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}