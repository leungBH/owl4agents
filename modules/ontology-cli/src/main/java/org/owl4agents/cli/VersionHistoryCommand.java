package org.owl4agents.cli;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.reasoner.write.VersionSnapshot;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * v0.9.1 mcp-write-tools-expansion: version-history CLI command (readonly).
 * Lists version snapshots for an ontology (newest first).
 */
@Command(name = "version-history",
    description = "List version snapshots for an ontology, newest first (v0.9.1 readonly).")
public class VersionHistoryCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--limit"}, description = "Maximum number of snapshots to return (default: 50)")
    private int limit = 50;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Option(names = {"--json"}, description = "Emit JSON output to stdout")
    private boolean json;

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        ServiceResult<List<VersionSnapshot>> result = factory.getVersionHistoryStore()
            .listHistory(new OntologyId(ontologyId), limit);

        if (result.isSuccess()) {
            List<VersionSnapshot> snaps = ((ServiceResult.Success<List<VersionSnapshot>>) result).data();
            if (json) {
                List<Map<String, Object>> versions = new ArrayList<>();
                for (VersionSnapshot s : snaps) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("versionId", s.versionId());
                    m.put("createdAt", s.createdAt().toString());
                    m.put("author", s.author());
                    m.put("parentVersionId", s.parentVersionId());
                    m.put("changeSummary", s.changeSummary());
                    m.put("axiomCount", s.axiomCount());
                    m.put("entityCount", s.entityCount());
                    m.put("contentChecksum", s.contentChecksum());
                    versions.add(m);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "success");
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("ontologyId", ontologyId);
                data.put("versions", versions);
                data.put("count", versions.size());
                out.put("data", data);
                System.out.println(GsonFactory.createGson().toJson(out));
            } else {
                System.out.println("Version history for '" + ontologyId + "' (" + snaps.size() + " snapshots):");
                for (VersionSnapshot s : snaps) {
                    System.out.println("  " + s.versionId()
                        + " | " + s.createdAt()
                        + " | author=" + s.author()
                        + " | axioms=" + s.axiomCount()
                        + " | entities=" + s.entityCount());
                    if (s.changeSummary() != null && !s.changeSummary().isBlank()) {
                        System.out.println("      summary: " + s.changeSummary());
                    }
                }
            }
            return 0;
        } else {
            ServiceError error = ((ServiceResult.Error<List<VersionSnapshot>>) result).error();
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
