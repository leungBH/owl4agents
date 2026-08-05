package org.owl4agents.cli;

import org.owl4agents.core.OntologyId;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * v0.9.1 mcp-write-tools-expansion: audit-log CLI command (readonly).
 * Queries the append-only audit log for an ontology with optional filters.
 */
@Command(name = "audit-log",
    description = "Query the audit log for an ontology with optional filters (v0.9.1 readonly).")
public class AuditLogCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--from"}, description = "ISO-8601 start instant (e.g. 2026-01-01T00:00:00Z)")
    private String from;

    @Option(names = {"--to"}, description = "ISO-8601 end instant (e.g. 2026-12-31T23:59:59Z)")
    private String to;

    @Option(names = {"--op"}, description = "Filter by operation (e.g. add_axiom, commit, rollback)")
    private String op;

    @Option(names = {"--transaction"}, description = "Filter by transaction ID")
    private String transaction;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Option(names = {"--json"}, description = "Emit JSON output to stdout")
    private boolean json;

    @Override
    public Integer call() {
        Instant fromInstant = parseInstant(from);
        Instant toInstant = parseInstant(to);

        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        List<Map<String, Object>> entries = factory.getAuditLog()
            .queryAsMaps(new OntologyId(ontologyId), fromInstant, toInstant, op, transaction);

        if (json) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "success");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ontologyId", ontologyId);
            data.put("entries", entries);
            data.put("count", entries.size());
            out.put("data", data);
            System.out.println(GsonFactory.createGson().toJson(out));
        } else {
            System.out.println("Audit log for '" + ontologyId + "' (" + entries.size() + " entries):");
            for (Map<String, Object> e : entries) {
                System.out.println("  " + e.getOrDefault("timestamp", "?")
                    + " | op=" + e.getOrDefault("operation", "?")
                    + " | author=" + e.getOrDefault("author", "?")
                    + " | result=" + e.getOrDefault("result", "?"));
                Object txId = e.get("transactionId");
                if (txId != null) {
                    System.out.println("      transactionId: " + txId);
                }
                Object detail = e.get("detail");
                if (detail != null && !detail.toString().isBlank()) {
                    System.out.println("      detail: " + detail);
                }
            }
        }
        return 0;
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            System.err.println("Error: invalid ISO-8601 instant: " + s);
            return null;
        }
    }
}
