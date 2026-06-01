package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.ReasoningReport;

/**
 * CLI command for retrieving the reasoning report.
 */
@Command(name = "report", description = "Get reasoning report for an ontology.")
public class ReportCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        OntologyId ontId = new OntologyId(ontologyId);

        ServiceResult<ReasoningReport> result = factory.getReasonerService().getReasoningReport(ontId);

        if (result.isSuccess()) {
            ReasoningReport report = ((ServiceResult.Success<ReasoningReport>) result).data();
            System.out.println("Reasoning report for ontology '" + ontologyId + "':");
            System.out.println("  Reasoner: " + report.reasonerName());
            System.out.println("  OWL profile: " + report.owlProfile());
            System.out.println("  Classification: " + report.classificationStatus());
            System.out.println("  Realization: " + (report.realizationStatus() != null ? report.realizationStatus() : "n/a"));
            System.out.println("  Consistency: " + report.consistencyStatus());

            ReasoningReport.TimingBreakdown timing = report.timingBreakdown();
            System.out.println("  Timing (ms):");
            System.out.println("    Initialization: " + timing.initializationTimeMs());
            System.out.println("    Classification: " + timing.classificationTimeMs());
            System.out.println("    Realization: " + timing.realizationTimeMs());
            System.out.println("    Total: " + timing.totalTimeMs());

            System.out.println("  Warning count: " + report.warningCount());

            System.out.println("  Inferred axiom counts:");
            for (var entry : report.inferredAxiomCountsByType().entrySet()) {
                System.out.println("    " + entry.getKey() + ": " + entry.getValue());
            }

            if (report.errorDetails() != null) {
                System.out.println("  Error: " + report.errorDetails().errorCode() + " - " + report.errorDetails().message());
            }

            return 0;
        } else {
            var error = ((ServiceResult.Error<ReasoningReport>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}