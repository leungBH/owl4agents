package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.ReasonerListResult;
import org.owl4agents.core.model.ReasonerCapability;

/**
 * CLI command for listing available reasoner adapters.
 */
@Command(name = "list-reasoners", description = "List available reasoner adapters and their capabilities.")
public class ListReasonersCommand implements Callable<Integer> {

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);

        ServiceResult<ReasonerListResult> result = factory.getReasonerService().listReasoners();

        if (result.isSuccess()) {
            ReasonerListResult data = ((ServiceResult.Success<ReasonerListResult>) result).data();
            System.out.println("Available reasoner adapters:");
            for (ReasonerCapability cap : data.reasoners()) {
                System.out.println("  " + cap.name());
                System.out.println("    Supported profiles: " + cap.supportedProfiles());
                System.out.println("    Supported operations: " + cap.supportedOperations());
                System.out.println("    Explanation supported: " + cap.explanationSupported());
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<ReasonerListResult>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}