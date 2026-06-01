package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.Optional;
import java.util.Map;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.EntailmentResult;

/**
 * CLI command for entailment checking.
 */
@Command(name = "entailment", description = "Check whether a structured axiom is entailed by the ontology.")
public class EntailmentCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--axiom-type"}, required = true, description = "Axiom type (e.g. SubClassOf, ClassAssertion)")
    private String axiomType;

    @Option(names = {"--subclass"}, description = "Subclass IRI (for SubClassOf)")
    private String subclass;

    @Option(names = {"--superclass"}, description = "Superclass IRI (for SubClassOf)")
    private String superclass;

    @Option(names = {"--reasoner"}, description = "Reasoner name (default: auto)")
    private String reasoner = "auto";

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        OntologyId ontId = new OntologyId(ontologyId);

        Map<String, String> parameters = new java.util.LinkedHashMap<>();
        if (subclass != null) parameters.put("subclass", subclass);
        if (superclass != null) parameters.put("superclass", superclass);

        ServiceResult<EntailmentResult> result = factory.getReasonerService()
            .checkEntailment(ontId, axiomType, parameters, Optional.of(reasoner));

        if (result.isSuccess()) {
            EntailmentResult data = ((ServiceResult.Success<EntailmentResult>) result).data();
            System.out.println("Entailment check for ontology '" + ontologyId + "':");
            System.out.println("  Axiom type: " + data.axiomType());
            System.out.println("  Result: " + data.result());
            if (data.source() != null) {
                System.out.println("  Source: " + data.source());
            }
            if (data.reasonerName() != null) {
                System.out.println("  Reasoner: " + data.reasonerName());
            }
            if (data.evidence() != null) {
                System.out.println("  Evidence: " + data.evidence());
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<EntailmentResult>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}