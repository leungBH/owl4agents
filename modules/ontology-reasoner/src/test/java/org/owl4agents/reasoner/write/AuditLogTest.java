package org.owl4agents.reasoner.write;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.OntologyId;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.9.1 mcp-write-tools-expansion D5: AuditLog unit tests.
 *
 * <p>Covers append-only writes, query filters, rotation, maxFiles
 * enforcement, disabled mode (no-op), and per-ontology isolation.</p>
 */
@DisplayName("v0.9.1 AuditLog (D5)")
class AuditLogTest {

    private static final String WS_NAME = "default";

    private AuditEntry entry(OntologyId oid, String op, String txId, String result, String detail) {
        return new AuditEntry(
            null, Instant.now(), oid.id(), txId, op, "tester",
            null, null, null, result, detail);
    }

    @Test
    @DisplayName("append + query round-trip preserves entries in chronological order")
    void appendAndQueryRoundTrip(@TempDir Path tmp) {
        AuditLog log = new AuditLog(tmp.toString(), WS_NAME, true, 104_857_600L, 10);
        OntologyId oid = new OntologyId("test-ont");

        log.append(oid, entry(oid, "add_axiom", "tx-1", "ok", "first"));
        log.append(oid, entry(oid, "remove_axiom", "tx-1", "ok", "second"));
        log.append(oid, entry(oid, "commit", "tx-1", "ok", "third"));

        List<AuditEntry> all = log.query(oid, null, null, null, null);
        assertEquals(3, all.size(), "all 3 entries must be returned");
        assertEquals("add_axiom", all.get(0).operation());
        assertEquals("remove_axiom", all.get(1).operation());
        assertEquals("commit", all.get(2).operation());
    }

    @Test
    @DisplayName("query filters by operation")
    void queryFiltersByOperation(@TempDir Path tmp) {
        AuditLog log = new AuditLog(tmp.toString(), WS_NAME, true, 104_857_600L, 10);
        OntologyId oid = new OntologyId("test-ont");

        log.append(oid, entry(oid, "add_axiom", "tx-1", "ok", "a"));
        log.append(oid, entry(oid, "commit", "tx-1", "ok", "b"));
        log.append(oid, entry(oid, "add_axiom", "tx-2", "ok", "c"));

        List<AuditEntry> adds = log.query(oid, null, null, "add_axiom", null);
        assertEquals(2, adds.size());
        assertTrue(adds.stream().allMatch(e -> "add_axiom".equals(e.operation())));
    }

    @Test
    @DisplayName("query filters by transactionId")
    void queryFiltersByTransactionId(@TempDir Path tmp) {
        AuditLog log = new AuditLog(tmp.toString(), WS_NAME, true, 104_857_600L, 10);
        OntologyId oid = new OntologyId("test-ont");

        log.append(oid, entry(oid, "add_axiom", "tx-A", "ok", "a1"));
        log.append(oid, entry(oid, "add_axiom", "tx-B", "ok", "b1"));
        log.append(oid, entry(oid, "commit", "tx-A", "ok", "a2"));

        List<AuditEntry> txA = log.query(oid, null, null, null, "tx-A");
        assertEquals(2, txA.size());
        assertTrue(txA.stream().allMatch(e -> "tx-A".equals(e.transactionId())));
    }

    @Test
    @DisplayName("disabled audit log is a no-op (append writes nothing)")
    void disabledIsNoOp(@TempDir Path tmp) {
        AuditLog log = new AuditLog(tmp.toString(), WS_NAME, false, 104_857_600L, 10);
        OntologyId oid = new OntologyId("test-ont");

        log.append(oid, entry(oid, "add_axiom", "tx-1", "ok", "ignored"));
        List<AuditEntry> all = log.query(oid, null, null, null, null);
        assertTrue(all.isEmpty(), "disabled audit log must not persist entries");
        assertFalse(Files.exists(tmp.resolve(WS_NAME).resolve("ontologies")
            .resolve("test-ont").resolve("audit.jsonl")));
    }

    @Test
    @DisplayName("queryAsMaps returns map representation matching AuditEntry.toMap")
    void queryAsMapsMatchesToMap(@TempDir Path tmp) {
        AuditLog log = new AuditLog(tmp.toString(), WS_NAME, true, 104_857_600L, 10);
        OntologyId oid = new OntologyId("test-ont");
        log.append(oid, entry(oid, "add_axiom", "tx-1", "ok", "detail-text"));

        List<Map<String, Object>> maps = log.queryAsMaps(oid, null, null, null, null);
        assertEquals(1, maps.size());
        assertEquals("add_axiom", maps.get(0).get("operation"));
        assertEquals("ok", maps.get(0).get("result"));
        assertEquals("detail-text", maps.get(0).get("detail"));
    }

