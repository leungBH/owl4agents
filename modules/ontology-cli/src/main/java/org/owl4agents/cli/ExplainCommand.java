package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.Optional;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.InconsistencyExplanation;
import org.owl4agents.core.model.ConflictingAxiomSet;
import org.owl4agents.core.model.ReasonerMetadata;

/**
 * CLI command for explaining ontology inconsistency.
 */
@Command(name = "explain", description = "Explain ontology inconsistency using reasoner explanation support.")
public class ExplainCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--entity"}, required = true, description = "Entity IRI to explain")
    private String entityIri;

    @Option(names = {"--reasoner"}, description = "Reasoner name for explanation (default: openllet)")
    private String reasoner = "openllet";

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        OntologyId ontId = new OntologyId(ontologyId);

        ServiceResult<InconsistencyExplanation> result = factory.getReasonerService()
            .explainInconsistency(ontId, Optional.of(reasoner));

        if (result.isSuccess()) {
            InconsistencyExplanation explanation = ((ServiceResult.Success<InconsistencyExplanation>) result).data();
            System.out.println("Inconsistency explanation for ontology '" + ontologyId + "':");
            System.out.println("  Explanation count: " + explanation.explanationCount());
            ReasonerMetadata meta = explanation.reasonerMetadata();
            if (meta != null) {
                System.out.println("  Reasoner: " + meta.reasonerName() + " (v" + meta.reasonerVersion() + ")");
                System.out.println("  Reasoning time: " + meta.reasoningTimeMs() + "ms");
            }
            System.out.println();
            System.out.println("Conflicting axiom sets:");
            for (ConflictingAxiomSet axiomSet : explanation.conflictingAxiomSets()) {
                System.out.println("  Set (" + axiomSet.syntaxFormat() + "):");
                for (String desc : axiomSet.axiomDescriptions()) {
                    System.out.println("    - " + desc);
                }
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<InconsistencyExplanation>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}