package org.owl4agents.reasoner.write;

import java.time.Instant;
import java.util.Map;

/**
 * v0.9.1 mcp-write-tools-expansion D5: Append-only audit log entry.
 *
 * <p>One JSON line per write operation. Schema fields per design.md D5:
 * {@code auditId}, {@code timestamp}, {@code ontologyId}, {@code transactionId}
 * (nullable), {@code operation}, {@code author}, {@code before} (nullable),
 * {@code after} (nullable), {@code versionId} (nullable), {@code result},
 * {@code detail}.</p>
 *
 * @param auditId       UUID identifying this entry
 * @param timestamp     ISO-8601 instant when the operation occurred
 * @param ontologyId    target ontology ID
 * @param transactionId transaction ID (null for import / version_pruned /
 *                      transaction_lost_on_shutdown with no transaction)
 * @param operation     one of: add_axiom, remove_axiom, edit_entity,
 *                      create_class, merge, commit, rollback,
 *                      rollback_to_version, import, version_pruned,
 *                      transaction_expired, transaction_lost_on_shutdown
 * @param author        caller-supplied identity (default "mcp" / "cli")
 * @param before        pre-operation state snapshot (nullable)
 * @param after         post-operation state snapshot (nullable)
 * @param versionId     resulting VersionSnapshot ID (null except for
 *                      commit / rollback_to_version / import success)
 * @param result        one of: ok, rejected, conflict, shacl_violation, expired
 * @param detail        free-text context (e.g. SHACL report reference)
 */
public record AuditEntry(
    String auditId,
    Instant timestamp,
    String ontologyId,
    String transactionId,
    String operation,
    String author,
    Object before,
    Object after,
    String versionId,
    String result,
    String detail
) {
    public AuditEntry {
        if (auditId == null || auditId.isBlank()) {
            auditId = java.util.UUID.randomUUID().toString();
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (operation == null || operation.isBlank()) {
            operation = "unknown";
        }
        if (result == null || result.isBlank()) {
            result = "ok";
        }
        if (author == null || author.isBlank()) {
            author = "mcp";
        }
    }

    /**
     * Convert this entry to a JSON-serializable map. Null fields are
     * rendered as JSON null (not omitted) so the schema is stable.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("auditId", auditId);
        m.put("timestamp", timestamp.toString());
        m.put("ontologyId", ontologyId);
        m.put("transactionId", transactionId);
        m.put("operation", operation);
        m.put("author", author);
        m.put("before", before);
        m.put("after", after);
        m.put("versionId", versionId);
        m.put("result", result);
        m.put("detail", detail);
        return m;
    }
}