    @Test
    @DisplayName("rotation triggers when current file exceeds maxBytes")
    void rotationTriggersOnSize(@TempDir Path tmp) throws Exception {
        // maxBytes=1 byte forces rotation after the first append (line > 1 byte)
        AuditLog log = new AuditLog(tmp.toString(), WS_NAME, true, 1L, 5);
        OntologyId oid = new OntologyId("rot-ont");

        log.append(oid, entry(oid, "add_axiom", "tx-1", "ok", "first"));
        log.append(oid, entry(oid, "add_axiom", "tx-2", "ok", "second"));
        log.append(oid, entry(oid, "add_axiom", "tx-3", "ok", "third"));

        Path ontDir = tmp.resolve(WS_NAME).resolve("ontologies").resolve("rot-ont");
        long rotatedCount;
        try (var stream = Files.list(ontDir)) {
            rotatedCount = stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("audit.") && n.endsWith(".jsonl") && n.length() > "audit..jsonl".length();
            }).count();
        }
        assertTrue(rotatedCount >= 2,
            "at least 2 rotated files expected; got " + rotatedCount);

        // All entries must still be queryable across rotated + current files
        List<AuditEntry> all = log.query(oid, null, null, null, null);
        assertEquals(3, all.size(), "rotation must not lose entries");
    }

    @Test
    @DisplayName("maxFiles enforcement deletes oldest rotated file")
    void maxFilesEnforcement(@TempDir Path tmp) throws Exception {
        AuditLog log = new AuditLog(tmp.toString(), WS_NAME, true, 1L, 2);
        OntologyId oid = new OntologyId("maxf-ont");

        // Append enough entries to trigger multiple rotations.
        for (int i = 0; i < 8; i++) {
            log.append(oid, entry(oid, "add_axiom", "tx-" + i, "ok", "entry-" + i));
        }

        Path ontDir = tmp.resolve(WS_NAME).resolve("ontologies").resolve("maxf-ont");
        long rotatedCount;
        try (var stream = Files.list(ontDir)) {
            rotatedCount = stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("audit.") && n.endsWith(".jsonl") && n.length() > "audit..jsonl".length();
            }).count();
        }
        assertTrue(rotatedCount <= 2,
            "rotated file count must not exceed maxFiles=2; got " + rotatedCount);
    }

    @Test
    @DisplayName("per-ontology isolation: entries for one ontology are not visible to another")
    void perOntologyIsolation(@TempDir Path tmp) {
        AuditLog log = new AuditLog(tmp.toString(), WS_NAME, true, 104_857_600L, 10);
        OntologyId a = new OntologyId("ont-a");
        OntologyId b = new OntologyId("ont-b");

        log.append(a, entry(a, "add_axiom", "tx-1", "ok", "for-a"));
        log.append(b, entry(b, "add_axiom", "tx-1", "ok", "for-b"));

        assertEquals(1, log.query(a, null, null, null, null).size());
        assertEquals(1, log.query(b, null, null, null, null).size());
        assertEquals("for-a", log.query(a, null, null, null, null).get(0).detail());
        assertEquals("for-b", log.query(b, null, null, null, null).get(0).detail());
    }

    @Test
    @DisplayName("query on missing ontology returns empty list (no file created)")
    void queryMissingOntologyReturnsEmpty(@TempDir Path tmp) {
        AuditLog log = new AuditLog(tmp.toString(), WS_NAME, true, 104_857_600L, 10);
        OntologyId oid = new OntologyId("never-existed");
        List<AuditEntry> all = log.query(oid, null, null, null, null);
        assertNotNull(all);
        assertTrue(all.isEmpty());
    }

    @Test
    @DisplayName("append with null entry is a safe no-op")
    void appendNullEntryIsNoOp(@TempDir Path tmp) {
        AuditLog log = new AuditLog(tmp.toString(), WS_NAME, true, 104_857_600L, 10);
        OntologyId oid = new OntologyId("null-ent");
        log.append(oid, null);
        assertTrue(log.query(oid, null, null, null, null).isEmpty());
    }
}
