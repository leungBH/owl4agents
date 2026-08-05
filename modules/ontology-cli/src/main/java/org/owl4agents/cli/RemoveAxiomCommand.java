package org.owl4agents.cli;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.ServiceError;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * v0.9.1 mcp-write-tools-expansion: remove-axiom CLI command.
 * Removes a single axiom from a transaction's staging ontology.
 */
@Command(name = "remove-axiom",
    description = "Remove a single axiom from a write transaction's staging ontology (v0.9.1).")
public class RemoveAxiomCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Parameters(index = "1", description = "Transaction ID (UUID)")
    private String transactionId;

    @Option(names = {"--axiom"}, description = "Inline axiom JSON object")
    private String axiomJson;

    @Option(names = {"--axiom-file"}, description = "Path to a file containing axiom JSON (alternative to --axiom)")
    private String axiomFilePath;

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
        if (axiomJson == null && axiomFilePath == null) {
            System.err.println("Error: either --axiom or --axiom-file is required");
            return 2;
        }
        String raw;
        if (axiomJson != null) {
            raw = axiomJson;
        } else {
            try {
                raw = Files.readString(Path.of(axiomFilePath));
            } catch (java.io.IOException e) {
                System.err.println("Error: cannot read axiom file: " + e.getMessage());
                return 1;
            }
        }
        Object axiomObj = GsonFactory.createGson().fromJson(raw, Object.class);

        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        ServiceResult<Map<String, Object>> result = factory.getOntologyEditService()
            .removeAxiom(new OntologyId(ontologyId), transactionId, axiomObj, author);

        if (result.isSuccess()) {
            Map<String, Object> data = ((ServiceResult.Success<Map<String, Object>>) result).data();
            if (json) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "success");
                out.put("data", data);
                System.out.println(GsonFactory.createGson().toJson(out));
            } else {
                System.out.println("Axiom removed successfully.");
                System.out.println("  transactionId:        " + data.get("transactionId"));
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
