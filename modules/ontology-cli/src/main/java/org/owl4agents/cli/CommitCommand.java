package org.owl4agents.cli;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.mcp.WriteToolsHandler;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * v0.9.1 mcp-write-tools-expansion: commit CLI command.
 * Persists a transaction's staging ontology to the workspace canonical file.
 *
 * <p>Delegates to {@link WriteToolsHandler} so that SHACL-on-commit (D3)
 * is applied identically to the MCP path. This ensures CLI/MCP parity:
 * the same SHACL shapes are evaluated, the same {@code COMMIT_SHACL_VIOLATION}
 * response shape is returned, and the transaction stays open on violation.</p>
 *
 * <p>Exit codes (per task 9.6):
 * <ul>
 *   <li>0 — success OR {@code COMMIT_SHACL_VIOLATION} (structured report on stdout)</li>
 *   <li>1 — {@code TRANSACTION_NOT_FOUND} / {@code TRANSACTION_CONFLICT} / other errors</li>
 *   <li>2 — write mode not enabled</li>
 * </ul></p>
 */
@Command(name = "commit",
    description = "Commit a write transaction, creating a version snapshot (v0.9.1).")
public class CommitCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Parameters(index = "1", description = "Transaction ID (UUID)")
    private String transactionId;

    @Option(names = {"--message"}, description = "Commit message / change summary")
    private String message;

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
        WriteToolsHandler handler = new WriteToolsHandler(
            factory.getWriteTransactionService(),
            factory.getOntologyEditService(),
            factory.getVersionHistoryStore(),
            factory.getAuditLog(),
            factory.getShaclValidationService(),
            factory.getShapeRegistry(),
            factory.getSharedOntologyCache(),
            factory.getCatalogStore(),
            factory.getHomeResolver(),
            factory.getWorkspaceId(),
            null);

        Map<String, Object> args = new HashMap<>();
        args.put("ontology_id", ontologyId);
        args.put("transaction_id", transactionId);
        if (message != null) args.put("message", message);
        args.put("author", author);

        Map<String, Object> response = handler.execute("ontology_commit", args);
        String status = String.valueOf(response.get("status"));

        if (json) {
            System.out.println(GsonFactory.createGson().toJson(response));
        } else {
            if ("success".equals(status)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                System.out.println("Commit successful.");
                System.out.println("  transactionId:    " + data.get("transactionId"));
                System.out.println("  versionId:       " + data.get("versionId"));
                System.out.println("  axiomCount:      " + data.get("axiomCount"));
                System.out.println("  entityCount:     " + data.get("entityCount"));
                System.out.println("  contentChecksum: " + data.get("contentChecksum"));
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> err = (Map<String, Object>) response.get("error");
                System.err.println("Error: " + err.get("code") + " - " + err.get("message"));
                if (err.containsKey("details")) {
                    System.err.println("Details: " + err.get("details"));
                }
            }
        }

        // Task 9.6: exit 0 on success AND on COMMIT_SHACL_VIOLATION (structured report);
        // nonzero on TRANSACTION_NOT_FOUND / TRANSACTION_CONFLICT / other errors.
        if ("success".equals(status)) {
            return 0;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) response.get("error");
        String code = String.valueOf(err.get("code"));
        if (ErrorCode.COMMIT_SHACL_VIOLATION.code().equals(code)) {
            return 0;
        }
        return 1;
    }
}
