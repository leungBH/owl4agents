package org.owl4agents.cli;

import org.owl4agents.mcp.WriteToolsHandler;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * v0.9.1 mcp-write-tools-expansion: diff CLI command (readonly).
 * Compares two ontology views (committed / versionId / transaction:&lt;id&gt;)
 * and lists added/removed axioms.
 */
@Command(name = "diff",
    description = "Compare two ontology views and list added/removed axioms (v0.9.1 readonly).")
public class DiffCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--from"}, description = "Source view: 'committed' (default), a versionId, or 'transaction:<id>'")
    private String from = "committed";

    @Option(names = {"--to"}, description = "Target view: 'committed' (default), a versionId, or 'transaction:<id>'")
    private String to = "committed";

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Option(names = {"--json"}, description = "Emit JSON output to stdout")
    private boolean json;

    @Override
    public Integer call() {
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
        args.put("from", from);
        args.put("to", to);
        Map<String, Object> response = handler.execute("ontology_diff", args);

        String status = String.valueOf(response.get("status"));
        if ("success".equals(status)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (json) {
                System.out.println(GsonFactory.createGson().toJson(response));
            } else {
                System.out.println("Diff: " + data.get("ontologyId")
                    + " (from=" + data.get("from") + ", to=" + data.get("to") + ")");
                System.out.println("  addedCount:   " + data.get("addedCount"));
                System.out.println("  removedCount: " + data.get("removedCount"));
                @SuppressWarnings("unchecked")
                List<String> added = (List<String>) data.get("added");
                @SuppressWarnings("unchecked")
                List<String> removed = (List<String>) data.get("removed");
                if (added != null && !added.isEmpty()) {
                    System.out.println("  Added axioms:");
                    for (String a : added) {
                        System.out.println("    + " + a);
                    }
                }
                if (removed != null && !removed.isEmpty()) {
                    System.out.println("  Removed axioms:");
                    for (String r : removed) {
                        System.out.println("    - " + r);
                    }
                }
            }
            return 0;
        } else {
            @SuppressWarnings("unchecked")
            Map<String, Object> err = (Map<String, Object>) response.get("error");
            if (json) {
                System.out.println(GsonFactory.createGson().toJson(response));
            } else {
                System.err.println("Error: " + err.get("code") + " - " + err.get("message"));
                if (err.containsKey("details")) {
                    System.err.println("Details: " + err.get("details"));
                }
            }
            return 1;
        }
    }
}
