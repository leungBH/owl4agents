package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.Optional;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.MembershipResult;

/**
 * CLI command for individual membership checking.
 */
@Command(name = "membership", description = "Check whether an individual belongs to a class (explicit, inferred, or both).")
public class MembershipCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--individual"}, required = true, description = "Individual IRI")
    private String individualIri;

    @Option(names = {"--class"}, required = true, description = "Class IRI")
    private String classIri;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        OntologyId ontId = new OntologyId(ontologyId);

        ServiceResult<MembershipResult> result = factory.getConsistencyAnalysisService()
            .checkIndividualMembership(ontId, individualIri, classIri, Optional.empty());

        if (result.isSuccess()) {
            MembershipResult data = ((ServiceResult.Success<MembershipResult>) result).data();
            System.out.println("Membership check for ontology '" + ontologyId + "':");
            System.out.println("  Individual: " + data.individualIRI());
            System.out.println("  Class: " + data.classIRI());
            System.out.println("  Is member: " + data.isMember());
            System.out.println("  Membership type: " + (data.membershipType() != null ? data.membershipType() : "none"));
            if (data.reasonerName() != null) {
                System.out.println("  Reasoner: " + data.reasonerName());
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<MembershipResult>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}