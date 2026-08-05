package org.owl4agents.cli;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.reasoner.write.VersionSnapshot;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * v0.9.1 mcp-write-tools-expansion: rollback-to-version CLI command.
 * Restores the committed ontology to the content of a specific version snapshot.
 */
@Command(name = "rollback-to-version",
    description = "Restore the committed ontology to a specific version snapshot (v0.9.1).")
public class RollbackToVersionCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Parameters(index = "1", description = "Version ID to restore")
    private String versionId;

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
        ServiceResult<VersionSnapshot> result = factory.getWriteTransactionService()
            .rollbackToVersion(new OntologyId(ontologyId), versionId, author);

        if (result.isSuccess()) {
            VersionSnapshot snap = ((ServiceResult.Success<VersionSnapshot>) result).data();
            if (json) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "success");
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("versionId", snap.versionId());
                data.put("restoredFrom", versionId);
                data.put("axiomCount", snap.axiomCount());
                data.put("entityCount", snap.entityCount());
                data.put("contentChecksum", snap.contentChecksum());
                out.put("data", data);
                System.out.println(GsonFactory.createGson().toJson(out));
            } else {
                System.out.println("Rollback-to-version successful.");
                System.out.println("  versionId:       " + snap.versionId());
                System.out.println("  restoredFrom:    " + versionId);
                System.out.println("  axiomCount:     " + snap.axiomCount());
                System.out.println("  entityCount:    " + snap.entityCount());
                System.out.println("  contentChecksum: " + snap.contentChecksum());
            }
            return 0;
        } else {
            ServiceError error = ((ServiceResult.Error<VersionSnapshot>) result).error();
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
