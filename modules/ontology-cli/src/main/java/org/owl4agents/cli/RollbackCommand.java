package org.owl4agents.cli;

import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * v0.9.1 mcp-write-tools-expansion: rollback CLI command.
 * Discards a transaction's staging ontology and removes the transaction.
 */
@Command(name = "rollback",
    description = "Discard a write transaction and release its staging ontology (v0.9.1).")
public class RollbackCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Parameters(index = "1", description = "Transaction ID (UUID)")
    private String transactionId;

    @Option(names = {"--author"}, description = "Author identity (default: cli)")
    private String author = "cli";

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Option(names = {"--write"}, description = "Enable write mode (required)")
    private boolean writeFlag;

    @Option(names = {"--json"}, description = "Emit JSON output to stdout")
    private boolean json;

    @Override
    public Integer call() {
        boolean writeMode = writeFlag || "write".equalsIgnoreCase(System.getenv("OWL4AGENTS_MODE"));
        if (!writeMode) {
            System.err.println("This command requires --write flag or OWL4AGENTS_MODE=write env var");
            return 2;
        }

        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        ServiceResult<Void> result = factory.getWriteTransactionService()
            .rollback(transactionId, author);

        if (result.isSuccess()) {
            if (json) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "success");
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("transactionId", transactionId);
                data.put("stagedAxiomCount", 0);
                out.put("data", data);
                System.out.println(GsonFactory.createGson().toJson(out));
            } else {
                System.out.println("Rollback successful.");
                System.out.println("  transactionId: " + transactionId);
                System.out.println("  staged operations discarded.");
            }
            return 0;
        } else {
            ServiceError error = ((ServiceResult.Error<Void>) result).error();
            if (json) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "error");
                out.put("error", errorMap(error));
                System.out.println(GsonFactory.createGson().toJson(out));
            } else {
                System.err.println("Error: " + error.code().code() + " - " + error.message());
                if (!error.details().isEmpty()) {
                    System.err.println("Details: " + error.details());
                }
            }
            return 1;
        }
    }

    private static Map<String, Object> errorMap(ServiceError error) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", error.code().code());
        err.put("message", error.message());
        if (!error.details().isEmpty()) {
            err.put("details", error.details());
        }
        return err;
    }
}
