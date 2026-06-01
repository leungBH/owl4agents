package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.DatatypeConstraintsResult;
import org.owl4agents.core.model.DatatypeFacet;

/**
 * CLI command for getting datatype constraints.
 */
@Command(name = "datatype-constraints", description = "Get datatype facet constraints for a given datatype URI.")
public class DatatypeConstraintsCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--datatype"}, required = true, description = "Datatype IRI")
    private String datatypeIri;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        OntologyId ontId = new OntologyId(ontologyId);

        ServiceResult<DatatypeConstraintsResult> result = factory.getSemanticDeepeningService()
            .getDatatypeConstraints(ontId, datatypeIri);

        if (result.isSuccess()) {
            DatatypeConstraintsResult data = ((ServiceResult.Success<DatatypeConstraintsResult>) result).data();
            System.out.println("Datatype constraints for '" + datatypeIri + "' in ontology '" + ontologyId + "':");
            System.out.println("  Base datatype: " + (data.baseDatatypeIRI() != null ? data.baseDatatypeIRI() : "(none)"));
            System.out.println("  Facet constraints:");
            for (DatatypeFacet facet : data.facets()) {
                System.out.println("    " + facet.facetType() + ": " + facet.facetValue());
            }
            if (data.facets().isEmpty()) {
                System.out.println("    (No facet constraints defined)");
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<DatatypeConstraintsResult>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}