package org.owl4agents.cli;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * v0.9.1 mcp-write-tools-expansion: merge CLI command.
 * Stages all axioms from a source ontology into a target transaction.
 */
@Command(name = "merge",
    description = "Stage all axioms from a source ontology into a write transaction (v0.9.1).")
public class MergeCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID (target)")
    private String ontologyId;

    @Parameters(index = "1", description = "Transaction ID (UUID)")
    private String transactionId;

    @Parameters(index = "2", description = "Source (registered ontology_id or file_path)")
    private String source;

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
        ServiceResult<Map<String, Object>> result = factory.getOntologyEditService()
            .merge(new OntologyId(ontologyId), transactionId, source, author);

        if (result.isSuccess()) {
            Map<String, Object> data = ((ServiceResult.Success<Map<String, Object>>) result).data();
            if (json) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "success");
                out.put("data", data);
                System.out.println(GsonFactory.createGson().toJson(out));
            } else {
                System.out.println("Merge staged successfully.");
                System.out.println("  transactionId:        " + data.get("transactionId"));
                System.out.println("  source:              " + data.get("source"));
                System.out.println("  sourceKind:          " + data.get("sourceKind"));
                System.out.println("  mergedAxiomCount:    " + data.get("mergedAxiomCount"));
                System.out.println("  stagedAxiomCount:     " + data.get("stagedAxiomCount"));
                System.out.println("  stagedOperationIndex: " + data.get("stagedOperationIndex"));
            }
            return 0;
        } else {
            ServiceError error = ((ServiceResult.Error<Map<String, Object>>) result).error();
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
